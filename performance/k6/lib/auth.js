import crypto from "k6/crypto";
import encoding from "k6/encoding";
import { JWT_SECRET, PERF_BOJ_ID, env } from "./environment.js";

function base64Url(input) {
  return encoding.b64encode(input, "rawurl");
}

function toBase64Url(base64) {
  return base64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function createJwt(
  subject = PERF_BOJ_ID,
  role = "USER",
  studentId = env("PERF_STUDENT_ID", "perf-student-1")
) {
  const nowSeconds = Math.floor(Date.now() / 1000);
  const ttlSeconds = Number(env("JWT_TTL_SECONDS", "3600"));
  const header = {
    alg: "HS256",
    typ: "JWT",
  };
  const payload = {
    sub: subject,
    role,
    type: "access",
    studentId,
    credentialVersion: 0,
    iat: nowSeconds,
    exp: nowSeconds + ttlSeconds,
  };

  const unsigned = `${base64Url(JSON.stringify(header))}.${base64Url(JSON.stringify(payload))}`;
  const signature = toBase64Url(crypto.hmac("sha256", JWT_SECRET, unsigned, "base64"));
  return `${unsigned}.${signature}`;
}

export function getAuthToken(role = "USER") {
  return env("JWT_TOKEN", createJwt(PERF_BOJ_ID, role));
}

export function authHeaders(token = getAuthToken(), extra = {}) {
  return {
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json",
    ...extra,
  };
}
