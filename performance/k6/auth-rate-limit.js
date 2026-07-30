import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";
import {
  BASE_URL,
  assertSafeEnvironment,
  positiveIntegerEnv,
  tags,
  validateConfiguredEnv,
} from "./lib/environment.js";
import { checkStatus, parseJson } from "./lib/checks.js";
import { summaryHandlers } from "./lib/summary.js";
import { summaryTrendStats } from "./config.js";

http.setResponseCallback(http.expectedStatuses(400, 404, 429));

const signupAllowed = new Counter("rate_limit_signup_allowed");
const signupRejected = new Counter("rate_limit_signup_rejected");
const loginAllowed = new Counter("rate_limit_login_allowed");
const loginRejected = new Counter("rate_limit_login_rejected");
const passwordResetAllowed = new Counter("rate_limit_password_reset_allowed");
const passwordResetRejected = new Counter("rate_limit_password_reset_rejected");
const unexpectedStatus = new Counter("rate_limit_unexpected_status");
const policyMismatches = new Counter("rate_limit_policy_mismatches");
const signupBurstStartLag = new Trend("rate_limit_signup_burst_start_lag_ms");
const loginBurstStartLag = new Trend("rate_limit_login_burst_start_lag_ms");
const passwordResetBurstStartLag = new Trend("rate_limit_password_reset_burst_start_lag_ms");

const signupMax = positiveIntegerEnv("RATE_LIMIT_SIGNUP_MAX", 5);
const loginMax = positiveIntegerEnv("RATE_LIMIT_LOGIN_MAX", 10);
const passwordResetMax = positiveIntegerEnv("RATE_LIMIT_PASSWORD_RESET_MAX", 3);
const overLimitRequests = positiveIntegerEnv("RATE_LIMIT_OVERAGE_REQUESTS", 2, 1, 20);
const firstBurstLeadTimeMs = 2_000;
const scenarioSpacingMs = 5_000;
const maxBurstStartLagMs = 500;

export const options = {
  scenarios: {
    signup_rate_limit_policy: {
      executor: "per-vu-iterations",
      exec: "signupPolicy",
      vus: signupMax + overLimitRequests,
      iterations: 1,
      maxDuration: "30s",
    },
    login_rate_limit_policy: {
      executor: "per-vu-iterations",
      exec: "loginPolicy",
      vus: loginMax + overLimitRequests,
      iterations: 1,
      startTime: "5s",
      maxDuration: "30s",
    },
    password_reset_rate_limit_policy: {
      executor: "per-vu-iterations",
      exec: "passwordResetPolicy",
      vus: passwordResetMax + overLimitRequests,
      iterations: 1,
      startTime: "10s",
      maxDuration: "30s",
    },
  },
  thresholds: {
    checks: ["rate>=0.99"],
    rate_limit_signup_allowed: [`count==${signupMax}`],
    rate_limit_signup_rejected: [`count==${overLimitRequests}`],
    rate_limit_login_allowed: [`count==${loginMax}`],
    rate_limit_login_rejected: [`count==${overLimitRequests}`],
    rate_limit_password_reset_allowed: [`count==${passwordResetMax}`],
    rate_limit_password_reset_rejected: [`count==${overLimitRequests}`],
    rate_limit_unexpected_status: ["count==0"],
    rate_limit_policy_mismatches: ["count==0"],
    rate_limit_signup_burst_start_lag_ms: [`max<${maxBurstStartLagMs}`],
    rate_limit_login_burst_start_lag_ms: [`max<${maxBurstStartLagMs}`],
    rate_limit_password_reset_burst_start_lag_ms: [`max<${maxBurstStartLagMs}`],
  },
  summaryTrendStats,
};

export function setup() {
  assertSafeEnvironment({ allowRemoteBaseUrl: false });
  validateConfiguredEnv();
  return {
    firstBurstAtEpochMillis: Date.now() + firstBurstLeadTimeMs,
  };
}

export function signupPolicy(setupData) {
  const burstTargetEpochMillis = waitForBurst(setupData, 0);
  exerciseRequest(
    {
      name: "signup",
      endpoint: "/api/v1/auth/signup",
      maxRequests: signupMax,
      allowedStatuses: [400],
      allowedCounter: signupAllowed,
      rejectedCounter: signupRejected,
      payload: (suffix) => ({
        bojId: "",
        password: "short",
        email: `signup-${suffix}@example.test`,
      }),
    },
    burstTargetEpochMillis,
    signupBurstStartLag
  );
}

