import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { expectHttpResponses, startZoltHttpProcess } from "./support/http-process.mts";
import { configureQuarkusHttpPort, QUARKUS_HTTP_RESPONSES } from "./support/quarkus-fixture.mts";
import { copyFixture, expectTestsFound, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("Quarkus HTTP smoke", { tags: ["framework", "quarkus", "http"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-quarkus-http");
  const zolt = await packagedZolt(t);
  const port = await t.ports.reserve("quarkus-http");
  const project = await copyFixture(root, work, "quarkus-http");
  await configureQuarkusHttpPort(project, port.port);

  await t.step("augments, tests, and exports current IDE state", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expect.file(join(project, "target/quarkus-app/quarkus-run.jar")).toExist();

    await expectTestsFound(t, zolt, 1, [
      "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    await expect.file(join(project, "target/quarkus/test-application-model.dat")).toExist();
    await expect.file(join(project, "target/quarkus/zolt-test-bootstrap.properties")).toContain("runnerMode=plain-junit");
    await expect.file(join(project, "target/quarkus/zolt-test-bootstrap.properties")).toContain("supportsQuarkusTestAnnotations=true");
    await expect.file(join(project, "target/quarkus/test-runtime-classpath.txt")).toExist();

    const model = await runZolt(t, zolt, [
      "--no-progress", "ide", "model", "--format", "json", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(model.stdout).toContain('"augmentationStatus": "current"');
    expect.value(model.stdout).toContain('"kind": "runner-jar"');
    expect.value(model.stdout).toContain('"kind": "generated-bytecode-jar"');
    expect.value(model.stdout).toContain('"kind": "transformed-bytecode-jar"');
  });

  await t.step("serves resources through zolt run and the packaged runner", async () => {
    const running = await startZoltHttpProcess(t, zolt, "quarkus-zolt-run", [
      "--no-progress", "run", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ], port.port);
    await expectHttpResponses(t, port.url(), QUARKUS_HTTP_RESPONSES);
    await running.stop();

    await runZolt(t, zolt, [
      "--no-progress", "package", "--mode", "quarkus", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const java = await t.tools.java({ minVersion: 21 });
    const packaged = await t.process.start(java.command, ["-jar", join(project, "target/quarkus-app/quarkus-run.jar")], {
      name: "quarkus-packaged-runner",
      cwd: project,
      ready: t.tcp.ready(port.port),
      timeout: "1m",
    });
    await expectHttpResponses(t, port.url(), QUARKUS_HTTP_RESPONSES);
    await packaged.stop();
  });
});
