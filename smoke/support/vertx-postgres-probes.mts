import { expect, type SmokeContext } from "smoque";

import { expectJsonObject, jsonNumber, jsonString } from "./json.mts";
import { jsonRequest, requestJson, requestJsonObject } from "./http-json.mts";
import type { PostgresRuntime } from "./postgres-runtime.mts";

export async function verifyVertxPostgresCrud(
  t: SmokeContext,
  baseUrl: string,
  postgres: PostgresRuntime,
): Promise<void> {
  const health = await requestJsonObject(t, baseUrl, "/health", 200);
  expect.value(jsonString(t, health.body, "status", "health response")).toBe("ok");
  const ready = await requestJsonObject(t, baseUrl, "/ready", 200);
  expect.value(jsonString(t, ready.body, "status", "readiness response")).toBe("ready");

  const unsupported = await requestJsonObject(t, baseUrl, "/notes", 415, {
    method: "POST",
    headers: { "content-type": "text/plain" },
    body: "not json",
  });
  expect.value(jsonString(t, unsupported.body, "error", "unsupported media response"))
    .toBe("content-type must be application/json");

  const created = await requestJsonObject(t, baseUrl, "/notes", 201, jsonRequest("POST", {
    title: "  first  ",
    body: "  hello  ",
  }));
  const id = jsonNumber(t, created.body, "id", "created note");
  expect.value(jsonString(t, created.body, "title", "created note")).toBe("first");
  expect.value(jsonString(t, created.body, "body", "created note")).toBe("hello");
  expect.value(created.response.headers.get("location")).toBe(`/notes/${id}`);

  const listed = await requestJson(t, baseUrl, "/notes", 200);
  if (!Array.isArray(listed.body)) {
    t.fail("GET /notes response should be a JSON array.");
  }
  const listedNote = expectJsonObject(t, listed.body[0], "first listed note");
  expect.value(jsonNumber(t, listedNote, "id", "first listed note")).toBe(id);

  const updated = await requestJsonObject(t, baseUrl, `/notes/${id}`, 200, jsonRequest("PUT", {
    title: "updated",
    body: "hello again",
  }));
  expect.value(jsonString(t, updated.body, "title", "updated note")).toBe("updated");
  const stored = await postgres.database.query(`select id, title, body from ${postgres.table}`);
  stored.expectRow({ id, title: "updated", body: "hello again" });

  const deleted = await fetch(new URL(`/notes/${id}`, baseUrl), { method: "DELETE" });
  expect.value(deleted.status).toBe(204);
  expect.value(await deleted.text()).toBe("");
  const missing = await requestJsonObject(t, baseUrl, `/notes/${id}`, 404);
  expect.value(jsonString(t, missing.body, "error", "missing note response")).toBe(`note ${id} was not found`);

  const method = await requestJsonObject(t, baseUrl, "/health", 405, { method: "POST" });
  expect.value(method.response.headers.get("allow")).toBe("GET");
}
