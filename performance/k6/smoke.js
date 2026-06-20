import http from "k6/http";
import { check } from "k6";
import { BASE_URL, assertSafeEnvironment, tags, validateConfiguredEnv } from "./lib/environment.js";
import { authHeaders, getAuthToken } from "./lib/auth.js";
import { checkJsonFields, checkStatus } from "./lib/checks.js";
import { commonThresholds, summaryTrendStats } from "./config.js";
import { summaryHandlers } from "./lib/summary.js";

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }));

export const options = {
  scenarios: {
    smoke: {
      executor: "shared-iterations",
      vus: 1,
      iterations: 1,
      maxDuration: "30s",
    },
  },
  thresholds: commonThresholds(),
  summaryTrendStats,
};

export function setup() {
  assertSafeEnvironment({ allowRemoteBaseUrl: false });
  validateConfiguredEnv();
}

export default function () {
  const token = getAuthToken();

  const systemTags = tags("system_status", "smoke", "success", "ANONYMOUS");
  const system = http.get(`${BASE_URL}/api/v1/system/status`, { tags: systemTags });
  checkStatus(system, 200, systemTags);
  checkJsonFields(system, ["underMaintenance"], systemTags);

  const dashboardTags = tags("dashboard", "smoke", "success");
  const dashboard = http.get(`${BASE_URL}/api/v1/dashboard`, {
    headers: authHeaders(token),
    tags: dashboardTags,
  });
  checkStatus(dashboard, 200, dashboardTags);
  checkJsonFields(dashboard, ["studentProfile.bojId", "todaySolvedCount"], dashboardTags);

  const statisticsTags = tags("statistics", "smoke", "success");
  const statistics = http.get(`${BASE_URL}/api/v1/statistics`, {
    headers: authHeaders(token),
    tags: statisticsTags,
  });
  checkStatus(statistics, 200, statisticsTags);
  checkJsonFields(statistics, ["monthlyHeatmap", "totalRetrospectives", "successRate"], statisticsTags);

  const listTags = tags("log_list", "smoke", "success");
  const list = http.get(`${BASE_URL}/api/v1/retrospectives?page=1&size=5`, {
    headers: authHeaders(token),
    tags: listTags,
  });
  checkStatus(list, 200, listTags);
  checkJsonFields(list, ["content", "totalElements", "currentPage"], listTags);

  check(
    list,
    {
      "retrospective list is isolated to owner": (response) => {
        const body = response.json();
        return Array.isArray(body.content) && body.content.every((item) => item.isOwner === true);
      },
    },
    listTags
  );
}

export const handleSummary = summaryHandlers("smoke");
