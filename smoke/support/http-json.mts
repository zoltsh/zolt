import { expect, type SmokeContext } from "smoque";

import { expectJsonObject, parseJson, type JsonObject } from "./json.mts";

export interface HttpJsonResponse {
  readonly body: unknown;
  readonly response: Response;
}

export async function requestJson(
  t: SmokeContext,
  baseUrl: string,
  path: string,
  expectedStatus: number,
  init: RequestInit = {},
): Promise<HttpJsonResponse> {
  const response = await fetch(new URL(path, baseUrl), init);
  expect.value(response.status).toBe(expectedStatus);
  expect.value(response.headers.get("content-type") ?? "").toContain("application/json");
  expect.value(response.headers.get("cache-control")).toBe("no-store");
  const text = await response.text();
  return { body: parseJson(t, text, `${init.method ?? "GET"} ${path} response`), response };
}

export async function requestJsonObject(
  t: SmokeContext,
  baseUrl: string,
  path: string,
  expectedStatus: number,
  init: RequestInit = {},
): Promise<{ readonly body: JsonObject; readonly response: Response }> {
  const result = await requestJson(t, baseUrl, path, expectedStatus, init);
  return {
    body: expectJsonObject(t, result.body, `${init.method ?? "GET"} ${path} response`),
    response: result.response,
  };
}

export function jsonRequest(method: "POST" | "PUT", body: JsonObject): RequestInit {
  return {
    method,
    headers: { "content-type": "application/json; charset=utf-8" },
    body: JSON.stringify(body),
  };
}
