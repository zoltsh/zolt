import { expect, type ProcessHandle, type SmokeContext } from "smoque";

import type { ZoltRuntime } from "./zolt-runtime.mts";

interface ExactHttpExpectation {
  readonly body: string;
  readonly contains?: never;
  readonly path: string;
}

interface PartialHttpExpectation {
  readonly body?: never;
  readonly contains: readonly string[];
  readonly path: string;
}

export type HttpExpectation = ExactHttpExpectation | PartialHttpExpectation;

export async function startZoltHttpProcess(
  t: SmokeContext,
  zolt: ZoltRuntime,
  name: string,
  args: string[],
  port: number,
  env: Readonly<Record<string, string | undefined>> = {},
): Promise<ProcessHandle> {
  return await t.process.start(zolt.command, args, {
    name,
    cwd: zolt.root,
    env: { ...zolt.env, ...env },
    ready: t.tcp.ready(port),
    timeout: "1m",
  });
}

export async function expectHttpResponses(
  t: SmokeContext,
  baseUrl: string,
  expectations: readonly HttpExpectation[],
): Promise<void> {
  for (const expectation of expectations) {
    await t.poll(`${expectation.path} HTTP response`, async () => {
      const response = await fetch(new URL(expectation.path, baseUrl));
      expect.value(response.status).toBe(200);
      const body = await response.text();
      if (expectation.body !== undefined) {
        expect.value(body).toBe(expectation.body);
      } else {
        for (const expected of expectation.contains) {
          expect.value(body).toContain(expected);
        }
      }
    }, { timeout: "10s", interval: "100ms" });
  }
}
