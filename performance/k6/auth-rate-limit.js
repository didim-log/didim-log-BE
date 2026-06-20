import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";
import { BASE_URL, env, numberEnv, tags, uniqueRateLimitIp } from "./lib/environment.js";
import { checkStatus, parseJson } from "./lib/checks.js";
import { summaryHandlers } from "./lib/summary.js";
import { summaryTrendStats } from "./config.js";

http.setResponseCallback(http.expectedStatuses(400, 404, 429));

const allowedResponses = new Counter("rate_limit_allowed_responses");
const limitedResponses = new Counter("rate_limit_429_responses");
const policyMismatches = new Counter("rate_limit_policy_mismatches");

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
    rate_limit_policy_mismatches: ["count==0"],
  },
  summaryTrendStats,
};

export default function () {
  exercisePolicy({
    name: "signup",
    endpoint: "/api/v1/auth/signup",
    maxRequests: numberEnv("RATE_LIMIT_SIGNUP_MAX", 5),
    payload: (suffix) => ({
      bojId: "",
      password: "short",
      email: `signup-${suffix}@example.test`,
    }),
  });

  exercisePolicy({
    name: "login",
    endpoint: "/api/v1/auth/login",
    maxRequests: numberEnv("RATE_LIMIT_LOGIN_MAX", 10),
    payload: () => ({
      bojId: "",
      password: "short",
    }),
  });

  exercisePolicy({
    name: "password_reset",
    endpoint: "/api/v1/auth/reset-password",
    maxRequests: numberEnv("RATE_LIMIT_PASSWORD_RESET_MAX", 3),
    payload: (suffix) => ({
      resetCode: `NO${suffix}`.slice(0, 8),
      newPassword: "short",
    }),
  });
}

function exercisePolicy(target) {
  const overLimitRequests = numberEnv("RATE_LIMIT_OVERAGE_REQUESTS", 2);
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
      limitedResponses.add(res.status === 429 ? 1 : 0, tagSet);
      if (res.status !== 429) {
        policyMismatches.add(1, tagSet);
      }
      checkStatus(res, 429, tagSet);
      check(
        res,
        {
          "429 includes retry unlock time": (response) => {
            const body = parseJson(response);
            return body !== null && body.code === "RATE_LIMIT_EXCEEDED" && typeof body.unlockTime === "string";
          },
        },
        tagSet
      );
    } else {
      allowedResponses.add(res.status !== 429 ? 1 : 0, tagSet);
      if (res.status === 429) {
        policyMismatches.add(1, tagSet);
      }
      check(
        res,
        {
          "within policy is not 429": (response) => response.status !== 429,
        },
        tagSet
      );
    }

    sleep(numberEnv("RATE_LIMIT_SLEEP_SECONDS", 0.05));
  }
}

export const handleSummary = summaryHandlers("auth-rate-limit");
