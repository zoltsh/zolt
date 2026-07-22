import { expect, smoke, type SmokeContext } from "smoque";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { copyFixture, expectTestsFound, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("Spring Boot executable jar smoke", { tags: ["examples", "spring-boot"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-spring-boot");
  const zolt = await packagedZolt(t);

  await t.step("tests, packages generated resources, and starts the executable jar", async () => {
    const project = await copyFixture(root, work, "spring-boot-webmvc");
    const generatedDirectory = join(project, "target/generated/resources/static");
    await mkdir(generatedDirectory, { recursive: true });
    await writeFile(join(generatedDirectory, "generated.css"), "body { color: #123456; }\n", "utf8");

    await expectTestsFound(t, zolt, 1, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, [
      "--no-progress",
      "package",
      "--mode",
      "spring-boot",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
    ]);

    const jar = await singleJar(join(project, "target"));
    await expect.archive(jar).toContainEntries([
      "BOOT-INF/classes/META-INF/build-info.properties",
      "BOOT-INF/classes/static/generated.css",
    ]);

    const java = await t.tools.java({ minVersion: 21 });
    const result = await t.cmd(java.command, [
      "-jar",
      jar,
      "--spring.main.web-application-type=none",
    ], { cwd: project, timeout: "1m" });
    expect.value(`${result.stdout}\n${result.stderr}`).toContain("Started DemoApplication");
  });
});
