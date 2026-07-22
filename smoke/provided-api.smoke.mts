import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { copyFixture, expectTextFile, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("provided container API smoke", { tags: ["packaging", "provided"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-provided-api");
  const zolt = await packagedZolt(t);

  await t.step("compiles against provided APIs without packaging them", async () => {
    const project = await copyFixture(root, work, "provided-container-api");
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);

    const jar = await singleJar(join(project, "target"));
    const runtimeClasspath = jar.replace(/\.jar$/u, ".runtime-classpath");
    await expectTextFile(runtimeClasspath, { excludes: ["jakarta.servlet-api"] });
    await expect.archive(jar).not.toContainEntries(["jakarta/servlet/Servlet.class"]);

    const result = await runZolt(t, zolt, [
      "--no-progress", "run-package", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(result.stdout).toContain("provided-container-api fixture ran");
  });
});