export function loginPolicy(setupData) {
  const burstTargetEpochMillis = waitForBurst(setupData, scenarioSpacingMs);
  exerciseRequest(
    {
      name: "login",
      endpoint: "/api/v1/auth/login",
      maxRequests: loginMax,
      allowedStatuses: [400],
      allowedCounter: loginAllowed,
      rejectedCounter: loginRejected,
      payload: () => ({
        bojId: "",
        password: "short",
      }),
    },
    burstTargetEpochMillis,
    loginBurstStartLag
  );
}

export function passwordResetPolicy(setupData) {
  const burstTargetEpochMillis = waitForBurst(setupData, scenarioSpacingMs * 2);
  exerciseRequest(
    {
      name: "password_reset",
      endpoint: "/api/v1/auth/find-password",
      maxRequests: passwordResetMax,
      allowedStatuses: [400],
      allowedCounter: passwordResetAllowed,
      rejectedCounter: passwordResetRejected,
      payload: () => ({
        email: "invalid",
        bojId: "",
      }),
    },
    burstTargetEpochMillis,
    passwordResetBurstStartLag
  );
}

function waitForBurst(setupData, offsetMillis) {
  const targetEpochMillis = setupData.firstBurstAtEpochMillis + offsetMillis;
  let remainingMillis = targetEpochMillis - Date.now();
  while (remainingMillis > 0) {
    sleep(remainingMillis / 1_000);
    remainingMillis = targetEpochMillis - Date.now();
  }
  return targetEpochMillis;
}

function exerciseRequest(target, burstTargetEpochMillis, burstStartLagMetric) {
  const tagSet = tags(target.name, "auth_rate_limit_policy", "concurrent_boundary", "ANONYMOUS");
  const payload = JSON.stringify(target.payload(`${Date.now()}-${__VU}-${__ITER}`));
  burstStartLagMetric.add(Date.now() - burstTargetEpochMillis, tagSet);
  const res = http.post(
    `${BASE_URL}${target.endpoint}`,
    payload,
    {
      headers: {
        "Content-Type": "application/json",
      },
      tags: tagSet,
    }
  );

  if (res.status === 429) {
    target.rejectedCounter.add(1, tagSet);
    checkStatus(res, 429, tagSet);
    check(
      res,
      {
        "429 body and headers match rate limit contract": (response) => {
          const body = parseJson(response);
          return (
            body !== null &&
            body.code === "RATE_LIMIT_EXCEEDED" &&
            body.remainingAttempts === 0 &&
            isJson(response) &&
            isFutureIso8601(body.unlockTime) &&
            headerValue(response, "Retry-After") !== "" &&
            headerValue(response, "X-Rate-Limit-Limit") === `${target.maxRequests}` &&
            headerValue(response, "X-Rate-Limit-Remaining") === "0"
          );
        },
      },
      tagSet
    );
    return;
  }

  const allowed = target.allowedStatuses.includes(res.status);
  target.allowedCounter.add(allowed ? 1 : 0, tagSet);
  if (!allowed) {
    policyMismatches.add(1, tagSet);
    unexpectedStatus.add(1, tagSet);
  }
  checkStatus(res, target.allowedStatuses, tagSet);
  check(
    res,
    {
      "within policy matches expected validation response": (response) =>
        target.allowedStatuses.includes(response.status),
      "within policy includes limit headers": (response) => {
        const remaining = Number(headerValue(response, "X-Rate-Limit-Remaining"));
        return (
          headerValue(response, "X-Rate-Limit-Limit") === `${target.maxRequests}` &&
          Number.isInteger(remaining) &&
          remaining >= 0 &&
          remaining < target.maxRequests
        );
      },
    },
    tagSet
  );
}

function headerValue(response, name) {
  const wanted = name.toLowerCase();
  for (const [key, value] of Object.entries(response.headers)) {
    if (key.toLowerCase() === wanted) {
      return `${value}`;
    }
  }
  return "";
}

function isJson(response) {
  const contentType = response.headers["Content-Type"] || response.headers["content-type"] || "";
  return contentType.includes("application/json");
}

function isFutureIso8601(value) {
  if (typeof value !== "string" || value.trim() === "") {
    return false;
  }
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) && parsed > Date.now();
}

export const handleSummary = summaryHandlers("auth-rate-limit");
