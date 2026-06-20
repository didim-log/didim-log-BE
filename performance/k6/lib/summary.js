export function summaryHandlers(name) {
  return (data) => {
    const path = __ENV.SUMMARY_EXPORT || `performance/results/${name}-${Date.now()}.json`;
    const metrics = data.metrics || {};
    const summary = {
      script: name,
      commitSha: __ENV.COMMIT_SHA || "NOT_CAPTURED",
      javaVersion: __ENV.JAVA_VERSION || "NOT_CAPTURED",
      kotlinVersion: __ENV.KOTLIN_VERSION || "1.9.25",
      jvmHeap: __ENV.JVM_HEAP || "NOT_CAPTURED",
      cpu: __ENV.CPU_INFO || "NOT_CAPTURED",
      memory: __ENV.MEMORY_INFO || "NOT_CAPTURED",
      mongoEnvironment: __ENV.MONGO_ENVIRONMENT || "local",
      redisEnvironment: __ENV.REDIS_ENVIRONMENT || "local",
      fixtureCount: __ENV.FIXTURE_COUNT || "NOT_CAPTURED",
      mockGeminiDelayMs: __ENV.MOCK_GEMINI_DELAY_MS || "NOT_CAPTURED",
      vus: __ENV.K6_VUS || __ENV.READ_VUS || __ENV.AI_CONCURRENCY || "NOT_CAPTURED",
      duration: __ENV.K6_DURATION || __ENV.READ_DURATION || "NOT_CAPTURED",
      requests: metrics.http_reqs?.values?.count ?? "NOT_CAPTURED",
      rps: metrics.http_reqs?.values?.rate ?? "NOT_CAPTURED",
      errorRate: metrics.http_req_failed?.values?.rate ?? "NOT_CAPTURED",
      p50: metrics.http_req_duration?.values?.["p(50)"] ?? "NOT_CAPTURED",
      p90: metrics.http_req_duration?.values?.["p(90)"] ?? "NOT_CAPTURED",
      p95: metrics.http_req_duration?.values?.["p(95)"] ?? "NOT_CAPTURED",
      p99: metrics.http_req_duration?.values?.["p(99)"] ?? "NOT_CAPTURED",
      geminiActualCallCount: metrics.gemini_call_count?.values?.value ?? "NOT_CAPTURED",
      finalAiReviewCount: __ENV.FINAL_AI_REVIEW_COUNT || "VERIFY_SCRIPT_REQUIRED",
    };

    return {
      stdout: `${name} completed. summary=${path}\n`,
      [path]: JSON.stringify({ summary, raw: data }, null, 2),
    };
  };
}
