export function env(name, fallback = undefined) {
  const value = __ENV[name];
  if (value === undefined || value === null || `${value}`.trim() === "") {
    return fallback;
  }
  return value;
}

export function numberEnv(name, fallback) {
  const value = env(name);
  if (value === undefined) {
    return fallback;
  }
  const parsed = Number(value);
  if (Number.isNaN(parsed)) {
    throw new Error(`${name} must be a number. value=${value}`);
  }
  return parsed;
}

export function boolEnv(name, fallback = false) {
  const value = env(name);
  if (value === undefined) {
    return fallback;
  }
  return ["1", "true", "yes", "y"].includes(`${value}`.toLowerCase());
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

export const BASE_URL = stripTrailingSlash(env("BASE_URL", "http://localhost:8080"));
export const WIREMOCK_URL = stripTrailingSlash(env("WIREMOCK_URL", "http://localhost:8090"));
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

export function uniqueRateLimitIp(name) {
  const prefix = env("RATE_LIMIT_IP_PREFIX", "10.67");
  const runId = env("K6_RUN_ID", `${Date.now()}`);
  const seed = `${name}:${runId}:${__VU}:${__ITER}`;
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  }
  const third = 1 + (hash % 200);
  const fourth = 1 + ((hash >>> 8) % 200);
  return `${prefix}.${third}.${fourth}`;
}
