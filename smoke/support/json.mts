import type { SmokeContext } from "smoque";

export type JsonObject = Record<string, unknown>;

export function parseJson(t: SmokeContext, text: string, label: string): unknown {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    t.fail(`${label} should be valid JSON: ${String(error)}\n${text}`);
  }
  return parsed;
}

export function parseJsonObject(t: SmokeContext, text: string, label: string): JsonObject {
  return expectJsonObject(t, parseJson(t, text, label), label);
}

export function expectJsonObject(t: SmokeContext, value: unknown, label: string): JsonObject {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    t.fail(`${label} should be a JSON object.`);
  }
  return value as JsonObject;
}

export function jsonString(t: SmokeContext, object: JsonObject, key: string, label: string): string {
  const value = object[key];
  if (typeof value !== "string") {
    t.fail(`${label}.${key} should be a string.`);
  }
  return value;
}

export function jsonNumber(t: SmokeContext, object: JsonObject, key: string, label: string): number {
  const value = object[key];
  if (typeof value !== "number" || !Number.isFinite(value)) {
    t.fail(`${label}.${key} should be a finite number.`);
  }
  return value;
}

export function jsonArray(t: SmokeContext, object: JsonObject, key: string, label: string): readonly unknown[] {
  const value = object[key];
  if (!Array.isArray(value)) {
    t.fail(`${label}.${key} should be an array.`);
  }
  return value;
}

export function findJsonObjectByString(
  t: SmokeContext,
  values: readonly unknown[],
  key: string,
  expected: string,
  label: string,
): JsonObject {
  for (const [index, value] of values.entries()) {
    const object = expectJsonObject(t, value, `${label}[${index}]`);
    if (object[key] === expected) {
      return object;
    }
  }
  t.fail(`${label} should contain an object whose ${key} is ${JSON.stringify(expected)}.`);
}
