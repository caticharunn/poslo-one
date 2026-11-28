// Prave (nativne) obavijesti za Android aplikaciju preko Firebase Cloud Messaging (HTTP v1).
// Tajni ključ je u FIREBASE_SERVICE_ACCOUNT (cijeli JSON servisnog računa). Bez njega se ništa ne šalje.
import { createSign } from 'crypto';

type ServiceAccount = { project_id: string; client_email: string; private_key: string };
type Store = { from(table: string): any };
export type NativePayload = { title: string; body: string; url?: string };

let cached: { token: string; exp: number } | null = null;

function account(): ServiceAccount | null {
  try {
    const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
    if (!raw) return null;
    const a = JSON.parse(raw);
    return a?.project_id && a?.client_email && a?.private_key ? a : null;
  } catch {
    return null;
  }
}

export function isNativePushConfigured() {
  return account() !== null;
}

const b64 = (v: string | Buffer) => Buffer.from(v).toString('base64url');

async function accessToken(a: ServiceAccount) {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 60 > now) return cached.token;
  const head = b64(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64(JSON.stringify({
    iss: a.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now,
    exp: now + 3600,
  }));
  const signer = createSign('RSA-SHA256');
  signer.update(head + '.' + claims);
  const jwt = head + '.' + claims + '.' + b64(signer.sign(a.private_key));
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }),
  });
  const json = await res.json();
  if (!res.ok || !json.access_token) throw new Error('FCM token: ' + res.status);
  cached = { token: json.access_token, exp: now + (json.expires_in || 3600) };
  return cached.token;
}

/** Šalje obavijest na sve uređaje navedenih korisnika. Greške ne ruše glavni tok. */
export async function sendNativeToUsers(admin: Store, userIds: string[], payload: NativePayload) {
  const a = account();
  const ids = Array.from(new Set(userIds.filter(Boolean)));
  if (!a || !ids.length) return { sent: 0, failed: 0 };
  let sent = 0;
  let failed = 0;
  try {
    const { data } = await admin.from('native_push_tokens').select('token').in('user_id', ids);
    const tokens: string[] = (data || []).map((r: { token: string }) => r.token);
    if (!tokens.length) return { sent, failed };
    const bearer = await accessToken(a);
    await Promise.all(tokens.map(async (token) => {
      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${a.project_id}/messages:send`, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + bearer, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: {
            token,
            notification: { title: payload.title, body: payload.body },
            data: { url: payload.url || 'https://poslo.one/' },
            android: { priority: 'HIGH', notification: { channel_id: 'poslo', color: '#2563eb', icon: 'ic_notification' } },
          },
        }),
      });
      if (res.ok) { sent++; return; }
      failed++;
      if (res.status === 404 || res.status === 400) {
        await admin.from('native_push_tokens').delete().eq('token', token);
      }
    }));
  } catch (error) {
    console.error('fcm: slanje nije uspjelo:', error);
  }
  return { sent, failed };
}

/** Vlasnici (profili) jedne firme. */
export async function sendNativeToBusiness(admin: Store, businessId: string, payload: NativePayload) {
  if (!isNativePushConfigured() || !businessId) return { sent: 0, failed: 0 };
  const { data } = await admin.from('profiles').select('id').eq('business_id', businessId);
  return sendNativeToUsers(admin, (data || []).map((r: { id: string }) => r.id), payload);
}
