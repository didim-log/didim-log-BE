import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Gauge } from "k6/metrics";
import { authHeaders, getAuthToken } from "./lib/auth.js";
import { BASE_URL, WIREMOCK_URL, env, numberEnv, tags } from "./lib/environment.js";
import { checkStatus, parseJson } from "./lib/checks.js";
import { commonThresholds, summaryTrendStats } from "./config.js";
import { summaryHandlers } from "./lib/summary.js";

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409, 429, { min: 500, max: 599 }));

const aiGenerationSuccess = new Counter("ai_generation_success");
const aiProcessing = new Counter("ai_processing");
const aiCached = new Counter("ai_cached");
const aiConflict = new Counter("ai_conflict");
const aiUnexpected5xx = new Counter("ai_unexpected_5xx");
const aiUnexpectedError = new Counter("ai_unexpected_error");
const geminiCallCount = new Gauge("gemini_call_count");
const geminiCallMismatch = new Counter("gemini_call_mismatch");

const concurrency = numberEnv("AI_CONCURRENCY", 50);
const verifyDelaySeconds = numberEnv("AI_VERIFY_DELAY_SECONDS", 8);
const experiment = env("AI_EXPERIMENT", "concurrency");

const concurrencyOptions = {
  scenarios: {
    ai_concurrency: {
      executor: "shared-iterations",
      vus: concurrency,
      iterations: concurrency,
      maxDuration: env("AI_MAX_DURATION", "45s"),
      gracefulStop: "5s",
    },
    verify_gemini: {
      executor: "shared-iterations",
      exec: "verifyGeminiAndCache",
      vus: 1,
      iterations: 1,
      startTime: `${verifyDelaySeconds}s`,
      maxDuration: "30s",
    },
  },
  thresholds: commonThresholds({
    ai_unexpected_5xx: ["count==0"],
    gemini_call_mismatch: ["count==0"],
  }),
  summaryTrendStats,
};

const failedRetryOptions = {
  scenarios: {
    failed_retry: {
      executor: "shared-iterations",
      exec: "failedRetry",
      vus: 1,
      iterations: 1,
      maxDuration: "45s",
    },
  },
  thresholds: commonThresholds({
    ai_unexpected_5xx: ["count==0"],
    gemini_call_mismatch: ["count==0"],
  }),
  summaryTrendStats,
};

export const options = experiment === "failed-retry" ? failedRetryOptions : concurrencyOptions;

export function setup() {
  const token = getAuthToken();
  resetWireMockJournal();
  resetWireMockScenarios();
  configureWireMockDelay();
  const runId = env("AI_RUN_ID", `${Date.now()}`);
  const configuredLogId = env("AI_LOG_ID");
  const logId =
    configuredLogId ||
    (experiment === "failed-retry" ? createFailedRetryLog(token, runId) : createAiReviewLog(token, runId));
  return {
    token,
    logId,
    runId,
    startAt: Date.now() + numberEnv("AI_SYNC_WAIT_MS", 3000),
  };
}

export default function (data) {
  const waitMs = data.startAt - Date.now();
  if (waitMs > 0) {
    sleep(waitMs / 1000);
  }

  const tagSet = tags("ai_review", "ai_concurrency", "pending");
  const res = http.post(`${BASE_URL}/api/v1/logs/${data.logId}/ai-review`, null, {
    headers: authHeaders(data.token),
    tags: tagSet,
  });

  classifyAiReviewResponse(res);
}

export function verifyGeminiAndCache(data) {
  const count = getGeminiRequestCount();
  geminiCallCount.add(count, tags("gemini_mock", "ai_concurrency", "verify", "MOCK"));
  if (count !== numberEnv("EXPECTED_GEMINI_CALLS", 1)) {
    geminiCallMismatch.add(1, tags("gemini_mock", "ai_concurrency", "mismatch", "MOCK"));
  }

  const tagSet = tags("ai_review", "ai_concurrency", "post_verify");
  const res = http.post(`${BASE_URL}/api/v1/logs/${data.logId}/ai-review`, null, {
    headers: authHeaders(data.token),
    tags: tagSet,
  });
  checkStatus(res, 200, tagSet);
  check(
    res,
    {
      "post concurrency request returns cached review": (response) => {
        const body = parseJson(response);
        return body !== null && body.cached === true && body.inProgress === false;
      },
    },
    tagSet
  );
}

export function failedRetry(data) {
  const firstTags = tags("ai_review_failed_retry", "ai_failed_retry", "first_attempt");
  const first = http.post(`${BASE_URL}/api/v1/logs/${data.logId}/ai-review`, null, {
    headers: authHeaders(data.token),
    tags: firstTags,
  });
  checkStatus(first, 202, firstTags);

  sleep(numberEnv("AI_FAILED_RETRY_WAIT_SECONDS", 5));

  const secondTags = tags("ai_review_failed_retry", "ai_failed_retry", "retry_after_failed");
  const second = http.post(`${BASE_URL}/api/v1/logs/${data.logId}/ai-review`, null, {
    headers: authHeaders(data.token),
    tags: secondTags,
  });
  checkStatus(second, 202, secondTags);

  sleep(numberEnv("AI_FAILED_RETRY_VERIFY_WAIT_SECONDS", 4));

  const finalTags = tags("ai_review_failed_retry", "ai_failed_retry", "existing_result");
  const final = http.post(`${BASE_URL}/api/v1/logs/${data.logId}/ai-review`, null, {
    headers: authHeaders(data.token),
    tags: finalTags,
  });
  checkStatus(final, 200, finalTags);
  check(
    final,
    {
      "failed retry eventually returns cached review": (response) => {
        const body = parseJson(response);
        return body !== null && body.cached === true && body.inProgress === false;
      },
    },
    finalTags
  );

  const count = getGeminiRequestCount();
  geminiCallCount.add(count, tags("gemini_mock", "ai_failed_retry", "verify", "MOCK"));
  if (count !== numberEnv("EXPECTED_GEMINI_CALLS", 2)) {
    geminiCallMismatch.add(1, tags("gemini_mock", "ai_failed_retry", "mismatch", "MOCK"));
  }
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

function classifyAiReviewResponse(res) {
  const body = parseJson(res);
  const resultType = classifyResultType(res, body);
  const tagSet = tags("ai_review", "ai_concurrency", resultType);
  checkStatus(res, [200, 202, 409], tagSet);

  if (res.status >= 500) {
    aiUnexpected5xx.add(1, tagSet);
    return;
  }
  if (res.status === 409) {
    aiConflict.add(1, tagSet);
    return;
  }
  if (res.status === 202 && body?.inProgress === true) {
    aiProcessing.add(1, tagSet);
    return;
  }
  if (res.status === 200 && body?.cached === true) {
    aiCached.add(1, tagSet);
    return;
  }
  if (res.status === 200 && body?.cached === false && body?.inProgress === false) {
    aiGenerationSuccess.add(1, tagSet);
    return;
  }
  aiUnexpectedError.add(1, tagSet);
}

function classifyResultType(res, body) {
  if (res.status >= 500) {
    return "abnormal_5xx";
  }
  if (res.status === 409) {
    return "expected_conflict";
  }
  if (res.status === 202 && body?.inProgress === true) {
    return "already_processing";
  }
  if (res.status === 200 && body?.cached === true) {
    return "existing_result";
  }
  if (res.status === 200) {
    return "ai_generation_success";
  }
  return "abnormal_error";
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
  const delay = numberEnv("MOCK_GEMINI_DELAY_MS", 500);
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
