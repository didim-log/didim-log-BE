import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Gauge } from "k6/metrics";
import { authHeaders, getAuthToken } from "./lib/auth.js";
import {
  BASE_URL,
  WIREMOCK_URL,
  assertSafeEnvironment,
  durationEnv,
  env,
  nonNegativeIntegerEnv,
  positiveIntegerEnv,
  tags,
  validateConfiguredEnv,
} from "./lib/environment.js";
import { checkStatus, parseJson } from "./lib/checks.js";
import { commonThresholds, summaryTrendStats } from "./config.js";
import { summaryHandlers } from "./lib/summary.js";

http.setResponseCallback(http.expectedStatuses(200, 201, 202, { min: 500, max: 599 }));

const aiInitialRequestCount = new Counter("ai_initial_request_count");
const aiClassifiedResponseCount = new Counter("ai_classified_response_count");
const aiGenerationSuccess = new Counter("ai_generation_success");
const aiProcessing = new Counter("ai_processing");
const aiCached = new Counter("ai_cached");
const aiUnexpected5xx = new Counter("ai_unexpected_5xx");
const aiUnexpectedError = new Counter("ai_unexpected_error");
const geminiCallCount = new Gauge("gemini_call_count");
const geminiCallMismatch = new Counter("gemini_call_mismatch");

const concurrency = positiveIntegerEnv("AI_CONCURRENCY", 50, 1, 500);
const experiment = env("AI_EXPERIMENT", "concurrency");

const concurrencyOptions = {
  scenarios: {
    ai_concurrency: {
      executor: "per-vu-iterations",
      vus: concurrency,
      iterations: 1,
      maxDuration: durationEnv("AI_MAX_DURATION", "45s"),
      gracefulStop: "5s",
    },
  },
  thresholds: commonThresholds({
    ai_initial_request_count: [`count==${concurrency}`],
    ai_classified_response_count: [`count==${concurrency}`],
    ai_unexpected_5xx: ["count==0"],
    ai_unexpected_error: ["count==0"],
    gemini_call_mismatch: ["count==0"],
  }),
  summaryTrendStats,
};

const failedFirstOptions = {
  scenarios: {
    failed_first: {
      executor: "shared-iterations",
      exec: "failedFirstAttempt",
      vus: 1,
      iterations: 1,
      maxDuration: durationEnv("AI_MAX_DURATION", "45s"),
    },
  },
  thresholds: commonThresholds({
    ai_initial_request_count: ["count==1"],
    ai_classified_response_count: ["count==1"],
    ai_unexpected_5xx: ["count==0"],
    ai_unexpected_error: ["count==0"],
    gemini_call_mismatch: ["count==0"],
  }),
  summaryTrendStats,
};

const failedSecondOptions = {
  scenarios: {
    failed_second: {
      executor: "shared-iterations",
      exec: "failedSecondAttempt",
      vus: 1,
      iterations: 1,
      maxDuration: durationEnv("AI_MAX_DURATION", "45s"),
    },
  },
  thresholds: commonThresholds({
    ai_initial_request_count: ["count==1"],
    ai_classified_response_count: ["count==1"],
    ai_unexpected_5xx: ["count==0"],
    ai_unexpected_error: ["count==0"],
    gemini_call_mismatch: ["count==0"],
  }),
  summaryTrendStats,
};

const failedFinalOptions = {
  scenarios: {
    failed_final: {
      executor: "shared-iterations",
      exec: "failedFinalCached",
      vus: 1,
      iterations: 1,
      maxDuration: durationEnv("AI_MAX_DURATION", "45s"),
    },
  },
  thresholds: commonThresholds({
    ai_initial_request_count: ["count==1"],
    ai_classified_response_count: ["count==1"],
    ai_unexpected_5xx: ["count==0"],
    ai_unexpected_error: ["count==0"],
    gemini_call_mismatch: ["count==0"],
  }),
  summaryTrendStats,
};

export const options =
  experiment === "failed-first"
    ? failedFirstOptions
    : experiment === "failed-second"
      ? failedSecondOptions
      : experiment === "failed-final"
        ? failedFinalOptions
        : concurrencyOptions;

export function setup() {
  assertSafeEnvironment({ allowRemoteBaseUrl: false });
  validateConfiguredEnv();

  const token = getAuthToken();
  if (experiment === "concurrency" || experiment === "failed-first") {
    resetWireMockJournal();
    resetWireMockScenarios();
    configureWireMockDelay();
  }
  const runId = env("AI_RUN_ID", `${Date.now()}`);
  const configuredLogId = env("AI_LOG_ID");
  if ((experiment === "failed-second" || experiment === "failed-final") && !configuredLogId) {
    throw new Error("AI_LOG_ID is required for failed-second and failed-final experiments.");
  }
  const logId =
    configuredLogId ||
    (experiment === "failed-first" ? createFailedRetryLog(token, runId) : createAiReviewLog(token, runId));
  return {
    token,
    logId,
    runId,
    startAt: Date.now() + nonNegativeIntegerEnv("AI_SYNC_WAIT_MS", 3000, 30000),
  };
}

export default function (data) {
  const waitMs = data.startAt - Date.now();
  if (waitMs > 0) {
    sleep(waitMs / 1000);
  }

  sendAiReviewRequest(data, "ai_concurrency");
}

export function failedFirstAttempt(data) {
  sendAiReviewRequest(data, "ai_failed_retry_first");
}

export function failedSecondAttempt(data) {
  sendAiReviewRequest(data, "ai_failed_retry_second");
}

