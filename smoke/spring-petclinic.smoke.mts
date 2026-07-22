import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { expectHttpResponses } from "./support/http-process.mts";
import { PETCLINIC_ARCHIVE_ENTRIES, writePetclinicGeneratedResources } from "./support/petclinic-resources.mts";
import { copyFixture, expectTestsFound, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("Spring Boot PetClinic smoke", { tags: ["framework", "spring-boot", "http"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-spring-petclinic");
  const zolt = await packagedZolt(t);
  const port = await t.ports.reserve("spring-petclinic");
  const project = await copyFixture(root, work, "spring-boot-petclinic-lite");
  await writePetclinicGeneratedResources(project);

  await t.step("tests, starts without a server, and packages application resources", async () => {
    await expectTestsFound(t, zolt, 1, [
      "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const nonWeb = await runZolt(t, zolt, [
      "--no-progress", "run", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--", "--spring.main.web-application-type=none",
    ], { timeout: "1m" });
    expect.value(`${nonWeb.stdout}\n${nonWeb.stderr}`).toContain("Started PetClinicLiteApplication");

    await runZolt(t, zolt, [
      "--no-progress", "package", "--mode", "spring-boot", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const jar = await singleJar(join(project, "target"));
    await expect.archive(jar).toContainEntries([...PETCLINIC_ARCHIVE_ENTRIES]);
    const jarTool = await t.tools.jar({ minVersion: 8 });
    const entries = await t.cmd(jarTool.command, ["tf", jar], { cwd: project });
    expect.value(entries.stdout).toMatch(/BOOT-INF\/lib\/h2-[^/]+\.jar/u);
  });

  await t.step("serves seeded data, templates, static assets, and generated resources", async () => {
    const jar = await singleJar(join(project, "target"));
    const java = await t.tools.java({ minVersion: 21 });
    const running = await t.process.start(java.command, ["-jar", jar, `--server.port=${port.port}`], {
      name: "spring-petclinic-http",
      cwd: project,
      ready: t.tcp.ready(port.port),
      timeout: "1m",
    });
    await expectHttpResponses(t, port.url(), [
      { path: "/", contains: ["PetClinic Lite", "Ada Lovelace", "Grace Hopper"] },
      { path: "/app.css", contains: ["system-ui"] },
      { path: "/generated/assets/css/theme.css", contains: ["petclinic-generated"] },
      { path: "/webjars/petclinic-lite/0.1.0/petclinic-lite.css", contains: ["webjar-petclinic-lite"] },
    ]);
    await running.stop();
  });
});
