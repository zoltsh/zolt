import { expect, smoke, type SmokeContext } from "smoque";
import { existsSync } from "node:fs";
import { appendFile } from "node:fs/promises";
import { join } from "node:path";

import { SIGNING_PASSPHRASE_ENV, throwawaySigningKey } from "./support/gpg-signing.mts";
import {
  FIXTURE_CONSUMER_URL,
  FIXTURE_PUBLISH_URL,
  retargetFixtureUrl,
  writeCentralCandidateFixture,
} from "./support/publish-fixture.mts";
import { startUploadFileServer } from "./support/upload-server.mts";
import { copyFixture, expectTextFile, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

const BOM_PATH = "releases/com/acme/platform/acme-bom/1.0.0/acme-bom-1.0.0.pom";
const CORE_PATH = "releases/com/acme/acme-core/1.0.0/acme-core-1.0.0";
const CHECKSUMS = ["md5", "sha1", "sha256"] as const;

smoke.suite("workspace publishing smoke", { tags: ["publish", "workspace", "enterprise"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-workspace-publish");
  const zolt = await packagedZolt(t);
  const server = await startUploadFileServer(t, work.path("repository"));

  await t.step("publishes a workspace family with checksum sidecars and a BOM", async () => {
    const family = await copyFixture(root, work, "platform-family");
    await retargetFixtureUrl(family, FIXTURE_PUBLISH_URL, server.url("releases"));

    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--workspace", "--cwd", family, "--cache-root", zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, [
      "--no-progress", "package", "--workspace", "--all", "--cwd", family, "--cache-root", zolt.cacheRoot,
    ]);
    const published = await runZolt(t, zolt, [
      "--no-progress", "publish", "--workspace", "--cwd", family, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(published.stdout).toContain("com.acme:acme-core:1.0.0 -> local");
    expect.value(published.stdout).toContain("com.acme.platform:acme-bom:1.0.0 [bom] -> local");
    expect.value(published.stdout).toContain("Uploaded the family.");

    for (const artifact of [`${CORE_PATH}.jar`, `${CORE_PATH}.pom`, BOM_PATH]) {
      await expect.file(join(server.root, artifact)).toExist();
      for (const algorithm of CHECKSUMS) {
        await expect.file(join(server.root, `${artifact}.${algorithm}`)).toExist();
      }
    }

    await expectTextFile(join(server.root, BOM_PATH), {
      contains: [
        "<packaging>pom</packaging>",
        "<dependencyManagement>\n    <dependencies>",
        "<artifactId>acme-core</artifactId>",
        "<artifactId>acme-http</artifactId>",
      ],
    });
  });

  await t.step("resolves a version-less dependency through the published BOM", async () => {
    const consumer = await copyFixture(root, work, "platform-family-consumer");
    await retargetFixtureUrl(consumer, FIXTURE_CONSUMER_URL, server.url("releases"));

    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--cwd", consumer, "--cache-root", work.path("consumer-cache"),
    ]);
    await expectTextFile(join(consumer, "zolt.lock"), {
      contains: [
        'id = "com.acme:acme-http"',
        'version = "1.0.0"',
        "managed-version: com.acme:acme-http -> 1.0.0 from com.acme.platform:acme-bom:1.0.0",
      ],
    });
    await runZolt(t, zolt, [
      "--no-progress", "build", "--cwd", consumer, "--cache-root", work.path("consumer-cache"),
    ]);
    await expect.file(join(consumer, "target/classes/com/acme/app/Main.class")).toExist();
  });

  await t.step("reports a Maven Central readiness checklist without uploading", async () => {
    const candidate = work.path("central-candidate");
    await writeCentralCandidateFixture(candidate, server.url("releases"));
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", candidate, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", candidate, "--cache-root", zolt.cacheRoot]);

    const before = server.requests.length;
    const readiness = await runZolt(t, zolt, [
      "--no-progress", "publish", "--dry-run", "--central", "--cwd", candidate, "--cache-root", zolt.cacheRoot,
    ], { check: false });

    expect.value(readiness.exitCode).toBe(1);
    expect.value(readiness.stdout).toContain("Target URL: https://central.sonatype.com");
    expect.value(readiness.stdout).toContain("No upload was performed.");
    expect.value(readiness.stdout).toContain("Maven Central readiness:");
    for (const requirement of [
      "release version", "project name", "project description", "project url",
      "license name and url", "developer information", "scm url and connection",
      "sources jar", "javadoc jar", "checksums",
    ]) {
      expect.value(readiness.stdout).toContain(`- [x] ${requirement}`);
    }
    expect.value(readiness.stdout).toContain("- [ ] gpg signatures");
    expect.value(readiness.stdout).toContain("Next: Enable [publish.signing]");
    expect.value(readiness.stdout).toContain("Central status: not ready");
    expect.value(readiness.stdout).toMatch(/Central bundle: .*central-bundle\.zip/u);
    expect.value(server.requests.length).toBe(before);
  });

  await t.step("reports Maven Central readiness from inside a workspace member", async () => {
    const family = await copyFixture(root, work, "platform-family", "member-central-family");
    await retargetFixtureUrl(family, FIXTURE_PUBLISH_URL, server.url("releases"));
    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--workspace", "--cwd", family, "--cache-root", zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, [
      "--no-progress", "package", "--workspace", "--all", "--cwd", family, "--cache-root", zolt.cacheRoot,
    ]);

    // A --workspace resolve writes only the root lock, so the member plans from the aggregated one.
    const member = join(family, "acme-core");
    expect.value(existsSync(join(member, "zolt.lock"))).toBe(false);
    expect.value(existsSync(join(family, "zolt.lock"))).toBe(true);

    const before = server.requests.length;
    const readiness = await runZolt(t, zolt, [
      "--no-progress", "publish", "--dry-run", "--central", "--cwd", member, "--cache-root", zolt.cacheRoot,
    ], { check: false });

    expect.value(readiness.exitCode).toBe(1);
    expect.value(readiness.stdout).toContain("Coordinate: com.acme:acme-core:1.0.0");
    expect.value(readiness.stdout).toContain("Target URL: https://central.sonatype.com");
    expect.value(readiness.stdout).toContain("Maven Central readiness:");
    for (const requirement of [
      "release version", "project name", "project description", "project url",
      "license name and url", "scm url and connection", "checksums",
    ]) {
      expect.value(readiness.stdout).toContain(`- [x] ${requirement}`);
    }
    for (const requirement of ["developer information", "sources jar", "javadoc jar", "gpg signatures"]) {
      expect.value(readiness.stdout).toContain(`- [ ] ${requirement}`);
    }
    expect.value(readiness.stdout).toContain("Central status: not ready");
    expect.value(server.requests.length).toBe(before);
  });

  await t.step("signs every uploaded file when gpg can provide a key", async () => {
    const key = await throwawaySigningKey(t);
    if (key === undefined) {
      await t.log("gpg is unavailable or could not generate a key; signing coverage is skipped.");
      return;
    }

    const signed = work.path("signed-candidate");
    const signedServer = await startUploadFileServer(t, work.path("signed-repository"));
    await writeCentralCandidateFixture(signed, signedServer.url("releases"));
    await appendFile(join(signed, "zolt.toml"), [
      "[publish.signing]", "enabled = true", `keyId = "${key.keyId}"`,
      `passphraseEnv = "${SIGNING_PASSPHRASE_ENV}"`, "",
    ].join("\n"), "utf8");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", signed, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", signed, "--cache-root", zolt.cacheRoot]);
    const result = await runZolt(t, zolt, [
      "--no-progress", "publish", "--cwd", signed, "--cache-root", zolt.cacheRoot,
    ], { env: key.env });
    expect.value(result.stdout).toContain("Status: uploaded");

    const base = "releases/com/example/central/central-candidate/1.0.0/central-candidate-1.0.0";
    for (const artifact of [`${base}.jar`, `${base}-sources.jar`, `${base}-javadoc.jar`, `${base}.pom`]) {
      await expect.file(join(signedServer.root, `${artifact}.asc`)).toExist();
      for (const algorithm of CHECKSUMS) {
        await expect.file(join(signedServer.root, `${artifact}.asc.${algorithm}`)).toExist();
      }
    }
    await expect.file(join(signedServer.root, `${base}.jar.asc`))
      .toContain("-----BEGIN PGP SIGNATURE-----");
  });
});
