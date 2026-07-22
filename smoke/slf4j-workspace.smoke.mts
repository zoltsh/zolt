import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { expectJarManifest } from "./support/jar.mts";
import {
  copyFixture,
  expectLockfilePackages,
  expectTestsFound,
  expectTextFile,
  packagedZolt,
  runZolt,
} from "./support/zolt-smoke.mts";

smoke.suite("SLF4J workspace canary smoke", { tags: ["libraries", "workspace"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-slf4j-workspace");
  const zolt = await packagedZolt(t);

  await t.step("preserves exported workspace edges and private dependencies", async () => {
    const workspace = await copyFixture(root, work, "slf4j-canary");
    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--workspace", "--cwd", workspace, "--cache-root", zolt.cacheRoot,
    ]);
    await expectTextFile(join(workspace, "zolt.lock"), {
      contains: [
        "org.slf4j:slf4j-api",
        'workspace = "slf4j-api"',
        'members = ["slf4j-simple"]',
        "org.apache.logging.log4j:log4j-to-slf4j",
      ],
    });
    await expectLockfilePackages(join(workspace, "zolt.lock"), {
      contains: ["org.apache.logging.log4j:log4j-to-slf4j"],
      excludes: ["org.apache.logging.log4j:log4j-api"],
    });

    const model = await runZolt(t, zolt, [
      "--no-progress", "ide", "model", "--workspace", "--format", "json",
      "--cwd", workspace, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(model.stdout).toContain('"from": "slf4j-simple"');
    expect.value(model.stdout).toContain('"to": "slf4j-api"');
    expect.value(model.stdout).toContain('"exported": true');
    expect.value(model.stdout).toContain('"Bundle-SymbolicName": "org.slf4j.simple"');
    expect.value(model.stdout).toContain('"coordinate": "org.apache.logging.log4j:log4j-to-slf4j"');
  });

  await t.step("builds, tests, and packages every library artifact", async () => {
    const workspace = await copyFixture(root, work, "slf4j-canary", "slf4j-packaging");
    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--workspace", "--cwd", workspace, "--cache-root", zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, [
      "--no-progress", "build", "--workspace", "--member", "slf4j-simple",
      "--cwd", workspace, "--cache-root", zolt.cacheRoot,
    ]);
    await expect.file(join(workspace, "slf4j-api/target/classes/org/slf4j/Logger.class")).toExist();
    await expect.file(join(workspace, "slf4j-simple/target/classes/org/slf4j/simple/SimpleLoggerFactory.class")).toExist();
    await expectTestsFound(t, zolt, 3, [
      "--no-progress", "test", "--workspace", "--member", "slf4j-simple",
      "--cwd", workspace, "--cache-root", zolt.cacheRoot,
      "--test", "org.slf4j.simple.SimpleLoggerFactoryTest",
      "--jvm-arg", "-Dslf4j.canary.mode=workspace",
    ]);
    await runZolt(t, zolt, [
      "--no-progress", "package", "--workspace", "--all",
      "--cwd", workspace, "--cache-root", zolt.cacheRoot,
    ]);

    const apiTarget = join(workspace, "slf4j-api/target");
    const simpleTarget = join(workspace, "slf4j-simple/target");
    const apiJar = join(apiTarget, "slf4j-api-0.1.0.jar");
    const simpleJar = join(simpleTarget, "slf4j-simple-0.1.0.jar");
    await expect.archive(apiJar).toContainEntries(["org/slf4j/Logger.class"]);
    await expect.archive(join(apiTarget, "slf4j-api-0.1.0-sources.jar")).toContainEntries(["org/slf4j/Logger.java"]);
    await expect.archive(simpleJar).toContainEntries(["org/slf4j/simple/SimpleLoggerFactory.class"]);
    await expect.archive(join(simpleTarget, "slf4j-simple-0.1.0-tests.jar")).toContainEntries([
      "org/slf4j/simple/SimpleLoggerFactoryTest.class",
    ]);
    await expectJarManifest(t, apiJar, work.path("slf4j-api-manifest"), [
      "Automatic-Module-Name: org.slf4j",
    ]);
    await expectJarManifest(t, simpleJar, work.path("slf4j-simple-manifest"), [
      "Bundle-SymbolicName: org.slf4j.simple",
      "Import-Package: org.slf4j",
    ]);
  });
});
