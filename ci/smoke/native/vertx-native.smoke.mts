import { smoke, type SmokeContext } from "smoque";

import { expectHttpResponses } from "../../../smoke/support/http-process.mts";
import { addVertxNativeConfig } from "../../../smoke/support/native-projects.mts";
import {
  expectNativeExecutable,
  nativeImageCommand,
  startNativeHttpProcess,
} from "../../../smoke/support/native-runtime.mts";
import { copyFixture, packagedZolt, runZolt } from "../../../smoke/support/zolt-smoke.mts";

smoke.suite("Vert.x real Native Image smoke", { tags: ["native", "vertx", "http", "real"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-vertx-native");
  const project = await copyFixture(root, work, "vertx-http");
  await addVertxNativeConfig(project);
  const zolt = await packagedZolt(t);
  const nativeImage = nativeImageCommand();
  const port = await t.ports.reserve("vertx-native");

  await t.step("builds and serves the Vert.x application as a native executable", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, [
      "--no-progress", "native", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--native-image", nativeImage,
    ], { timeout: "10m" });
    const binary = work.path("vertx-http/target/native/vertx-http");
    await expectNativeExecutable(binary);

    const running = await startNativeHttpProcess(
      t, binary, "vertx-native", [`--port=${port.port}`], port.port,
    );
    await expectHttpResponses(t, port.url(), [{ path: "/hello", body: "Hello from Vert.x via Zolt!" }]);
    await running.stop();
  });
});
