import { smoke, type SmokeContext } from "smoque";

import {
  expectCleanNativeImageLog,
  expectNativeExecutable,
  nativeImageCommand,
  startNativeHttpProcess,
} from "../../../smoke/support/native-runtime.mts";
import { writeSpringBootNativeWebProject } from "../../../smoke/support/spring-native-web-project.mts";
import { verifySpringBootNativeRuntime } from "../../../smoke/support/spring-native-probes.mts";
import { packagedZolt, runZolt } from "../../../smoke/support/zolt-smoke.mts";

smoke.suite("Spring Boot real Native Image smoke", {
  tags: ["native", "spring-boot", "actuator", "validation", "http", "real"],
}, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-spring-boot-real-native");
  const project = work.path("spring-boot-native-web");
  await writeSpringBootNativeWebProject(project);
  const zolt = await packagedZolt(t);
  const nativeImage = nativeImageCommand();
  const port = await t.ports.reserve("spring-boot-native");

  await t.step("builds Spring AOT output with real Native Image", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, [
      "--no-progress", "native", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--native-image", nativeImage,
    ], { timeout: "10m" });
    await expectNativeExecutable(work.path("spring-boot-native-web/target/native/spring-boot-native-web"));
    await expectCleanNativeImageLog(t, work.path("spring-boot-native-web/target/native/native-image.log"));
  });

  await t.step("serves web, Actuator, and validation contracts from the native executable", async () => {
    const binary = work.path("spring-boot-native-web/target/native/spring-boot-native-web");
    const running = await startNativeHttpProcess(
      t, binary, "spring-boot-native", [`--server.port=${port.port}`], port.port,
    );
    try {
      await verifySpringBootNativeRuntime(t, port.url());
    } finally {
      await running.stop();
    }
  });
});
