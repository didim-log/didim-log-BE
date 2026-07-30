export function env(name, fallback = undefined) {
  const value = __ENV[name];
  if (value === undefined || value === null || `${value}`.trim() === "") {
    return fallback;
  }
  return value;
}

export function numberEnv(name, fallback) {
  return positiveNumberEnv(name, fallback);
}

function rawEnv(name, fallback = undefined) {
  const value = env(name, fallback);
  if (value === undefined) {
    return undefined;
  }
  return `${value}`.trim();
}

function parseStrictNumber(name, fallback, { integer, min, max, allowZero }) {
  const value = env(name);
  if (value === undefined) {
    return fallback;
  }
  const trimmed = `${value}`.trim();
  const pattern = integer ? /^(0|[1-9]\d*)$/ : /^(0|[1-9]\d*)(\.\d+)?$/;
  if (!pattern.test(trimmed)) {
    throw new Error(`${name} must be a valid ${integer ? "integer" : "number"}. value=${value}`);
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || Number.isNaN(parsed)) {
    throw new Error(`${name} must be finite. value=${value}`);
  }
  if (!allowZero && parsed === 0) {
    throw new Error(`${name} must be greater than 0. value=${value}`);
  }
  if (parsed < min || parsed > max) {
    throw new Error(`${name} must be between ${min} and ${max}. value=${value}`);
  }
  return parsed;
}

export function positiveIntegerEnv(name, fallback, min = 1, max = Number.MAX_SAFE_INTEGER) {
  return parseStrictNumber(name, fallback, { integer: true, min, max, allowZero: false });
}

export function nonNegativeIntegerEnv(name, fallback, max = Number.MAX_SAFE_INTEGER) {
  return parseStrictNumber(name, fallback, { integer: true, min: 0, max, allowZero: true });
}

export function positiveNumberEnv(name, fallback, min = Number.MIN_VALUE, max = Number.MAX_VALUE) {
  return parseStrictNumber(name, fallback, { integer: false, min, max, allowZero: false });
}

export function booleanEnv(name, fallback = false) {
  const value = rawEnv(name);
  if (value === undefined) {
    return fallback;
  }
  const normalized = value.toLowerCase();
  if (["1", "true", "yes", "y"].includes(normalized)) {
    return true;
  }
  if (["0", "false", "no", "n"].includes(normalized)) {
    return false;
  }
  throw new Error(`${name} must be boolean. value=${value}`);
}

export const boolEnv = booleanEnv;

export function durationEnv(name, fallback) {
  const value = rawEnv(name, fallback);
  if (value === undefined) {
    return fallback;
  }
  if (!/^(0|[1-9]\d*)(ms|s|m|h)$/.test(value)) {
    throw new Error(`${name} must be a k6 duration such as 500ms, 30s, 2m, or 1h. value=${value}`);
  }
  return value;
}

