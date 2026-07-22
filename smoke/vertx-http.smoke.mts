import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { sha256File } from "./support/artifacts.mts";
import { expectHttpResponses, startZoltHttpProcess } from "./support/http-process.mts";
import { copyFixture, expectTestsFound, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("Vert.x HTTP smoke", { tags: ["framework", "vertx", "http"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-vertx-http");
  const zolt = await packagedZolt(t);
  const port = await t.ports.reserve("vertx-http");
  const project = await copyFixture(root, work, "vertx-http");

  await t.step("tests and serves the application through zolt run", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTestsFound(t, zolt, 1, [
      "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const running = await startZoltHttpProcess(t, zolt, "vertx-zolt-run", [
      "--no-progress", "run", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--", `--port=${port.port}`,
    ], port.port);
    await expectHttpResponses(t, port.url(), [{ path: "/hello", body: "Hello from Vert.x via Zolt!" }]);
    await running.stop();
  });

  for (const mode of ["thin", "uber"] as const) {
    await t.step(`${mode} packaging is deterministic and runnable`, async () => {
      const packageArgs = [
        "--no-progress", "package", "--mode", mode, "--cwd", project, "--cache-root", zolt.cacheRoot,
      ];
      await runZolt(t, zolt, packageArgs);
      const jar = join(project, "target/vertx-http-0.1.0.jar");
      const firstChecksum = await sha256File(jar);
      await runZolt(t, zolt, packageArgs);
      expect.value(await sha256File(jar)).toBe(firstChecksum);

      const running = await startZoltHttpProcess(t, zolt, `vertx-${mode}-package`, [
        "--no-progress", "run-package", "--mode", mode, "--cwd", project, "--cache-root", zolt.cacheRoot,
        "--", `--port=${port.port}`,
      ], port.port);
      await expectHttpResponses(t, port.url(), [{ path: "/hello", body: "Hello from Vert.x via Zolt!" }]);
      await running.stop();
    });
  }
});
