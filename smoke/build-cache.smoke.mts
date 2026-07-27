import { expect, smoke, type SmokeContext } from "smoque";
import { rm } from "node:fs/promises";
import { join } from "node:path";

import { startUploadFileServer } from "./support/upload-server.mts";
import { isolatedUserGlobalHome } from "./support/user-global-config.mts";
import { copyFixture, expectOutputExcludes, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

const MAIN_CLASS = "target/classes/com/example/Main.class";
const CACHE_USERNAME_ENV = "ZOLT_SMOKE_BUILD_CACHE_USERNAME";
const CACHE_TOKEN_ENV = "ZOLT_SMOKE_BUILD_CACHE_TOKEN";

smoke.suite("build output cache smoke", { tags: ["build-cache", "enterprise"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-build-cache");
  const zolt = await packagedZolt(t);

  await t.step("reports a disabled cache until the user-global config opts in", async () => {
    const home = await isolatedUserGlobalHome(t, work, "disabled-home");
    const status = await runZolt(t, zolt, ["--no-progress", "cache", "status"], { env: home.env });
    expect.value(status.stdout).toContain("build cache: disabled");
    expect.value(status.stdout).toContain("[buildCache]");
  });

  await t.step("serves wiped build output from the local cache", async () => {
    const home = await isolatedUserGlobalHome(t, work, "local-home");
    const cacheDir = join(home.path, "build-cache");
    await home.write([
      "version = 1",
      "",
      "[buildCache]",
      "enabled = true",
      `dir = "${cacheDir}"`,
      "maxSizeMb = 64",
    ]);

    const project = await copyFixture(root, work, "hello-zolt", "local-cache-app");
    const buildArgs = ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot];
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot], {
      env: home.env,
    });

    const cold = await runZolt(t, zolt, buildArgs, { env: home.env });
    expect.value(cold.stdout).toMatch(/Compiled \d+ main source files/u);
    expectOutputExcludes(t, cold.stdout, ["Restored"], "Cold build output");

    await rm(join(project, "target"), { recursive: true, force: true });
    const warm = await runZolt(t, zolt, buildArgs, { env: home.env });
    expect.value(warm.stdout).toMatch(/Restored \d+ main classes/u);
    expect.value(warm.stdout).toContain("build cache");
    await expect.file(join(project, MAIN_CLASS)).toExist();

    await rm(join(project, "target"), { recursive: true, force: true });
    const bypassed = await runZolt(t, zolt, [...buildArgs, "--no-build-cache"], { env: home.env });
    expect.value(bypassed.stdout).toMatch(/Compiled \d+ main source files/u);
    expectOutputExcludes(t, bypassed.stdout, ["Restored"], "Bypassed build output");

    const status = await runZolt(t, zolt, ["--no-progress", "cache", "status"], { env: home.env });
    expect.value(status.stdout).toContain("build cache: enabled");
    expect.value(status.stdout).toContain(`directory: ${cacheDir}`);
    expect.value(status.stdout).toMatch(/entries: [1-9]\d*/u);
    expect.value(status.stdout).toMatch(/size: \d/u);

    const prune = await runZolt(t, zolt, ["--no-progress", "cache", "prune"], { env: home.env });
    expect.value(prune.stdout).toMatch(/Pruned \d+ build cache entries/u);
    expect.value(prune.stdout).toMatch(/remaining: \d/u);
  });

  await t.step("pushes to the remote tier and rehydrates a second checkout", async () => {
    const credentials = { username: "cache-user", password: "cache-token" } as const;
    t.redact(credentials.password);
    const server = await startUploadFileServer(t, work.path("remote-cache"), credentials);

    const home = await isolatedUserGlobalHome(t, work, "remote-home");
    const cacheDir = join(home.path, "build-cache");
    await home.write([
      "version = 1",
      "",
      "[buildCache]",
      "enabled = true",
      `dir = "${cacheDir}"`,
      "",
      "[buildCache.remote]",
      `url = "${server.url()}"`,
      'credentials = "smoke-cache"',
      "push = true",
      "",
      "[repositoryCredentials.smoke-cache]",
      `usernameEnv = "${CACHE_USERNAME_ENV}"`,
      `passwordEnv = "${CACHE_TOKEN_ENV}"`,
    ]);
    const env = {
      ...home.env,
      [CACHE_USERNAME_ENV]: credentials.username,
      [CACHE_TOKEN_ENV]: credentials.password,
    };

    const producer = await copyFixture(root, work, "hello-zolt", "remote-cache-producer");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", producer, "--cache-root", zolt.cacheRoot], { env });
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", producer, "--cache-root", zolt.cacheRoot], { env });
    const uploads = server.requests.filter((request) => request.startsWith("PUT "));
    if (uploads.length === 0) {
      t.fail(`Expected the build to push cache objects, saw: ${server.requests.join(", ")}`);
    }
    expect.value(uploads.join("\n")).toContain(".zbc");

    await rm(cacheDir, { recursive: true, force: true });
    const consumer = await copyFixture(root, work, "hello-zolt", "remote-cache-consumer");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", consumer, "--cache-root", zolt.cacheRoot], { env });
    const warm = await runZolt(t, zolt, [
      "--no-progress", "build", "--cwd", consumer, "--cache-root", zolt.cacheRoot,
    ], { env });

    expect.value(warm.stdout).toMatch(/Restored \d+ main classes/u);
    await expect.file(join(consumer, MAIN_CLASS)).toExist();
    expect.value(server.requests.filter((request) => request.startsWith("GET ")).join("\n")).toContain(".zbc");
  });
});
