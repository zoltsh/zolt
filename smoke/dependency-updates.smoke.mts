import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { startAuthenticatedFileServer } from "./support/authenticated-server.mts";
import {
  expectJsonObject,
  findJsonObjectByString,
  jsonArray,
  jsonNumber,
  jsonString,
  parseJsonObject,
} from "./support/json.mts";
import {
  installVersionedArtifact,
  UPDATE_ARTIFACT,
  UPDATE_COORDINATE,
  UPDATE_NEW_VERSION,
  UPDATE_OLD_VERSION,
  UPDATE_REPOSITORY_PASSWORD_ENV,
  UPDATE_REPOSITORY_USERNAME_ENV,
  writeUpdateConsumer,
  writeUpdateLibrary,
} from "./support/update-fixtures.mts";
import { isolatedUserGlobalHome } from "./support/user-global-config.mts";
import { expectTextFile, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("dependency update reporting smoke", { tags: ["dependencies", "enterprise"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-dependency-updates");
  const zolt = await packagedZolt(t);
  const credentials = { username: "update-user", password: "update-token" } as const;
  t.redact(credentials.password);

  const home = await isolatedUserGlobalHome(t, work, "updates-home");
  const env = {
    ...home.env,
    [UPDATE_REPOSITORY_USERNAME_ENV]: credentials.username,
    [UPDATE_REPOSITORY_PASSWORD_ENV]: credentials.password,
  };
  const cache = work.path("artifact-cache");
  const consumer = work.path("consumer");

  await t.step("hosts one artifact at two versions and pins the consumer to the older one", async () => {
    const library = work.path("widget-library");
    await writeUpdateLibrary(library);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", library, "--cache-root", zolt.cacheRoot]);
    const repository = work.path("repository");
    await installVersionedArtifact(repository, await singleJar(join(library, "target")), [
      UPDATE_OLD_VERSION,
      UPDATE_NEW_VERSION,
    ]);

    const server = await startAuthenticatedFileServer(t, repository, credentials);
    await writeUpdateConsumer(consumer, server.url("maven2/"));
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", consumer, "--cache-root", cache], { env });
    await expectTextFile(join(consumer, "zolt.lock"), {
      contains: [`${UPDATE_COORDINATE}`, `${UPDATE_ARTIFACT}-${UPDATE_OLD_VERSION}.jar`],
    });
  });

  await t.step("reports the newer version in human and JSON output", async () => {
    const text = await runZolt(t, zolt, ["--no-progress", "outdated", "--cwd", consumer], { env });
    expect.value(text.stdout).toContain(UPDATE_COORDINATE);
    expect.value(text.stdout).toContain(UPDATE_OLD_VERSION);
    expect.value(text.stdout).toContain(`-> ${UPDATE_NEW_VERSION}`);

    const json = await runZolt(t, zolt, [
      "--no-progress", "outdated", "--format", "json", "--cwd", consumer,
    ], { env });
    const report = parseJsonObject(t, json.stdout, "zolt outdated --format json output");
    expect.value(jsonNumber(t, report, "schemaVersion", "outdated")).toBe(1);
    expect.value(jsonString(t, report, "command", "outdated")).toBe("outdated");

    const scopes = jsonArray(t, report, "scopes", "outdated");
    const entries = scopes.flatMap((scope, index) =>
      jsonArray(t, expectJsonObject(t, scope, `outdated.scopes[${index}]`), "entries", "scope"));
    const entry = findJsonObjectByString(t, entries, "identifier", UPDATE_COORDINATE, "outdated entries");
    expect.value(jsonString(t, entry, "surface", "entry")).toBe("dependency");
    expect.value(jsonString(t, entry, "section", "entry")).toBe("[dependencies]");
    expect.value(jsonString(t, entry, "current", "entry")).toBe(UPDATE_OLD_VERSION);
    expect.value(jsonString(t, entry, "status", "entry")).toBe("update-available");
    expect.value(jsonString(t, entry, "selectedInMajor", "entry")).toBe(UPDATE_NEW_VERSION);
  });

  await t.step("rewrites zolt.toml and leaves the project resolvable", async () => {
    const preview = await runZolt(t, zolt, [
      "--no-progress", "update", "--dry-run", "--cwd", consumer, "--cache-root", cache,
    ], { env });
    expect.value(preview.stdout).toContain("Planned updates (dry run):");
    expect.value(preview.stdout).toContain(`${UPDATE_OLD_VERSION} -> ${UPDATE_NEW_VERSION}`);
    await expectTextFile(join(consumer, "zolt.toml"), {
      contains: [`"${UPDATE_COORDINATE}" = "${UPDATE_OLD_VERSION}"`],
    });

    const applied = await runZolt(t, zolt, [
      "--no-progress", "update", "--cwd", consumer, "--cache-root", cache,
    ], { env });
    expect.value(applied.stdout).toContain("Updated:");
    await expectTextFile(join(consumer, "zolt.toml"), {
      contains: [`"${UPDATE_COORDINATE}" = "${UPDATE_NEW_VERSION}"`],
      excludes: [`"${UPDATE_COORDINATE}" = "${UPDATE_OLD_VERSION}"`],
    });

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", consumer, "--cache-root", cache], { env });
    await expectTextFile(join(consumer, "zolt.lock"), {
      contains: [`${UPDATE_ARTIFACT}-${UPDATE_NEW_VERSION}.jar`],
      excludes: [`${UPDATE_ARTIFACT}-${UPDATE_OLD_VERSION}.jar`],
    });
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", consumer, "--cache-root", cache], { env });

    const settled = await runZolt(t, zolt, ["--no-progress", "outdated", "--cwd", consumer], { env });
    expect.value(settled.stdout).toContain("All tracked versions are up to date.");
  });
});
