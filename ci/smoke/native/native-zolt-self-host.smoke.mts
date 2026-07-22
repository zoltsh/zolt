import { expect, smoke, type SmokeContext } from "smoque";

import { writeFakeNativeImage } from "../../../smoke/support/fake-native-image.mts";
import { buildNativeZolt } from "../../../smoke/support/native-zolt.mts";
import { writeSpringBootNativeAotProject } from "../../../smoke/support/spring-native-project.mts";
import { copyFixture, expectTextFile, packagedZolt, runZolt } from "../../../smoke/support/zolt-smoke.mts";

smoke.suite("native Zolt self-host smoke", { tags: ["native", "self-host", "cli", "real"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-native-self-host");
  const bootstrap = await packagedZolt(t);
  let zolt = bootstrap;

  await t.step("builds the current Zolt CLI with real Native Image", async () => {
    zolt = await buildNativeZolt(t, bootstrap);
    const version = await runZolt(t, zolt, ["--version"], { timeout: "30s" });
    expect.value(version.stdout).toContain("0.1.0");
  });

  await t.step("runs a packaged Java lifecycle through native Zolt", async () => {
    const project = await copyFixture(root, work, "hello-zolt");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    const result = await runZolt(t, zolt, [
      "--no-progress", "run-package", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(result.stdout).toContain("Hello from Zolt\n");
  });

  await t.step("drives the Spring AOT handoff through native Zolt", async () => {
    const project = work.path("native-zolt-spring-aot");
    await writeSpringBootNativeAotProject(project);
    const fake = await writeFakeNativeImage(work.path("native-zolt-fake"));
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, [
      "--no-progress", "native", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--native-image", fake.command,
    ], { env: { ZOLT_FAKE_NATIVE_IMAGE_ARGS: fake.argsLog }, timeout: "5m" });
    await expectTextFile(work.path("native-zolt-spring-aot/target/native/spring-aot-evidence.json"), {
      contains: ['"schema": "zolt.spring-aot-evidence.v1"', '"freshness": "present"'],
    });
  });
});
