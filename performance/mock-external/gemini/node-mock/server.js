const http = require("http");

const port = Number(process.env.WIREMOCK_PORT || "8090");
let delayMs = Number(process.env.MOCK_GEMINI_DELAY_MS || "500");
let requests = [];
let failedRetryState = "Started";

function readBody(req) {
  return new Promise((resolve) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", () => resolve(body));
  });
}

function send(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

function countGenerateContentRequests() {
  return requests.filter((item) => item.method === "POST" && item.url.includes(":generateContent")).length;
}

const success = {
  candidates: [
    {
      content: {
        parts: [
          {
            text: "Extract the repeated loop into a named helper and keep input validation close to the boundary.",
          },
        ],
      },
    },
  ],
};

const server = http.createServer(async (req, res) => {
  const body = await readBody(req);

  if (req.method === "GET" && req.url === "/__admin/health") {
    return send(res, 200, { status: "ok", mode: "node" });
  }

  if (req.method === "DELETE" && req.url === "/__admin/requests") {
    requests = [];
    return send(res, 200, { status: "reset" });
  }

  if (req.method === "POST" && req.url === "/__admin/scenarios/reset") {
    failedRetryState = "Started";
    return send(res, 200, { status: "reset" });
  }

  if (req.method === "POST" && req.url === "/__admin/settings") {
    try {
      const parsed = JSON.parse(body || "{}");
      if (Number.isInteger(parsed.fixedDelay) && parsed.fixedDelay >= 0) {
        delayMs = parsed.fixedDelay;
      }
    } catch (_) {
      // Keep the current delay when the settings payload is malformed.
    }
    return send(res, 200, { fixedDelay: delayMs });
  }

  if (req.method === "POST" && req.url === "/__admin/requests/count") {
    return send(res, 200, { count: countGenerateContentRequests() });
  }

  if (req.method === "POST" && req.url.includes("/v1beta/models/") && req.url.includes(":generateContent")) {
    requests.push({ method: req.method, url: req.url, body });
    setTimeout(() => {
      if (body.includes("FORCE_GEMINI_FAILURE_ONCE") && failedRetryState === "Started") {
        failedRetryState = "failed-once";
        return send(res, 500, {
          error: {
            code: 500,
            message: "forced local Gemini failure for retry verification",
          },
        });
      }
      return send(res, 200, success);
    }, delayMs);
    return undefined;
  }

  return send(res, 404, { error: "not found" });
});

server.listen(port, "127.0.0.1", () => {
  console.log(`local Gemini mock listening on ${port}`);
});
