import { expect, type SmokeContext } from "smoque";

import { expectJsonObject, jsonNumber, jsonString, parseJson } from "./json.mts";

async function fetchObject(
  t: SmokeContext,
  url: URL,
  init: RequestInit = {},
  expectedStatus = 200,
): Promise<Record<string, unknown>> {
  const response = await fetch(url, init);
  const text = await response.text();
  if (response.status !== expectedStatus) {
    t.fail(`${init.method ?? "GET"} ${url.pathname} expected ${expectedStatus}, got ${response.status}: ${text}`);
  }
  return expectJsonObject(t, parseJson(t, text, `${init.method ?? "GET"} ${url.pathname}`), url.pathname);
}

export async function verifySpringBootNativeRuntime(t: SmokeContext, baseUrl: URL): Promise<void> {
  const hello = await fetchObject(t, new URL("/hello", baseUrl));
  expect.value(jsonString(t, hello, "message", "hello response")).toBe("hello from native Spring Boot");

  const health = await fetchObject(t, new URL("/actuator/health", baseUrl));
  expect.value(jsonString(t, health, "status", "Actuator health response")).toBe("UP");

  const echoed = await fetchObject(t, new URL("/echo", baseUrl), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ message: "native validation" }),
  });
  expect.value(jsonString(t, echoed, "message", "echo response")).toBe("native validation");

  const invalid = await fetch(new URL("/echo", baseUrl), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ message: "" }),
  });
  expect.value(invalid.status).toBe(400);

  const created = await fetchObject(t, new URL("/data-notes", baseUrl), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ text: "  native data  " }),
  });
  expect.value(jsonNumber(t, created, "id", "created data note")).toBe(1);
  expect.value(jsonString(t, created, "text", "created data note")).toBe("native data");

  const read = await fetchObject(t, new URL("/data-notes/1", baseUrl));
  expect.value(jsonString(t, read, "text", "read data note")).toBe("native data");
  await fetchObject(t, new URL("/data-notes/99", baseUrl), {}, 404);
}
