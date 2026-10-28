import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}


async function readJsonLimited(req: Request, maxBytes: number): Promise<Record<string, unknown>> {
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > maxBytes) throw new Error("payload_too_large");
  if (!req.body) throw new Error("invalid_json");
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      try { await reader.cancel(); } catch {}
      throw new Error("payload_too_large");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("invalid_json");
  return parsed as Record<string, unknown>;
}

async function enforceIpRateLimit(req: Request, serviceKey: string, db: any): Promise<"allowed" | "blocked" | "unavailable"> {
  const forwarded = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  const ip = req.headers.get("cf-connecting-ip")?.trim()
    || req.headers.get("x-real-ip")?.trim()
    || forwarded;
  if (!ip || ip.length > 128) return "allowed";
  try {
    const hmacKey = await crypto.subtle.importKey(
      "raw", new TextEncoder().encode(serviceKey), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
    );
    const signature = await crypto.subtle.sign("HMAC", hmacKey, new TextEncoder().encode("analytics-ingress-v1:" + ip));
    const keyHash = Array.from(new Uint8Array(signature)).map(b => b.toString(16).padStart(2, "0")).join("");
    const windowMs = 10 * 60 * 1000;
    const bucketStart = new Date(Math.floor(Date.now() / windowMs) * windowMs).toISOString();
    const { data, error } = await db.rpc("consume_analytics_rate_limit", {
      p_key_hash: keyHash,
      p_bucket_start: bucketStart,
      p_max_hits: 600,
    });
    if (error) return "unavailable";
    const row = Array.isArray(data) ? data[0] : data;
    if (!row || typeof row.allowed !== "boolean") return "unavailable";
    return row.allowed ? "allowed" : "blocked";
  } catch {
    return "unavailable";
  }
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return response({ error: "POST required" }, 405);

  const url = Deno.env.get("SUPABASE_URL");
  const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) return response({ error: "Server configuration missing" }, 500);
  const db = createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
  const ingressLimit = await enforceIpRateLimit(req, key, db);
  if (ingressLimit === "blocked") return response({ error: "Rate limit exceeded" }, 429);
  if (ingressLimit === "unavailable") return response({ error: "Rate limiter unavailable" }, 503);

  let body: Record<string, unknown>;
  try {
    body = await readJsonLimited(req, 2048);
  } catch (error) {
    const tooLarge = error instanceof Error && error.message === "payload_too_large";
    return response({ error: tooLarge ? "Payload too large" : "Invalid JSON" }, tooLarge ? 413 : 400);
  }

  const installId = body.install_id;
  if (typeof installId !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(installId)) {
    return response({ error: "install_id must be a UUIDv4" }, 400);
  }
  const validModes = new Set(["novel", "scanning", "realtime"]);
  const mode = typeof body.mode === "string" && validModes.has(body.mode) ? body.mode : null;
  const appVersion = typeof body.app_version === "string" ? body.app_version.slice(0, 40) : null;
  const androidVersion = typeof body.android_version === "string" ? body.android_version.slice(0, 30) : null;


  const { data: existing, error: readError } = await db
    .from("device_presence").select("last_seen").eq("install_id", installId).maybeSingle();
  if (readError) return response({ error: "Presence lookup failed" }, 500);
  if (existing && Date.now() - new Date(existing.last_seen).getTime() < 8000) {
    return response({ ok: true, throttled: true }, 202);
  }

  const { error } = await db.from("device_presence").upsert({
    install_id: installId,
    app_version: appVersion,
    android_version: androidVersion,
    mode,
    last_seen: new Date().toISOString(),
  }, { onConflict: "install_id" });
  if (error) return response({ error: "Presence update failed" }, 500);
  return response({ ok: true }, 202);
});