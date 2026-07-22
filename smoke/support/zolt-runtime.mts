import { expect, type CommandOptions, type CommandResult, type PathRef, type SmokeContext } from "smoque";

import { singleJar } from "./artifacts.mts";

const CACHE_ROOT = ".zolt/cache";
const CLI_MEMBER = "apps/zolt";
const JUNIT_WORKER_MEMBER = "apps/zolt-junit-worker";
const PREBUILT_ENV = "ZOLT_SMOKE_PREBUILT";

export interface ZoltRuntime {
  readonly cacheRoot: string;
  readonly command: string;
  readonly env: Readonly<Record<string, string>>;
  readonly root: PathRef;
}

let packagedRuntime: Promise<ZoltRuntime> | undefined;

export async function packagedZolt(t: SmokeContext): Promise<ZoltRuntime> {
  const cached = packagedRuntime;
  if (cached) {
    return await t.step("reuse packaged JUnit worker and CLI", async () => await cached);
  }

  const usePrebuilt = process.env[PREBUILT_ENV] === "1";
  packagedRuntime = t.step(usePrebuilt ? "use prebuilt JUnit worker and CLI" : "package JUnit worker and CLI", async () => {
    await t.tools.node({ minVersion: 22 });
    await t.tools.npm({ minVersion: 10 });
    if (usePrebuilt) {
      return await usePackagedZolt(t, t.repoRoot());
    }
    return await buildPackagedZolt(t, t.repoRoot());
  });
  return await packagedRuntime;
}

export async function runZolt(
  t: SmokeContext,
  zolt: ZoltRuntime,
  args: string[],
  options: CommandOptions = {},
): Promise<CommandResult> {
  const { env, timeout, ...rest } = options;
  return await t.cmd(zolt.command, args, {
    cwd: zolt.root,
    env: {
      ...zolt.env,
      ...env,
    },
    timeout: timeout ?? "3m",
    ...rest,
  });
}

async function buildPackagedZolt(t: SmokeContext, root: PathRef): Promise<ZoltRuntime> {
  const cacheRoot = root.path(CACHE_ROOT);

  await t.cmd(script(root, "bootstrap-zolt-jvm"), [
    "--no-progress",
    "package",
    "--workspace",
    "--member",
    JUNIT_WORKER_MEMBER,
    "--cache-root",
    cacheRoot,
  ], { cwd: root, timeout: "10m" });
  await t.cmd(script(root, "bootstrap-zolt-jvm"), [
    "--no-progress",
    "package",
    "--workspace",
    "--member",
    CLI_MEMBER,
    "--cache-root",
    cacheRoot,
  ], { cwd: root, timeout: "10m" });

  return await usePackagedZolt(t, root);
}

async function usePackagedZolt(t: SmokeContext, root: PathRef): Promise<ZoltRuntime> {
  const cacheRoot = root.path(CACHE_ROOT);
  const jar = await singleJar(root.path(CLI_MEMBER, "target"));
  const workerJar = await singleJar(root.path(JUNIT_WORKER_MEMBER, "target"));
  const runtimeClasspathFile = jar.replace(/\.jar$/u, ".runtime-classpath");
  await expect.file(runtimeClasspathFile).toExist();

  return {
    cacheRoot,
    command: script(root, "run-zolt-built"),
    env: {
      ZOLT_JAR: jar,
      ZOLT_RUNTIME_CLASSPATH_FILE: runtimeClasspathFile,
      ZOLT_JUNIT_WORKER_JAR: workerJar,
    },
    root,
  };
}

function script(root: PathRef, name: string): string {
  return root.path("scripts", name);
}
