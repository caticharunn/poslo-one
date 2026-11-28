// Most između stranice i Android aplikacije za prave obavijesti (window.PosloNative iz MainActivity).
type Bridge = { pushStatus(): string; requestPush(): void; refreshToken(): void };
type W = Window & { PosloNative?: Bridge; __posloPushResult?: (ok: boolean, token: string) => void };

const TOKEN_KEY = 'poslo_fcm_token';
const SENT_KEY = 'poslo_fcm_sent';

function bridge(): Bridge | null {
  if (typeof window === 'undefined') return null;
  return (window as W).PosloNative || null;
}

export function isNativeApp() {
  return bridge() !== null;
}

/** 'granted' | 'denied' | 'default' | 'unsupported' */
export function nativePushStatus(): string {
  try {
    return bridge()?.pushStatus() || 'unsupported';
  } catch {
    return 'unsupported';
  }
}

function session(): { token: string; uid: string } | null {
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i) || '';
      if (key.startsWith('sb-') && key.endsWith('-auth-token')) {
        const s = JSON.parse(localStorage.getItem(key) || 'null');
        if (s?.access_token && s?.user?.id) return { token: s.access_token, uid: s.user.id };
      }
    }
  } catch {}
  return null;
}

/** Šalje token uređaja serveru za prijavljenog korisnika (samo kad se nešto promijenilo). */
export async function syncNativeToken() {
  try {
    const fcm = localStorage.getItem(TOKEN_KEY);
    const s = session();
    if (!fcm || !s) return;
    const mark = s.uid + ':' + fcm;
    if (localStorage.getItem(SENT_KEY) === mark) return;
    const res = await fetch('/api/push/native', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + s.token },
      body: JSON.stringify({ token: fcm }),
    });
    if (res.ok) localStorage.setItem(SENT_KEY, mark);
  } catch {}
}

function listen(resolve?: (ok: boolean) => void) {
  (window as W).__posloPushResult = (ok, token) => {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token);
    } catch {}
    void syncNativeToken();
    resolve?.(ok);
  };
}

/** Pita korisnika za dozvolu (Android dijalog) i vraća da li je dozvoljeno. */
export function requestNativePush(): Promise<boolean> {
  const b = bridge();
  if (!b) return Promise.resolve(false);
  return new Promise((resolve) => {
    const timer = setTimeout(() => resolve(false), 60000);
    listen((ok) => {
      clearTimeout(timer);
      resolve(ok);
    });
    b.requestPush();
  });
}

/** Pri pokretanju: osvježi token ako su obavijesti već dozvoljene. */
export function refreshNativePush() {
  const b = bridge();
  if (!b || nativePushStatus() !== 'granted') return;
  listen();
  try {
    b.refreshToken();
  } catch {}
}
