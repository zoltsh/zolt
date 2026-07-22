import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import {
  copyFixture,
  expectTestsFound,
  expectTextFile,
  packagedZolt,
  runZolt,
  singleJar,
} from "./support/zolt-smoke.mts";

smoke.suite("exec build step smoke", { tags: ["examples", "exec"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-exec-steps");
  const zolt = await packagedZolt(t);

  await t.step("runs a project-classpath generator and packages its resource", async () => {
    const project = await copyFixture(root, work, "exec-jvm-canary");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTestsFound(t, zolt, 1, [
      "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTextFile(join(project, "target/generated/resources/build-info/build-info.properties"), {
      contains: ["canary.version=1.4.2", "canary.generator=exec-project"],
    });

    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    const jar = await singleJar(join(project, "target"));
    await expect.archive(jar).toContainEntries(["build-info.properties"]);
    const result = await runZolt(t, zolt, [
      "--no-progress", "run-package", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(result.stdout).toMatch(/exec-jvm-canary version 1\.4\.2 \(exec-project\)/u);
  });

  await t.step("chains process generators into compiled sources and packaged resources", async () => {
    const project = await copyFixture(root, work, "exec-process-canary");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTestsFound(t, zolt, 1, [
      "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expect.file(
      join(project, "target/generated/sources/greeting/sh/zolt/canary/execprocess/generated/Greeting.java"),
    ).toExist();
    await expectTextFile(join(project, "target/generated/exec/stage/staged.txt"), {
      contains: ["Hello from exec-process-canary"],
    });
    await expectTextFile(join(project, "target/generated/exec/resource/exec-canary.properties"), {
      contains: ["canary.message=Hello from exec-process-canary", "canary.source=exec-process"],
    });

    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    const jar = await singleJar(join(project, "target"));
    await expect.archive(jar).toContainEntries([
      "exec-canary.properties",
      "sh/zolt/canary/execprocess/generated/Greeting.class",
    ]);
    await expect.archive(jar).not.toContainEntries(["staged.txt"]);
    const result = await runZolt(t, zolt, [
      "--no-progress", "run-package", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(result.stdout).toMatch(/Hello from exec-process-canary :: exec-process/u);
  });
});
