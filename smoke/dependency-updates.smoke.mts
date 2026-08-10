import { expect, smoke, type SmokeContext } from "smoque";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

import {
  startAuthenticatedFileServer,
  type AuthenticatedFileServer,
} from "./support/authenticated-server.mts";
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
  let exactTargetId = "";
  let repository = "";
  let server: AuthenticatedFileServer;

  await t.step("hosts one artifact at two versions and pins the consumer to the older one", async () => {
    const library = work.path("widget-library");
    await writeUpdateLibrary(library);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", library, "--cache-root", zolt.cacheRoot]);
    repository = work.path("repository");
    await installVersionedArtifact(repository, await singleJar(join(library, "target")), [
      UPDATE_OLD_VERSION,
      UPDATE_NEW_VERSION,
    ]);

    server = await startAuthenticatedFileServer(t, repository, credentials);
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

    const automationJson = await runZolt(t, zolt, [
      "--no-progress", "outdated", "--format", "json", "--schema-version", "2", "--cwd", consumer,
    ], { env });
    const automation = parseJsonObject(t, automationJson.stdout, "outdated schema v2 output");
    expect.value(jsonNumber(t, automation, "schemaVersion", "outdated v2")).toBe(2);
    const automationScopes = jsonArray(t, automation, "scopes", "outdated v2");
    const automationScope = expectJsonObject(t, automationScopes[0], "outdated v2.scopes[0]");
    const automationEntries = automationScopes.flatMap((scope, index) =>
      jsonArray(t, expectJsonObject(t, scope, `outdated v2.scopes[${index}]`), "entries", "scope"));
    const automationEntry = findJsonObjectByString(
      t, automationEntries, "identifier", UPDATE_COORDINATE, "outdated v2 entries");
    exactTargetId = jsonString(t, automationEntry, "targetId", "automation entry");
    expect.value(jsonString(t, automationScope, "manifestPath", "automation scope")).toBe("zolt.toml");
    expect.value(jsonString(t, automationScope, "lockfilePath", "automation scope")).toBe("zolt.lock");
    expect.value(automationEntry.updateable).toBe(true);
  });

  await t.step("routes a workspace-root exact target to one nested member and the root lock", async () => {
    const workspace = work.path("exact-workspace");
    const api = join(workspace, "apps/api");
    const core = join(workspace, "modules/core");
    await mkdir(core, { recursive: true });
    await writeFile(join(workspace, "zolt.toml"), [
      "[workspace]", 'name = "exact-workspace"', 'members = ["apps/api", "modules/core"]', "",
    ].join("\n"), "utf8");
    await writeUpdateConsumer(api, server.url("maven2/"));
    await writeFile(join(core, "zolt.toml"), [
      "[project]", 'name = "core"', 'version = "0.1.0"',
      'group = "com.example.workspace"', 'java = "21"', "", "[dependencies]", "",
    ].join("\n"), "utf8");
    const workspaceCache = work.path("workspace-cache");
    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--workspace", "--cwd", workspace, "--cache-root", workspaceCache,
    ], { env });

    const reportOutput = await runZolt(t, zolt, [
      "--no-progress", "outdated", "--format", "json", "--schema-version", "2", "--cwd", workspace,
    ], { env });
    const report = parseJsonObject(t, reportOutput.stdout, "workspace outdated schema v2 output");
    const scopes = jsonArray(t, report, "scopes", "workspace outdated");
    const apiScope = findJsonObjectByString(
      t, scopes, "manifestPath", "apps/api/zolt.toml", "workspace outdated scopes");
    expect.value(jsonString(t, apiScope, "lockfilePath", "workspace scope")).toBe("zolt.lock");
    const entries = jsonArray(t, apiScope, "entries", "workspace api scope");
    const entry = findJsonObjectByString(
      t, entries, "identifier", UPDATE_COORDINATE, "workspace outdated entries");
    const workspaceTargetId = jsonString(t, entry, "targetId", "workspace target");
    const siblingBefore = await readFile(join(core, "zolt.toml"), "utf8");
    const rootLockBefore = await readFile(join(workspace, "zolt.lock"), "utf8");

    const applied = await runZolt(t, zolt, [
      "--no-progress", "update",
      "--target-id", workspaceTargetId,
      "--to", UPDATE_NEW_VERSION,
      "--format", "json",
      "--schema-version", "2",
      "--cwd", workspace,
      "--cache-root", workspaceCache,
    ], { env });
    const result = parseJsonObject(t, applied.stdout, "workspace exact update output");
    expect.value(JSON.stringify(jsonArray(t, result, "changedFiles", "workspace exact update")))
      .toBe(JSON.stringify(["apps/api/zolt.toml", "zolt.lock"]));
    await expectTextFile(join(api, "zolt.toml"), {
      contains: [`"${UPDATE_COORDINATE}" = "${UPDATE_NEW_VERSION}"`],
    });
    expect.value(await readFile(join(core, "zolt.toml"), "utf8")).toBe(siblingBefore);
    expect.value((await readFile(join(workspace, "zolt.lock"), "utf8")) === rootLockBefore).toBe(false);
    await expect.file(join(api, "zolt.lock")).notToExist();
    await expect.file(join(core, "zolt.lock")).notToExist();
    await runZolt(t, zolt, [
      "--no-progress", "build", "--workspace", "--member", "apps/api",
      "--cwd", workspace, "--cache-root", workspaceCache,
    ], { env });
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

    const metadataPath = join(
      repository, "maven2", ...UPDATE_COORDINATE.split(":")[0].split("."), UPDATE_ARTIFACT, "maven-metadata.xml");
    await rm(metadataPath);
    const metadataRequestsBefore = server.requests.filter((request) => request.includes("maven-metadata.xml")).length;
    const applied = await runZolt(t, zolt, [
      "--no-progress", "update",
      "--target-id", exactTargetId,
      "--to", UPDATE_NEW_VERSION,
      "--format", "json",
      "--schema-version", "2",
      "--cwd", consumer,
      "--cache-root", cache,
    ], { env });
    const result = parseJsonObject(t, applied.stdout, "exact update schema v2 output");
    expect.value(jsonNumber(t, result, "schemaVersion", "exact update")).toBe(2);
    expect.value(result.changed).toBe(true);
    expect.value(result.applied).toBe(true);
    expect.value(result.resolved).toBe(true);
    expect.value(JSON.stringify(jsonArray(t, result, "changedFiles", "exact update")))
      .toBe(JSON.stringify(["zolt.toml", "zolt.lock"]));
    expect.value(applied.stdout.includes(credentials.username)).toBe(false);
    expect.value(applied.stdout.includes(credentials.password)).toBe(false);
    expect.value(server.requests.filter((request) => request.includes("maven-metadata.xml")).length)
      .toBe(metadataRequestsBefore);
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

    const noOp = await runZolt(t, zolt, [
      "--no-progress", "update",
      "--target-id", exactTargetId,
      "--to", UPDATE_NEW_VERSION,
      "--format", "json",
      "--schema-version", "2",
      "--cwd", consumer,
      "--cache-root", cache,
    ], { env });
    const noOpResult = parseJsonObject(t, noOp.stdout, "exact update no-op output");
    expect.value(noOpResult.changed).toBe(false);
    expect.value(noOpResult.applied).toBe(false);
    expect.value(noOpResult.resolved).toBe(false);
    expect.value(JSON.stringify(jsonArray(t, noOpResult, "changedFiles", "exact update no-op"))).toBe("[]");

    const manifestBeforeFailure = await readFile(join(consumer, "zolt.toml"), "utf8");
    const lockBeforeFailure = await readFile(join(consumer, "zolt.lock"), "utf8");
    const unavailable = await runZolt(t, zolt, [
      "--no-progress", "update",
      "--target-id", exactTargetId,
      "--to", "9.9.9",
      "--format", "json",
      "--schema-version", "2",
      "--cwd", consumer,
      "--cache-root", cache,
    ], { env, check: false });
    expect.value(unavailable.exitCode).toBe(1);
    const unavailableResult = parseJsonObject(t, unavailable.stdout, "unavailable exact update failure");
    expect.value(jsonNumber(t, unavailableResult, "schemaVersion", "exact update failure")).toBe(2);
    expect.value(unavailableResult.status).toBe("failed");
    expect.value(await readFile(join(consumer, "zolt.toml"), "utf8")).toBe(manifestBeforeFailure);
    expect.value(await readFile(join(consumer, "zolt.lock"), "utf8")).toBe(lockBeforeFailure);

    const settled = await runZolt(t, zolt, ["--no-progress", "outdated", "--cwd", consumer], { env });
    expect.value(settled.stdout).toContain(UPDATE_COORDINATE);
    expect.value(settled.stdout).toContain(`${UPDATE_NEW_VERSION}  unknown`);
  });
});
