import { constants } from "node:fs";
import { access, readFile } from "node:fs/promises";

import { expect, type ProcessHandle, type SmokeContext } from "smoque";

export function nativeImageCommand(): string {
  return process.env.ZOLT_SMOKE_NATIVE_IMAGE?.trim() || "native-image";
}

export async function expectNativeExecutable(path: string): Promise<void> {
  await expect.file(path).toExist();
  await access(path, constants.X_OK);
}

export async function expectCleanNativeImageLog(t: SmokeContext, path: string): Promise<void> {
  await expect.file(path).toExist();
  const log = await readFile(path, "utf8");
  const serious = log.split(/\r?\n/u).filter((line) => /(^|\s)(warning:|error:|unsupported(?:\s|:|$))/iu.test(line));
  if (serious.length > 0) {
    t.fail(`Native Image log contains serious diagnostics:\n${serious.slice(0, 20).join("\n")}`);
  }
}

export async function startNativeHttpProcess(
  t: SmokeContext,
  binary: string,
  name: string,
  args: string[],
  port: number,
  env: Readonly<Record<string, string | undefined>> = {},
): Promise<ProcessHandle> {
  return await t.process.start(binary, args, {
    name,
    env: { ...env },
    ready: t.tcp.ready(port),
    timeout: "1m",
  });
}
