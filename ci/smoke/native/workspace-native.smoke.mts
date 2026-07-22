import { expect, smoke, type SmokeContext } from "smoque";

import { writeNativeWorkspace } from "../../../smoke/support/native-projects.mts";
import { expectNativeExecutable, nativeImageCommand } from "../../../smoke/support/native-runtime.mts";
import { packagedZolt, runZolt } from "../../../smoke/support/zolt-smoke.mts";

smoke.suite("workspace real Native Image smoke", { tags: ["native", "workspace", "real"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-workspace-native");
  const project = work.path("workspace-real-native");
  await writeNativeWorkspace(project);
  const zolt = await packagedZolt(t);
  const nativeImage = nativeImageCommand();

  await t.step("builds the selected app and its workspace library into a native executable", async () => {
    await t.cmd(nativeImage, ["--version"], { timeout: "30s" });
    await runZolt(t, zolt, [
      "--no-progress", "native", "--workspace", "--member", "apps/api",
      "--cwd", project, "--cache-root", zolt.cacheRoot, "--native-image", nativeImage,
    ], { timeout: "10m" });
    const binary = work.path("workspace-real-native/apps/api/target/native/workspace-api");
    await expectNativeExecutable(binary);
    await expect.file(work.path("workspace-real-native/apps/api/target/native/native-image.log")).toExist();

    const result = await t.cmd(binary, ["smoke"], { timeout: "30s" });
    expect.value(result.stdout).toContain("api:core:smoke");
  });
});
