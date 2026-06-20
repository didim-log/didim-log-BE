import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { authHeaders, getAuthToken } from "./lib/auth.js";
import {
  BASE_URL,
  PERF_BOJ_ID,
  assertSafeEnvironment,
  listEnv,
  pickWeighted,
  positiveIntegerEnv,
  positiveNumberEnv,
  tags,
  validateConfiguredEnv,
} from "./lib/environment.js";
import { checkJsonFields, checkStatus, parseJson } from "./lib/checks.js";
import { defaultReadOptions } from "./config.js";
import { summaryHandlers } from "./lib/summary.js";

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }));

export const options = defaultReadOptions;

const endpointDuration = new Trend("didimlog_endpoint_duration", true);
const dashboardDuration = new Trend("read_dashboard_duration", true);
const statisticsDuration = new Trend("read_statistics_duration", true);
const logListDuration = new Trend("read_retrospective_list_duration", true);
const logDetailDuration = new Trend("read_retrospective_detail_duration", true);
const dashboardRequests = new Counter("read_dashboard_requests");
const statisticsRequests = new Counter("read_statistics_requests");
const logListRequests = new Counter("read_retrospective_list_requests");
const logDetailRequests = new Counter("read_retrospective_detail_requests");
const dashboardSuccess = new Rate("read_dashboard_success");
const statisticsSuccess = new Rate("read_statistics_success");
const logListSuccess = new Rate("read_retrospective_list_success");
const logDetailSuccess = new Rate("read_retrospective_detail_success");

const endpointMetrics = {
  dashboard: { duration: dashboardDuration, requests: dashboardRequests, success: dashboardSuccess },
  statistics: { duration: statisticsDuration, requests: statisticsRequests, success: statisticsSuccess },
  log_list: { duration: logListDuration, requests: logListRequests, success: logListSuccess },
  log_detail: { duration: logDetailDuration, requests: logDetailRequests, success: logDetailSuccess },
};

export function setup() {
  assertSafeEnvironment({ allowRemoteBaseUrl: false });
  validateConfiguredEnv();
  const token = getAuthToken();
  const configuredIds = listEnv("READ_RETROSPECTIVE_IDS", []);
  if (configuredIds.length > 0) {
    return { token, detailIds: configuredIds };
  }

  const res = http.get(`${BASE_URL}/api/v1/retrospectives?page=1&size=100`, {
    headers: authHeaders(token),
    tags: tags("log_list", "setup", "success"),
  });

  if (res.status !== 200) {
    throw new Error(`Failed to load retrospective IDs for detail workload. status=${res.status}`);
  }

  const body = res.json();
  const ids = Array.isArray(body.content) ? body.content.map((item) => item.id).filter(Boolean) : [];
  if (ids.length === 0) {
    throw new Error("No retrospective IDs found. Seed performance fixtures before running read-workload.js.");
  }
  return { token, detailIds: ids };
}

export default function (data) {
  const endpoints = [
    {
      name: "dashboard",
      weight: positiveIntegerEnv("READ_DASHBOARD_WEIGHT", 30),
      run: () => requestDashboard(data.token),
    },
    {
      name: "statistics",
      weight: positiveIntegerEnv("READ_STATISTICS_WEIGHT", 25),
      run: () => requestStatistics(data.token),
    },
    {
      name: "log_list",
      weight: positiveIntegerEnv("READ_LOG_LIST_WEIGHT", 30),
      run: () => requestLogList(data.token),
    },
    {
      name: "log_detail",
      weight: positiveIntegerEnv("READ_LOG_DETAIL_WEIGHT", 15),
      run: () => requestLogDetail(data.token, data.detailIds),
    },
  ];

  pickWeighted(endpoints).run();
  sleep(positiveNumberEnv("READ_SLEEP_SECONDS", 0.1));
}

function requestDashboard(token) {
  const tagSet = tags("dashboard", "read_workload", "profile");
  const res = http.get(`${BASE_URL}/api/v1/dashboard`, {
    headers: authHeaders(token),
    tags: tagSet,
  });
  const ok =
    checkStatus(res, 200, tagSet) &&
    checkJsonFields(res, ["studentProfile.bojId", "todaySolvedCount", "currentRating"], tagSet) &&
    check(
    res,
    {
      "dashboard belongs to test user": (response) => response.json().studentProfile.bojId === PERF_BOJ_ID,
    },
    tagSet
  );
  recordEndpointMetrics("dashboard", res, ok, tagSet);
}

function requestStatistics(token) {
  const tagSet = tags("statistics", "read_workload", "aggregate");
  const res = http.get(`${BASE_URL}/api/v1/statistics`, {
    headers: authHeaders(token),
    tags: tagSet,
  });
  const ok =
    checkStatus(res, 200, tagSet) &&
    checkJsonFields(res, ["monthlyHeatmap", "totalSolved", "totalRetrospectives", "categoryStats"], tagSet);
  recordEndpointMetrics("statistics", res, ok, tagSet);
}

function requestLogList(token) {
  const page = 1 + Math.floor(Math.random() * positiveIntegerEnv("READ_MAX_PAGE", 5));
  const size = positiveIntegerEnv("READ_PAGE_SIZE", 10, 1, 100);
  const tagSet = tags("log_list", "read_workload", "page");
  const res = http.get(`${BASE_URL}/api/v1/retrospectives?page=${page}&size=${size}`, {
    headers: authHeaders(token),
    tags: tagSet,
  });
  const ok =
    checkStatus(res, 200, tagSet) &&
    checkJsonFields(res, ["content", "totalElements", "totalPages", "currentPage"], tagSet) &&
    check(
    res,
    {
      "list items are owner-scoped": (response) => {
        const body = parseJson(response);
        return body !== null && Array.isArray(body.content) && body.content.every((item) => item.isOwner === true);
      },
    },
    tagSet
  );
  recordEndpointMetrics("log_list", res, ok, tagSet);
}

function requestLogDetail(token, ids) {
  const id = ids[Math.floor(Math.random() * ids.length)];
  const tagSet = tags("log_detail", "read_workload", "detail");
  const res = http.get(`${BASE_URL}/api/v1/retrospectives/${id}`, {
    headers: authHeaders(token),
    tags: tagSet,
  });
  const ok =
    checkStatus(res, 200, tagSet) &&
    checkJsonFields(res, ["id", "studentId", "isOwner", "problemId", "content"], tagSet) &&
    check(
    res,
    {
      "detail is owner-scoped": (response) => response.json().isOwner === true,
    },
    tagSet
  );
  recordEndpointMetrics("log_detail", res, ok, tagSet);
}

function recordEndpointMetrics(name, res, ok, tagSet) {
  const metrics = endpointMetrics[name];
  endpointDuration.add(res.timings.duration, tagSet);
  metrics.duration.add(res.timings.duration, tagSet);
  metrics.requests.add(1, tagSet);
  metrics.success.add(Boolean(ok), tagSet);
}

export const handleSummary = summaryHandlers("read-workload");
