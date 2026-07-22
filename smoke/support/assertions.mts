import { expect, type SmokeContext } from "smoque";
import { readFile } from "node:fs/promises";

import { runZolt, type ZoltRuntime } from "./zolt-runtime.mts";

export interface TextFileContract {
  readonly contains?: readonly string[];
  readonly excludes?: readonly string[];
}

export interface LockfilePackageContract {
  readonly contains?: readonly string[];
  readonly excludes?: readonly string[];
}

export async function expectTextFile(path: string, contract: TextFileContract): Promise<void> {
  const content = await readFile(path, "utf8");
  for (const expected of contract.contains ?? []) {
    expect.value(content).toContain(expected);
  }
  for (const excluded of contract.excludes ?? []) {
    if (content.includes(excluded)) {
      throw new Error(`Expected ${path} not to contain ${JSON.stringify(excluded)}.`);
    }
  }
}

export async function expectLockfilePackages(path: string, contract: LockfilePackageContract): Promise<void> {
  const packageIds = new Set((await readFile(path, "utf8"))
    .split("[[package]]")
    .slice(1)
    .map((block) => /^\s*id = "([^"]+)"/u.exec(block)?.[1])
    .filter((id): id is string => id !== undefined));
  for (const expected of contract.contains ?? []) {
    if (!packageIds.has(expected)) {
      throw new Error(`Expected ${path} to resolve package ${JSON.stringify(expected)}.`);
    }
  }
  for (const excluded of contract.excludes ?? []) {
    if (packageIds.has(excluded)) {
      throw new Error(`Expected ${path} not to resolve package ${JSON.stringify(excluded)}.`);
    }
  }
}

export async function expectTestsFound(
  t: SmokeContext,
  zolt: ZoltRuntime,
  expected: number,
  args: string[],
): Promise<void> {
  const result = await runZolt(t, zolt, args);
  const output = `${result.stdout}\n${result.stderr}`;
  expect.value(output).toMatch(new RegExp(`(Tests found: ${expected}|${expected} tests found)`, "u"));
  expect.value(output).toMatch(/(Tests succeeded:|Tests passed)/u);
}

export async function expectCommandFailureContains(
  t: SmokeContext,
  zolt: ZoltRuntime,
  args: string[],
  expected: string,
): Promise<void> {
  const result = await runZolt(t, zolt, args, { check: false });
  if (result.exitCode === 0) {
    t.fail(`Expected command to fail: ${[zolt.command, ...args].join(" ")}`);
  }
  expect.value(`${result.stdout}\n${result.stderr}`).toContain(expected);
}
