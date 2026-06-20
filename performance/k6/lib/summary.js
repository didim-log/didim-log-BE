export function summaryHandlers(name) {
  return (data) => {
    const path = __ENV.SUMMARY_EXPORT || `performance/results/${name}-${Date.now()}.json`;
    const metrics = data.metrics || {};
    const metricValue = (metricName, valueName) => metrics[metricName]?.values?.[valueName] ?? null;
    const metricCount = (metricName) => metricValue(metricName, "count");
    const metricRate = (metricName) => metricValue(metricName, "rate");
    const trend = (metricName) => {
      const values = metrics[metricName]?.values;
      if (!values) {
        return null;
      }
      return {
        p50: values.med ?? null,
        p90: values["p(90)"] ?? null,
        p95: values["p(95)"] ?? null,
        p99: values["p(99)"] ?? null,
        max: values.max ?? null,
      };
    };

    const thresholdFailures = Object.entries(metrics)
      .flatMap(([metricName, metric]) =>
        Object.entries(metric.thresholds || {})
          .filter(([, threshold]) => threshold.ok === false)
          .map(([thresholdName]) => `${metricName}:${thresholdName}`)
      );

    const summary = {
      script: name,
      executedAt: new Date().toISOString(),
      commitSha: __ENV.COMMIT_SHA || "NOT_CAPTURED",
      gitDirty: __ENV.GIT_DIRTY || "NOT_CAPTURED",
      k6Version: __ENV.K6_VERSION || "NOT_CAPTURED",
      javaVersion: __ENV.JAVA_VERSION || "NOT_CAPTURED",
      kotlinVersion: __ENV.KOTLIN_VERSION || "1.9.25",
      jvmHeap: __ENV.JVM_HEAP || "NOT_CAPTURED",
      cpu: __ENV.CPU_INFO || "NOT_CAPTURED",
      memory: __ENV.MEMORY_INFO || "NOT_CAPTURED",
      mongoEnvironment: __ENV.MONGO_ENVIRONMENT || "local",
      redisEnvironment: __ENV.REDIS_ENVIRONMENT || "local",
      fixtureCount: __ENV.FIXTURE_COUNT || "NOT_CAPTURED",
      mockGeminiDelayMs: __ENV.MOCK_GEMINI_DELAY_MS || "NOT_CAPTURED",
      scenario: __ENV.PERF_SCENARIO || name,
      executor: __ENV.K6_EXECUTOR || "NOT_CAPTURED",
      vus: __ENV.K6_VUS || __ENV.READ_VUS || __ENV.AI_CONCURRENCY || "NOT_CAPTURED",
      iterations: metricCount("iterations"),
      duration: __ENV.K6_DURATION || __ENV.READ_DURATION || "NOT_CAPTURED",
      requests: metricCount("http_reqs"),
      rps: metricRate("http_reqs"),
      errorRate: metricRate("http_req_failed"),
      checkSuccessRate: metricRate("checks"),
      p50: metricValue("http_req_duration", "med"),
      p90: metricValue("http_req_duration", "p(90)"),
      p95: metricValue("http_req_duration", "p(95)"),
      p99: metricValue("http_req_duration", "p(99)"),
      max: metricValue("http_req_duration", "max"),
      droppedIterations: metricCount("dropped_iterations"),
      thresholdFailures,
      readWorkload: {
        dashboard: {
          requests: metricCount("read_dashboard_requests"),
          successRate: metricRate("read_dashboard_success"),
          duration: trend("read_dashboard_duration"),
        },
        statistics: {
          requests: metricCount("read_statistics_requests"),
          successRate: metricRate("read_statistics_success"),
          duration: trend("read_statistics_duration"),
        },
        retrospectiveList: {
          requests: metricCount("read_retrospective_list_requests"),
          successRate: metricRate("read_retrospective_list_success"),
          duration: trend("read_retrospective_list_duration"),
        },
        retrospectiveDetail: {
          requests: metricCount("read_retrospective_detail_requests"),
          successRate: metricRate("read_retrospective_detail_success"),
          duration: trend("read_retrospective_detail_duration"),
        },
      },
      ai: {
        aiInitialRequestCount: metricCount("ai_initial_request_count"),
        aiClassifiedResponseCount: metricCount("ai_classified_response_count"),
        aiProcessing: metricCount("ai_processing"),
        aiCached: metricCount("ai_cached"),
        aiGenerationSuccess: metricCount("ai_generation_success"),
        aiUnexpectedError: metricCount("ai_unexpected_error"),
        aiUnexpected5xx: metricCount("ai_unexpected_5xx"),
        geminiCallCount: metricValue("gemini_call_count", "value"),
        geminiCallMismatch: metricCount("gemini_call_mismatch"),
      },
      rateLimit: {
        signupAllowed: metricCount("rate_limit_signup_allowed"),
        signupRejected: metricCount("rate_limit_signup_rejected"),
        loginAllowed: metricCount("rate_limit_login_allowed"),
        loginRejected: metricCount("rate_limit_login_rejected"),
        passwordResetAllowed: metricCount("rate_limit_password_reset_allowed"),
        passwordResetRejected: metricCount("rate_limit_password_reset_rejected"),
        unexpectedStatus: metricCount("rate_limit_unexpected_status"),
        policyMismatches: metricCount("rate_limit_policy_mismatches"),
      },
      geminiActualCallCount: metricValue("gemini_call_count", "value"),
      finalAiReviewCount: __ENV.FINAL_AI_REVIEW_COUNT || "VERIFY_SCRIPT_REQUIRED",
    };

    return {
      stdout: `${name} completed. summary=${path}\n`,
      [path]: JSON.stringify({ summary, raw: data }, null, 2),
    };
  };
}
