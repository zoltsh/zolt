import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { startZoltHttpProcess } from "../../../smoke/support/http-process.mts";
import { startEphemeralPostgres } from "../../../smoke/support/postgres-runtime.mts";
import { copyFixture, expectTestsFound, packagedZolt, runZolt } from "../../../smoke/support/zolt-smoke.mts";
import { verifyVertxPostgresCrud } from "../../../smoke/support/vertx-postgres-probes.mts";

smoke.suite("Vert.x PostgreSQL container smoke", { tags: ["container", "postgres", "vertx"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-vertx-postgres");
  const project = await copyFixture(root, work, "vertx-postgres-crud");
  const zolt = await packagedZolt(t);
  const port = await t.ports.reserve("vertx-postgres");
  const postgres = await startEphemeralPostgres(t, {
    image: "postgres:16-alpine",
    database: "zolt_vertx",
    user: "zolt",
    password: "smoke_pg_password",
    table: "zolt_notes_smoke",
  });

  await t.step("tests and serves CRUD through zolt run", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTestsFound(t, zolt, 25, [
      "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const running = await startZoltHttpProcess(t, zolt, "vertx-postgres-run", [
      "--no-progress", "run", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--", `--port=${port.port}`,
    ], port.port, postgres.env);
    await verifyVertxPostgresCrud(t, port.url(), postgres);
    await running.stop();
  });

  await t.step("packages and serves the same database contract", async () => {
    await postgres.database.sql(`delete from ${postgres.table}`);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    const jar = join(project, "target/vertx-postgres-crud-0.1.0.jar");
    await expect.file(jar).toExist();
    const running = await startZoltHttpProcess(t, zolt, "vertx-postgres-package", [
      "--no-progress", "run-package", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--", `--port=${port.port}`,
    ], port.port, postgres.env);
    await verifyVertxPostgresCrud(t, port.url(), postgres);
    await running.stop();
  });
});
