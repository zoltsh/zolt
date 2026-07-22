import { expect, smoke, type SmokeContext } from "smoque";

import { writeFakeNativeImage } from "../../../smoke/support/fake-native-image.mts";
import { listJarEntries } from "../../../smoke/support/jar.mts";
import { expectNativeExecutable } from "../../../smoke/support/native-runtime.mts";
import { writeSpringBootNativeAotProject } from "../../../smoke/support/spring-native-project.mts";
import { expectTextFile, packagedZolt, runZolt } from "../../../smoke/support/zolt-smoke.mts";

smoke.suite("Spring Boot native AOT handoff smoke", { tags: ["native", "spring-boot", "aot"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-spring-boot-native-aot");
  const project = work.path("spring-boot-native-aot-canary");
  await writeSpringBootNativeAotProject(project);
  const fake = await writeFakeNativeImage(work.path());
  const zolt = await packagedZolt(t);

  await t.step("resolves the Spring AOT tooling lane", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTextFile(work.path("spring-boot-native-aot-canary/zolt.lock"), {
      contains: ['scope = "tool-spring-aot"'],
    });
  });

  await t.step("generates AOT evidence and passes thin inputs to Native Image", async () => {
    await runZolt(t, zolt, [
      "--no-progress", "native", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--native-image", fake.command,
    ], { env: { ZOLT_FAKE_NATIVE_IMAGE_ARGS: fake.argsLog }, timeout: "5m" });
    const target = work.path("spring-boot-native-aot-canary/target");
    await expectNativeExecutable(`${target}/native/spring-boot-native-aot-canary`);
    await expectTextFile(`${target}/native/native-image.log`, { contains: ["fake native-image wrote"] });
    await expectTextFile(`${target}/native/spring-aot-evidence.json`, {
      contains: [
        '"schema": "zolt.spring-aot-evidence.v1"',
        '"freshness": "present"',
        '"fingerprint": "sha256:',
        "Main__BeanDefinitions.java",
        "Main__BeanDefinitions.class",
        "reflect-config.json",
        "resource-config.json",
      ],
    });
    await expect.file(`${target}/spring-aot/main/sources/com/example/Main__ApplicationContextInitializer.java`).toExist();
    await expect.file(`${target}/spring-aot/main/classes/com/example/Main__ApplicationContextInitializer.class`).toExist();
    await expect.file(`${target}/spring-aot/main/resources/META-INF/native-image/com.example/spring-boot-native-aot-canary/native-image.properties`).toExist();

    await expectTextFile(fake.argsLog, {
      contains: [
        `${target}/spring-boot-native-aot-canary-0.1.0.jar`,
        `${target}/spring-aot/main/classes`,
        `${target}/spring-aot/main/resources`,
        "com.example.Main",
        `${target}/native/spring-boot-native-aot-canary`,
      ],
      excludes: ["BOOT-INF"],
    });
    const entries = await listJarEntries(t, `${target}/spring-boot-native-aot-canary-0.1.0.jar`);
    expect.value(entries.some((entry) => entry.startsWith("BOOT-INF/"))).toBeFalsy();
  });
});
