import { NextRequest, NextResponse } from 'next/server';
import { createClient } from '@supabase/supabase-js';
import { checkRateLimit, clientIp } from '../../../../utils/rateLimit';

// Prijavljeni korisnik veže token svog telefona (Android aplikacija) za obavijesti.
export async function POST(request: NextRequest) {
  const rl = checkRateLimit('push-native:' + clientIp(request), 20, 600000);
  if (!rl.allowed) return NextResponse.json({ error: 'Previše pokušaja.' }, { status: 429 });
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY;
  if (!url || !key) return NextResponse.json({ error: 'Nije konfigurisano.' }, { status: 503 });
  const bearer = request.headers.get('authorization')?.replace(/^Bearer\s+/i, '');
  if (!bearer) return NextResponse.json({ error: 'Prijavite se.' }, { status: 401 });
  const admin = createClient(url, key, { auth: { autoRefreshToken: false, persistSession: false } });
  const { data: who } = await admin.auth.getUser(bearer);
  if (!who?.user) return NextResponse.json({ error: 'Sesija nije važeća.' }, { status: 401 });
  const body = await request.json().catch(() => null);
  const token = typeof body?.token === 'string' ? body.token.trim() : '';
  if (!/^[\w:\-.]{20,4096}$/.test(token)) return NextResponse.json({ error: 'Neispravan token.' }, { status: 400 });
  const { error } = await admin.from('native_push_tokens').upsert(
    { token, user_id: who.user.id, platform: 'android', updated_at: new Date().toISOString() },
    { onConflict: 'token' }
  );
  if (error) return NextResponse.json({ error: 'Nije spremljeno.' }, { status: 500 });
  return NextResponse.json({ success: true });
}
