import { expect, smoke, type SmokeContext } from "smoque";
import { mkdir } from "node:fs/promises";
import { join } from "node:path";

import { sha256File } from "./support/artifacts.mts";
import {
  compilePublishedConsumer,
  installPublishedArtifact,
  writePublisherFixture,
} from "./support/publish-fixture.mts";
import { parsePublishDryRun } from "./support/publish-output.mts";
import {
  expectTestsFound,
  expectTextFile,
  packagedZolt,
  runZolt,
  singleJar,
} from "./support/zolt-smoke.mts";

smoke.suite("publish compatibility smoke", { tags: ["publish", "compatibility"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-publish-compatibility");
  const zolt = await packagedZolt(t);

  await t.step("initializes, tests, and packages a quickstart through the packaged CLI", async () => {
    const root = work.path("quickstart");
    const project = join(root, "app");
    await mkdir(root, { recursive: true });
    await runZolt(t, zolt, [
      "--no-progress",
      "init",
      "--cwd",
      root,
      "--group",
      "com.example.quickstart",
      "--java",
      "21",
      "app",
    ]);
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
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    await expect.file(await singleJar(join(project, "target"))).toExist();
  });

  await t.step("dry-run metadata materializes a consumable Maven artifact", async () => {
    const publisher = work.path("publisher");
    await writePublisherFixture(publisher);
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", publisher, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", publisher, "--cache-root", zolt.cacheRoot]);
    const result = await runZolt(t, zolt, ["--no-progress", "publish", "--cwd", publisher, "--dry-run"]);
    expect.value(result.stdout).toContain("Coordinate: com.example.publish:publisher-lib:1.2.3");
    expect.value(result.stdout).toContain("Status: ready");
    expect.value(result.stdout).toContain("No upload was performed.");

    const publish = parsePublishDryRun(result.stdout);
    const artifact = join(publisher, publish.artifactPath);
    const pom = join(publisher, publish.pomPath);
    expect.value(`sha256:${await sha256File(artifact)}`).toBe(publish.artifactChecksum);
    expect.value(`sha256:${await sha256File(pom)}`).toBe(publish.pomChecksum);
    await expectTextFile(pom, {
      contains: [
        "<groupId>com.example.publish</groupId>",
        "<artifactId>publisher-lib</artifactId>",
        "<version>1.2.3</version>",
      ],
    });

    const repository = work.path("repository");
    const installedArtifact = await installPublishedArtifact(repository, artifact, pom, publish);
    await compilePublishedConsumer(t, work.path("consumer"), installedArtifact);
  });
});
