import { durationEnv, env, positiveIntegerEnv, positiveNumberEnv } from "./lib/environment.js";

export const summaryTrendStats = ["min", "avg", "med", "p(90)", "p(95)", "p(99)", "max"];

export function commonThresholds(extra = {}) {
  const thresholds = {
    http_req_failed: ["rate<0.01"],
    checks: ["rate>=0.99"],
    ...extra,
  };

  const p95Ms = env("P95_MS");
  if (p95Ms !== undefined) {
    thresholds.http_req_duration = [`p(95)<${positiveNumberEnv("P95_MS", undefined)}`];
  }

  return thresholds;
}

export const defaultReadOptions = {
  scenarios: {
    read_workload: {
      executor: "constant-vus",
      vus: positiveIntegerEnv("READ_VUS", 10, 1, 500),
      duration: durationEnv("READ_DURATION", "1m"),
      gracefulStop: "10s",
    },
  },
  thresholds: commonThresholds(),
  summaryTrendStats,
};