export function failedFinalCached(data) {
  sendAiReviewRequest(data, "ai_failed_retry_final");
}

function createAiReviewLog(token, runId) {
  const tagSet = tags("log_create", "ai_setup", "success");
  const payload = JSON.stringify({
    title: `k6-ai-review-${runId}`,
    content: "k6 AI review concurrency fixture",
    code: [
      `// k6 unique AI fixture: ${runId}`,
      "public class Main {",
      "  public static void main(String[] args) {",
      "    int sum = 0;",
      "    for (int i = 0; i < 100; i++) { sum += i; }",
      "    System.out.println(sum);",
      "  }",
      "}",
    ].join("\n"),
    isSuccess: true,
  });
  const res = http.post(`${BASE_URL}/api/v1/logs`, payload, {
    headers: authHeaders(token),
    tags: tagSet,
  });
  if (res.status !== 201) {
    throw new Error(`Failed to create AI review log. status=${res.status} body=${res.body}`);
  }
  return res.json().id;
}

function createFailedRetryLog(token, runId) {
  const tagSet = tags("log_create", "ai_failed_retry_setup", "success");
  const payload = JSON.stringify({
    title: `k6-ai-review-${runId}`,
    content: "k6 AI review failed retry fixture",
    code: [
      `// k6 unique failed-retry fixture: ${runId}`,
      "public class Main {",
      "  static final String MARKER = \"FORCE_GEMINI_FAILURE_ONCE\";",
      "  public static void main(String[] args) {",
      "    System.out.println(MARKER.length());",
      "  }",
      "}",
    ].join("\n"),
    isSuccess: false,
  });
  const res = http.post(`${BASE_URL}/api/v1/logs`, payload, {
    headers: authHeaders(token),
    tags: tagSet,
  });
  if (res.status !== 201) {
    throw new Error(`Failed to create failed-retry log. status=${res.status} body=${res.body}`);
  }
  return res.json().id;
}

function sendAiReviewRequest(data, scenario) {
  const initialTags = tags("ai_review", scenario, "initial_request");
  aiInitialRequestCount.add(1, initialTags);
  const res = http.post(`${BASE_URL}/api/v1/logs/${data.logId}/ai-review`, null, {
    headers: authHeaders(data.token),
    tags: initialTags,
  });
  classifyAiReviewResponse(res, scenario);
}

function classifyAiReviewResponse(res, scenario) {
  const body = parseJson(res);
  const resultType = classifyResultType(res, body);
  const tagSet = tags("ai_review", scenario, resultType);
  aiClassifiedResponseCount.add(1, tagSet);
  checkStatus(res, [200, 202], tagSet);

  if (res.status >= 500) {
    aiUnexpected5xx.add(1, tagSet);
    return;
  }
  if (res.status === 202 && body?.inProgress === true && body?.cached === false) {
    aiProcessing.add(1, tagSet);
    return;
  }
  if (res.status === 200 && body?.cached === true && body?.inProgress === false && hasReview(body)) {
    aiCached.add(1, tagSet);
    return;
  }
  if (res.status === 200 && body?.cached === false && body?.inProgress === false && hasReview(body)) {
    aiGenerationSuccess.add(1, tagSet);
    return;
  }
  aiUnexpectedError.add(1, tagSet);
}

function classifyResultType(res, body) {
  if (res.status >= 500) {
    return "unexpected_5xx";
  }
  if (res.status === 202) {
    return body?.inProgress === true && body?.cached === false ? "accepted_in_progress" : "unexpected_body";
  }
  if (res.status === 200 && body?.cached === true && body?.inProgress === false && hasReview(body)) {
    return "cached_result";
  }
  if (res.status === 200 && body?.cached === false && body?.inProgress === false && hasReview(body)) {
    return "generated_result";
  }
  if (res.status === 200) {
    return "unexpected_body";
  }
  return "unexpected_status";
}

function hasReview(body) {
  return typeof body?.review === "string" && body.review.trim().length > 0;
}

function resetWireMockJournal() {
  http.del(`${WIREMOCK_URL}/__admin/requests`, null, {
    tags: tags("gemini_mock", "setup", "reset", "MOCK"),
  });
}

function resetWireMockScenarios() {
  http.post(`${WIREMOCK_URL}/__admin/scenarios/reset`, null, {
    tags: tags("gemini_mock", "setup", "reset_scenarios", "MOCK"),
  });
}

function configureWireMockDelay() {
  const delay = nonNegativeIntegerEnv("MOCK_GEMINI_DELAY_MS", 500, 30000);
  http.post(
    `${WIREMOCK_URL}/__admin/settings`,
    JSON.stringify({ fixedDelay: delay }),
    {
      headers: { "Content-Type": "application/json" },
      tags: tags("gemini_mock", "setup", "delay", "MOCK"),
    }
  );
}

function getGeminiRequestCount() {
  const res = http.post(
    `${WIREMOCK_URL}/__admin/requests/count`,
    JSON.stringify({
      method: "POST",
      urlPathPattern: "/v1beta/models/.*:generateContent",
    }),
    {
      headers: { "Content-Type": "application/json" },
      tags: tags("gemini_mock", "ai_concurrency", "count", "MOCK"),
    }
  );
  if (res.status !== 200) {
    geminiCallMismatch.add(1, tags("gemini_mock", "ai_concurrency", "count_failed", "MOCK"));
    return -1;
  }
  return Number(res.json().count);
}

export const handleSummary = summaryHandlers("ai-review-concurrency");
