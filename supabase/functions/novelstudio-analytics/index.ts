import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const allowedEvents = new Set([
  "app_open", "mode_selected", "translation_started", "translation_completed",
  "translation_failed", "target_language_selected", "ocr_model_selected",
  "prefetch_toggled", "prefetch_completed", "manual_ocr_used", "app_update_seen",
]);
const allowedModes = new Set(["novel", "scanning", "realtime"]);

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
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
  const forwarded = req.headers.get("x-forwarded-for")?.split(",").map(value => value.trim()).filter(Boolean).slice(-1)[0];
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
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) return response({ error: "Server configuration missing" }, 500);

  const supabase = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const ingressLimit = await enforceIpRateLimit(req, serviceKey, supabase);
  if (ingressLimit === "blocked") return response({ error: "Rate limit exceeded" }, 429);
  if (ingressLimit === "unavailable") return response({ error: "Rate limiter unavailable" }, 503);


  let body: Record<string, unknown>;
  try {
    body = await readJsonLimited(req, 4096);
  } catch (error) {
    const tooLarge = error instanceof Error && error.message === "payload_too_large";
    return response({ error: tooLarge ? "Payload too large" : "Invalid JSON" }, tooLarge ? 413 : 400);
  }

  const eventName = body.event_name;
  const installId = body.install_id;
  if (typeof eventName !== "string" || !allowedEvents.has(eventName)) {
    return response({ error: "Unsupported event" }, 400);
  }
  if (typeof installId !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(installId)) {
    return response({ error: "install_id must be a UUID" }, 400);
  }

  const str = (key: string, max: number): string | null => {
    const value = body[key];
    return typeof value === "string" && value.length > 0 && value.length <= max ? value : null;
  };
  const mode = str("mode", 20);
  if (mode && !allowedModes.has(mode)) return response({ error: "Unsupported mode" }, 400);

  const duration = body.duration_ms;
  if (duration !== undefined &&
      (!Number.isInteger(duration) || Number(duration) < 0 || Number(duration) > 3600000)) {
    return response({ error: "Invalid duration_ms" }, 400);
  }

// Basic per-install rate limit. This is abuse reduction, not strong identity verification.
  const since = new Date(Date.now() - 10 * 60 * 1000).toISOString();
  const { count, error: countError } = await supabase
    .from("analytics_events")
    .select("id", { count: "exact", head: true })
    .eq("install_id", installId)
    .gte("created_at", since);
  if (countError) return response({ error: "Rate-limit check failed" }, 500);
  if ((count ?? 0) >= 60) return response({ error: "Rate limit exceeded" }, 429);

  const row = {
    event_name: eventName,
    install_id: installId,
    app_version: str("app_version", 40),
    android_version: str("android_version", 30),
    mode,
    target_language: str("target_language", 40),
    ocr_model: str("ocr_model", 80),
    provider: str("provider", 40),
    success: typeof body.success === "boolean" ? body.success : null,
    duration_ms: typeof duration === "number" ? duration : null,
  };

  const { error } = await supabase.from("analytics_events").insert(row);
  if (error) return response({ error: "Event could not be saved" }, 500);
  return response({ ok: true }, 202);
});