export function listEnv(name, fallback = []) {
  const value = env(name);
  if (value === undefined) {
    return fallback;
  }
  return `${value}`
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export function stripTrailingSlash(value) {
  return `${value}`.replace(/\/+$/, "");
}

export const BASE_URL = stripTrailingSlash(env("BASE_URL", "http://127.0.0.1:8080"));
export const WIREMOCK_URL = stripTrailingSlash(env("WIREMOCK_URL", "http://localhost:8090"));
export const MONGO_URI = env("MONGO_URI", "mongodb://localhost:27017/didimlog-performance");
export const REDIS_HOST = env("REDIS_HOST", "localhost");
export const PERF_BOJ_ID = env("PERF_BOJ_ID", "perfuser");
export const JWT_SECRET = env(
  "JWT_SECRET",
  "performance-secret-key-must-be-at-least-256-bits-long-1234567890"
);

export function tags(endpoint, scenario, resultType = "unknown", authRole = "USER") {
  return {
    endpoint,
    scenario,
    authRole,
    resultType,
  };
}

export function pickWeighted(items) {
  const total = items.reduce((sum, item) => sum + item.weight, 0);
  if (total <= 0) {
    throw new Error("At least one positive weight is required.");
  }

  let cursor = Math.random() * total;
  for (const item of items) {
    cursor -= item.weight;
    if (cursor <= 0) {
      return item;
    }
  }
  return items[items.length - 1];
}

const LOCAL_ALLOWED_HOSTS = new Set([
  "localhost",
  "127.0.0.1",
  "::1",
  "mongo",
  "redis",
  "gemini-wiremock",
  "host.docker.internal",
]);

const PERFORMANCE_DB_NAME = "didimlog-performance";

function parseHttpUrl(name, value) {
  const parsed = parseUrlParts(value);
  if (parsed === null) {
    throw new Error(`${name} must be a valid URL. value=${value}`);
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new Error(`${name} protocol must be http or https. value=${value}`);
  }
  if (parsed.username || parsed.password) {
    throw new Error(`${name} must not include URL credentials. value=${value}`);
  }
  return parsed;
}

function parseUrlParts(value) {
  const match = /^([a-z][a-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)?([?#].*)?$/i.exec(`${value}`);
  if (!match) {
    return null;
  }
  const protocol = `${match[1].toLowerCase()}:`;
  const authority = match[2];
  if (authority.includes("@")) {
    const [credentials, hostPart] = authority.split("@");
    const hostname = extractHostname(hostPart);
    return { protocol, hostname, username: credentials, password: credentials, pathname: match[3] || "/" };
  }
  const hostname = extractHostname(authority);
  if (!hostname) {
    return null;
  }
  return {
    protocol,
    hostname,
    username: "",
    password: "",
    pathname: match[3] || "/",
  };
}

function extractHostname(authority) {
  if (authority.startsWith("[")) {
    const end = authority.indexOf("]");
    return end > 0 ? authority.slice(1, end) : "";
  }
  return authority.split(":")[0];
}

function hostAllowedForLocal(hostname) {
  return LOCAL_ALLOWED_HOSTS.has(hostname);
}

function remoteAllowed(name, parsed) {
  const targetEnvironment = `${env("TARGET_ENVIRONMENT", "local")}`.toLowerCase();
  if (targetEnvironment === "prod" || targetEnvironment === "production") {
    throw new Error("TARGET_ENVIRONMENT=prod/production is blocked for k6 performance assets.");
  }

  const allowRemote = booleanEnv("ALLOW_REMOTE_LOAD_TEST", false);
  if (targetEnvironment !== "staging" || !allowRemote) {
    return false;
  }

  if (name !== "BASE_URL") {
    throw new Error(`${name} must remain local. Remote MongoDB, Redis, and Gemini mocks are blocked.`);
  }
  if (parsed.protocol !== "https:") {
    throw new Error("Remote staging BASE_URL must use HTTPS.");
  }

  const allowlist = listEnv("REMOTE_TARGET_ALLOWLIST", []);
  if (!allowlist.includes(parsed.hostname)) {
    throw new Error(`Remote BASE_URL host is not in REMOTE_TARGET_ALLOWLIST. host=${parsed.hostname}`);
  }
  return true;
}

function assertHttpTarget(name, value) {
  const parsed = parseHttpUrl(name, value);
  if (hostAllowedForLocal(parsed.hostname) || remoteAllowed(name, parsed)) {
    return parsed;
  }
  throw new Error(`${name} host is not allowed for local performance tests. host=${parsed.hostname}`);
}

function parseMongoUri(value) {
  const parsed = parseUrlParts(value);
  if (parsed === null) {
    throw new Error(`MONGO_URI must be a valid URI. value=${value}`);
  }
  if (parsed.protocol !== "mongodb:") {
    throw new Error("MONGO_URI protocol must be mongodb:// for local verification.");
  }
  if (parsed.username || parsed.password) {
    throw new Error("MONGO_URI must not include credentials.");
  }
  const dbName = parsed.pathname.replace(/^\/+/, "").split("?")[0];
  if (!dbName) {
    throw new Error("MONGO_URI must include database name didimlog-performance.");
  }
  if (dbName !== PERFORMANCE_DB_NAME) {
    throw new Error(`MONGO_URI database must be ${PERFORMANCE_DB_NAME}. actual=${dbName}`);
  }
  if (!hostAllowedForLocal(parsed.hostname)) {
    throw new Error(`MONGO_URI host must be local. host=${parsed.hostname}`);
  }
  return parsed;
}

function assertRedisHost(value) {
  if (!hostAllowedForLocal(value)) {
    throw new Error(`REDIS_HOST must be local. host=${value}`);
  }
}

export function assertSafeEnvironment({ allowRemoteBaseUrl = true } = {}) {
  const targetEnvironment = `${env("TARGET_ENVIRONMENT", "local")}`.toLowerCase();
  if (targetEnvironment === "prod" || targetEnvironment === "production") {
    throw new Error("TARGET_ENVIRONMENT=prod/production is blocked.");
  }

  const baseUrl = assertHttpTarget("BASE_URL", BASE_URL);
  if (!allowRemoteBaseUrl && !hostAllowedForLocal(baseUrl.hostname)) {
    throw new Error("This operation requires a local BASE_URL.");
  }
  assertHttpTarget("WIREMOCK_URL", WIREMOCK_URL);
  parseMongoUri(MONGO_URI);
  assertRedisHost(REDIS_HOST);
}

export function validateConfiguredEnv() {
  positiveIntegerEnv("AI_CONCURRENCY", 50, 1, 500);
  positiveIntegerEnv("AI_REPEAT_COUNT", 10, 1, 100);
  positiveIntegerEnv("READ_VUS", 10, 1, 500);
  nonNegativeIntegerEnv("MOCK_GEMINI_DELAY_MS", 500, 30000);
  positiveIntegerEnv("RATE_LIMIT_OVERAGE_REQUESTS", 2, 1, 20);
  positiveIntegerEnv("READ_PAGE_SIZE", 10, 1, 100);
  durationEnv("READ_DURATION", "1m");
  durationEnv("AI_MAX_DURATION", "45s");
}
