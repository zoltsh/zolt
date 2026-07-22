import { expect, type SmokeContext } from "smoque";

import { pathExists } from "./artifacts.mts";
import {
  writeFrameworkNativeDiagnosticProject,
  type NativeDiagnosticFramework,
} from "./framework-native-projects.mts";
import { runZolt, type ZoltRuntime } from "./zolt-runtime.mts";

export interface FrameworkNativeDiagnosticContract {
  readonly framework: NativeDiagnosticFramework;
  readonly mode: string;
  readonly nextStep: string;
}

export const FRAMEWORK_NATIVE_DIAGNOSTIC_CONTRACTS: readonly FrameworkNativeDiagnosticContract[] = [
  {
    framework: "spring-boot",
    mode: "Spring Boot native images require `[framework.springBoot.native] enabled = true`",
    nextStep: "zolt package --mode spring-boot",
  },
  {
    framework: "micronaut",
    mode: "Micronaut native images are not supported",
    nextStep: "zolt package --mode thin",
  },
  {
    framework: "quarkus",
    mode: "Quarkus native images are not supported",
    nextStep: "zolt package --mode quarkus",
  },
];

export async function verifyFrameworkNativeDiagnostic(
  t: SmokeContext,
  zolt: ZoltRuntime,
  workPath: (relativePath: string) => string,
  contract: FrameworkNativeDiagnosticContract,
): Promise<void> {
  const project = workPath(contract.framework);
  await writeFrameworkNativeDiagnosticProject(project, contract.framework);
  const result = await runZolt(t, zolt, [
    "--no-progress", "native", "--cwd", project, "--cache-root", zolt.cacheRoot,
  ], { check: false });
  expect.value(result.exitCode === 0).toBeFalsy();
  const output = `${result.stdout}\n${result.stderr}`;
  expect.value(output).toContain(contract.mode);
  expect.value(output).toContain(contract.nextStep);
  expect.value(output.includes("Could not run native-image")).toBeFalsy();
  expect.value(await pathExists(workPath(`${contract.framework}/target/native/native-image.log`))).toBeFalsy();
}
