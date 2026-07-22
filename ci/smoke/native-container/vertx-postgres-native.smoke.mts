import { smoke, type SmokeContext } from "smoque";

import {
  expectCleanNativeImageLog,
  expectNativeExecutable,
  nativeImageCommand,
  startNativeHttpProcess,
} from "../../../smoke/support/native-runtime.mts";
import { startEphemeralPostgres } from "../../../smoke/support/postgres-runtime.mts";
import { copyFixture, packagedZolt, runZolt } from "../../../smoke/support/zolt-smoke.mts";
import { verifyVertxPostgresCrud } from "../../../smoke/support/vertx-postgres-probes.mts";

smoke.suite("Vert.x PostgreSQL real Native Image smoke", {
  tags: ["native", "container", "postgres", "vertx", "real"],
}, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-vertx-postgres-native");
  const project = await copyFixture(root, work, "vertx-postgres-crud");
  const zolt = await packagedZolt(t);
  const nativeImage = nativeImageCommand();
  const port = await t.ports.reserve("vertx-postgres-native");

  await t.step("builds the PostgreSQL application as a native executable", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, [
      "--no-progress", "native", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--native-image", nativeImage,
    ], { timeout: "10m" });
    await expectNativeExecutable(work.path("vertx-postgres-crud/target/native/vertx-postgres-crud"));
    await expectCleanNativeImageLog(t, work.path("vertx-postgres-crud/target/native/native-image.log"));
  });

  await t.step("serves the CRUD contract against a Smoque-owned PostgreSQL container", async () => {
    const postgres = await startEphemeralPostgres(t, {
      image: "postgres:16-alpine",
      database: "zolt_native",
      user: "zolt_native",
      password: "zolt_native_password",
      table: "zolt_notes_native_smoke",
    });
    const binary = work.path("vertx-postgres-crud/target/native/vertx-postgres-crud");
    const running = await startNativeHttpProcess(
      t, binary, "vertx-postgres-native", [`--port=${port.port}`], port.port, postgres.env,
    );
    await verifyVertxPostgresCrud(t, port.url(), postgres);
    await running.stop();
  });
});
