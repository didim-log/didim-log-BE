import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";
import {
  BASE_URL,
  assertSafeEnvironment,
  env,
  positiveIntegerEnv,
  positiveNumberEnv,
  tags,
  uniqueRateLimitIp,
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

const signupMax = positiveIntegerEnv("RATE_LIMIT_SIGNUP_MAX", 5);
const loginMax = positiveIntegerEnv("RATE_LIMIT_LOGIN_MAX", 10);
const passwordResetMax = positiveIntegerEnv("RATE_LIMIT_PASSWORD_RESET_MAX", 3);
const overLimitRequests = positiveIntegerEnv("RATE_LIMIT_OVERAGE_REQUESTS", 2, 1, 20);

export const options = {
  scenarios: {
    auth_rate_limit_policy: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "2m",
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
  },
  summaryTrendStats,
};

export function setup() {
  assertSafeEnvironment({ allowRemoteBaseUrl: false });
  validateConfiguredEnv();
}

export default function () {
  exercisePolicy({
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
  });

  exercisePolicy({
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
  });

  exercisePolicy({
    name: "password_reset",
    endpoint: "/api/v1/auth/reset-password",
    maxRequests: passwordResetMax,
    allowedStatuses: [400],
    allowedCounter: passwordResetAllowed,
    rejectedCounter: passwordResetRejected,
    payload: (suffix) => ({
      resetCode: `NO${suffix}`.slice(0, 8),
      newPassword: "short",
    }),
  });
}

function exercisePolicy(target) {
  const totalRequests = target.maxRequests + overLimitRequests;
  const clientIp = env(`RATE_LIMIT_${target.name.toUpperCase()}_IP`, uniqueRateLimitIp(target.name));

  for (let i = 1; i <= totalRequests; i += 1) {
    const expectedLimited = i > target.maxRequests;
    const resultType = expectedLimited ? "limited_429" : "within_limit_expected_error";
    const tagSet = tags(target.name, "auth_rate_limit_policy", resultType, "ANONYMOUS");
    const res = http.post(`${BASE_URL}${target.endpoint}`, JSON.stringify(target.payload(`${Date.now()}-${i}`)), {
      headers: {
        "Content-Type": "application/json",
        "X-Forwarded-For": clientIp,
      },
      tags: tagSet,
    });

    if (expectedLimited) {
      target.rejectedCounter.add(res.status === 429 ? 1 : 0, tagSet);
      if (res.status !== 429) {
        policyMismatches.add(1, tagSet);
      }
      checkStatus(res, 429, tagSet);
      check(
        res,
        {
          "429 body matches rate limit contract": (response) => {
            const body = parseJson(response);
            return (
              body !== null &&
              body.code === "RATE_LIMIT_EXCEEDED" &&
              isJson(response) &&
              isFutureIso8601(body.unlockTime)
            );
          },
        },
        tagSet
      );
    } else {
      const allowed = target.allowedStatuses.includes(res.status);
      target.allowedCounter.add(allowed ? 1 : 0, tagSet);
      if (res.status === 429 || !allowed) {
        policyMismatches.add(1, tagSet);
      }
      if (!allowed) {
        unexpectedStatus.add(1, tagSet);
      }
      checkStatus(res, target.allowedStatuses, tagSet);
      check(
        res,
        {
          "within policy matches expected validation response": (response) =>
            target.allowedStatuses.includes(response.status),
        },
        tagSet
      );
    }

    sleep(positiveNumberEnv("RATE_LIMIT_SLEEP_SECONDS", 0.05));
  }
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
