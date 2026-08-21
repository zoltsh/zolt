import { expect, smoke, type SmokeContext } from "smoque";
import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("zolt release archive smoke", { tags: ["jvm", "release"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-release-smoke");
  const zolt = await packagedZolt(t);

  await t.step("release archive version override stamps artifacts", async () => {
    const project = work.path("release-project");
    const target = "linux-x64";
    const version = "0.1.0-nightly.20260704.0123456789ab";
    const rootDirectory = `zolt-${version}-${target}`;
    const archivePath = join(project, "dist", `${rootDirectory}.tar.gz`);

    await mkdir(join(project, "bin"), { recursive: true });
    await mkdir(join(project, "target/libexec"), { recursive: true });
    await writeFile(
      join(project, "zolt.toml"),
      `[project]
name = "zolt"
version = "0.1.0-SNAPSHOT"
group = "sh.zolt"
java = 21
`,
      "utf8",
    );
    await writeFile(join(project, "bin/zolt-stub"), "#!/usr/bin/env bash\nexit 0\n", "utf8");
    await chmod(join(project, "bin/zolt-stub"), 0o755);
    await writeFile(join(project, "target/libexec/zolt-junit-worker.jar"), "worker\n", "utf8");
    await writeFile(join(project, "LICENSE"), "Apache License, Version 2.0\n", "utf8");
    await writeFile(join(project, "NOTICE"), "Zolt\nCopyright 2026 The Zolt Authors\n", "utf8");
    await writeFile(join(project, "THIRD_PARTY_NOTICES"), "Zolt - Third-Party Notices\n", "utf8");

    await runZolt(t, zolt, [
      "--no-progress",
      "release-archive",
      "--directory",
      project,
      "--target",
      target,
      "--binary",
      "bin/zolt-stub",
      "--output",
      "dist",
    ], {
      env: {
        ZOLT_VERSION_OVERRIDE: version,
      },
    });

    await expect.file(archivePath).toExist();
    await expect.archive(archivePath).toContainEntries([
      `${rootDirectory}/bin/zolt`,
      `${rootDirectory}/libexec/zolt-junit-worker.jar`,
      `${rootDirectory}/VERSION`,
      `${rootDirectory}/LICENSE`,
      `${rootDirectory}/NOTICE`,
      `${rootDirectory}/THIRD_PARTY_NOTICES`,
    ]);

    const embeddedVersion = await t.cmd("tar", ["-xzOf", archivePath, `${rootDirectory}/VERSION`], { cwd: root });
    expect.value(embeddedVersion.stdout.trim()).toBe(version);

    const manifest = JSON.parse(await readFile(join(project, "dist/release-manifest.json"), "utf8"));
    expect.value(manifest.version).toBe(version);

    const reportedVersion = await runZolt(t, zolt, ["--version"], {
      timeout: "30s",
      env: {
        ZOLT_VERSION_OVERRIDE: version,
      },
    });
    expect.value(reportedVersion.stdout.trim()).toBe(version);
  });

  await t.step("release archive ships workspace-root legal documents", async () => {
    const workspaceRoot = work.path("release-workspace");
    const project = join(workspaceRoot, "apps/zolt");
    const target = "linux-x64";
    const rootDirectory = `zolt-0.1.0-SNAPSHOT-${target}`;
    const archivePath = join(project, "dist", `${rootDirectory}.tar.gz`);

    await mkdir(join(project, "bin"), { recursive: true });
    await writeFile(
      join(workspaceRoot, "zolt.toml"),
      `[workspace]
name = "zolt"

[workspace.members]
include = ["apps/zolt"]
`,
      "utf8",
    );
    await writeFile(join(workspaceRoot, "LICENSE"), "Apache License, Version 2.0\n", "utf8");
    await writeFile(join(workspaceRoot, "NOTICE"), "Zolt\nCopyright 2026 The Zolt Authors\n", "utf8");
    await writeFile(
      join(workspaceRoot, "THIRD_PARTY_NOTICES"),
      "Zolt - Third-Party Notices\npicocli 4.7.6 Apache-2.0\n",
      "utf8",
    );
    await writeFile(
      join(project, "zolt.toml"),
      `[project]
name = "zolt"
version = "0.1.0-SNAPSHOT"
group = "sh.zolt"
java = 21
`,
      "utf8",
    );
    await writeFile(join(project, "bin/zolt-stub"), "#!/usr/bin/env bash\nexit 0\n", "utf8");
    await chmod(join(project, "bin/zolt-stub"), 0o755);

    await runZolt(t, zolt, [
      "--no-progress",
      "release-archive",
      "--directory",
      project,
      "--target",
      target,
      "--binary",
      "bin/zolt-stub",
      "--output",
      "dist",
    ]);

    await expect.file(archivePath).toExist();
    await expect.archive(archivePath).toContainEntries([
      `${rootDirectory}/bin/zolt`,
      `${rootDirectory}/VERSION`,
      `${rootDirectory}/LICENSE`,
      `${rootDirectory}/NOTICE`,
      `${rootDirectory}/THIRD_PARTY_NOTICES`,
    ]);

    for (const document of ["LICENSE", "NOTICE", "THIRD_PARTY_NOTICES"]) {
      const shipped = await t.cmd("tar", ["-xzOf", archivePath, `${rootDirectory}/${document}`], { cwd: root });
      expect.value(shipped.stdout.trim().length > 0).toBe(true);
    }
  });
});
