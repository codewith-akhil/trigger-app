// Shared Secret-Vault PIN hashing (used by upsert-vault-pin, verify-vault-pin,
// reset-vault-pin).
// ----------------------------------------------------------------------------
// VERSIONED FORMAT stored in vault_pins.pin_hash:
//
//   pbkdf2:<iterations>:<salt-b64url>:<hash-b64url>   (current)
//   <salt-uuid>:<sha256-hex>                          (legacy — verified, then
//                                                      transparently upgraded)
//
// The legacy single-iteration salted SHA-256 left a 10^6 keyspace open to
// offline brute force if the DB ever leaked. PBKDF2-SHA256 with 120k
// iterations (WebCrypto native) makes each guess ~120k× more expensive while
// keeping verification fast enough for a 6-digit PIN flow (<100ms).
// ----------------------------------------------------------------------------

const PBKDF2_ITERATIONS = 120_000;
const HASH_BYTES = 32;

function b64urlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlDecode(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function pbkdf2(pin: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(pin),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: salt as unknown as BufferSource, iterations },
    key,
    HASH_BYTES * 8,
  );
  return new Uint8Array(bits);
}

async function legacySha256(pin: string, salt: string): Promise<string> {
  const data = new TextEncoder().encode(`${pin}:${salt}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time-ish equality (no early exit on the digest bytes). */
function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** Hash a PIN for storage (PBKDF2, fresh random salt). */
export async function hashVaultPin(pin: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await pbkdf2(pin, salt, PBKDF2_ITERATIONS);
  return `pbkdf2:${PBKDF2_ITERATIONS}:${b64urlEncode(salt)}:${b64urlEncode(hash)}`;
}

export interface VaultPinVerifyResult {
  ok: boolean;
  /** True when the stored hash used the legacy scheme and verify succeeded —
   *  the caller should re-store with hashVaultPin() to upgrade. */
  needsUpgrade: boolean;
}

/** Verify a PIN against the stored pin_hash (supports both formats). */
export async function verifyVaultPin(pin: string, stored: string): Promise<VaultPinVerifyResult> {
  if (stored.startsWith("pbkdf2:")) {
    const [, iterStr, saltB64, hashB64] = stored.split(":");
    const iterations = parseInt(iterStr, 10);
    if (!iterations || !saltB64 || !hashB64) return { ok: false, needsUpgrade: false };
    const expected = b64urlDecode(hashB64);
    const candidate = await pbkdf2(pin, b64urlDecode(saltB64), iterations);
    return { ok: timingSafeEqual(candidate, expected), needsUpgrade: false };
  }
  // Legacy "<salt-uuid>:<sha256-hex>"
  const sep = stored.indexOf(":");
  if (sep <= 0) return { ok: false, needsUpgrade: false };
  const salt = stored.slice(0, sep);
  const legacyHash = stored.slice(sep + 1);
  const candidate = await legacySha256(pin, salt);
  const expectedBytes = new Uint8Array(legacyHash.match(/.{2}/g)?.map((h) => parseInt(h, 16)) ?? []);
  const candidateBytes = new Uint8Array(candidate.match(/.{2}/g)?.map((h) => parseInt(h, 16)) ?? []);
  const ok = timingSafeEqual(candidateBytes, expectedBytes) && legacyHash.length === 64;
  return { ok, needsUpgrade: ok };
}
