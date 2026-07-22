import { expect, smoke, type SmokeContext } from "smoque";

import {
  downloadExecutable,
  fileUrl,
  installFromChannel,
  nativeHttpsProtocolDisabled,
  readChannelManifest,
  readUrl,
  requireManifest,
  requiredReleaseEnv,
  runInstalledZolt,
  runInstalledZoltSelf,
  waitForPublishedChannel,
  writeChannelUrl,
  type ChannelManifest,
} from "./support/release-update.mts";

smoke.suite("zolt published release update smoke", { tags: ["release", "release-update"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-release-update-smoke");
  const installRoot = work.path("install");
  const currentInstallRoot = work.path("current-install");
  const target = process.env.ZOLT_RELEASE_UPDATE_TARGET ?? "linux-x64";
  const origin = requiredReleaseEnv("ZOLT_DISTRIBUTION_ORIGIN").replace(/\/+$/u, "");
  const expectedVersion = requiredReleaseEnv("ZOLT_RELEASE_UPDATE_EXPECTED_VERSION");
  const previousChannelPath = requiredReleaseEnv("ZOLT_RELEASE_UPDATE_PREVIOUS_CHANNEL");
  const currentChannelUrl = process.env.ZOLT_RELEASE_UPDATE_CHANNEL_URL ?? `${origin}/channels/zap.json`;
  const installScriptUrl = process.env.ZOLT_RELEASE_UPDATE_INSTALL_SCRIPT_URL ?? `${origin}/install.sh`;
  const installScript = work.path("install-zolt");

  let previousManifest: ChannelManifest | undefined;
  let currentManifest: ChannelManifest | undefined;
  let exercisedSelfUpdate = false;

  await t.step("download public installer and verify published channel", async () => {
    await downloadExecutable(installScriptUrl, installScript);

    previousManifest = await readChannelManifest(previousChannelPath);
    currentManifest = await waitForPublishedChannel(currentChannelUrl, expectedVersion);
    const currentSignature = await readUrl(`${currentChannelUrl}.sig`);

    expect.value(previousManifest.channel).toBe("zap");
    expect.value(currentManifest.channel).toBe("zap");
    expect.value(currentManifest.version).toBe(expectedVersion);
    expect.value(currentSignature).toContain("version: zolt-ed25519-v1");
    expect.value(currentSignature).toContain("keyId: zolt-release-2026");
    expect.value(currentSignature).toContain("signature:");
  });

  await t.step("install previous public zap build", async () => {
    const previous = requireManifest(previousManifest, "previous zap channel");
    if (previous.version === expectedVersion) {
      await installFromChannel(t, installScript, currentInstallRoot, currentChannelUrl, target);
      const currentVersion = await runInstalledZolt(t, currentInstallRoot, ["--version"]);
      expect.value(currentVersion.stdout.trim()).toBe(expectedVersion);
      return;
    }

    await installFromChannel(t, installScript, installRoot, fileUrl(previousChannelPath), target);
    const previousVersion = await runInstalledZolt(t, installRoot, ["--version"]);
    expect.value(previousVersion.stdout.trim()).toBe(previous.version);
  });

  await t.step("self-update to the newly published zap build", async () => {
    const previous = requireManifest(previousManifest, "previous zap channel");
    if (previous.version === expectedVersion) {
      const versions = await runInstalledZoltSelf(t, currentInstallRoot, ["versions"]);
      expect.value(versions.stdout).toContain(expectedVersion);
      return;
    }

    const selfHelp = await runInstalledZolt(t, installRoot, ["self", "--help"], { check: false });
    if (selfHelp.exitCode !== 0) {
      await installFromChannel(t, installScript, currentInstallRoot, currentChannelUrl, target);
      const currentVersion = await runInstalledZolt(t, currentInstallRoot, ["--version"]);
      expect.value(currentVersion.stdout.trim()).toBe(expectedVersion);
      const versions = await runInstalledZoltSelf(t, currentInstallRoot, ["versions"]);
      expect.value(versions.stdout).toContain(expectedVersion);
      return;
    }

    await writeChannelUrl(installRoot, currentChannelUrl);
    const update = await runInstalledZoltSelf(t, installRoot, ["update"], { check: false });
    if (update.exitCode !== 0 && nativeHttpsProtocolDisabled(update)) {
      await installFromChannel(t, installScript, currentInstallRoot, currentChannelUrl, target);
      const currentVersion = await runInstalledZolt(t, currentInstallRoot, ["--version"]);
      expect.value(currentVersion.stdout.trim()).toBe(expectedVersion);
      const versions = await runInstalledZoltSelf(t, currentInstallRoot, ["versions"]);
      expect.value(versions.stdout).toContain(expectedVersion);
      return;
    }
    if (update.exitCode !== 0) {
      throw new Error(`zolt self update failed unexpectedly.\nstdout:\n${update.stdout}\nstderr:\n${update.stderr}`);
    }
    exercisedSelfUpdate = true;

    const updatedVersion = await runInstalledZolt(t, installRoot, ["--version"]);
    expect.value(updatedVersion.stdout.trim()).toBe(expectedVersion);

    const versions = await runInstalledZoltSelf(t, installRoot, ["versions"]);
    expect.value(versions.stdout).toContain(previous.version);
    expect.value(versions.stdout).toContain(expectedVersion);
    expect.value(versions.stdout).toContain(`* ${expectedVersion} current`);
  });

  await t.step("rollback and reselect the published build", async () => {
    const previous = requireManifest(previousManifest, "previous zap channel");
    if (previous.version === expectedVersion) {
      return;
    }

    const selfHelp = await runInstalledZolt(t, installRoot, ["self", "--help"], { check: false });
    if (selfHelp.exitCode !== 0) {
      return;
    }
    if (!exercisedSelfUpdate) {
      return;
    }

    await runInstalledZoltSelf(t, installRoot, ["rollback"]);
    const rolledBackVersion = await runInstalledZolt(t, installRoot, ["--version"]);
    expect.value(rolledBackVersion.stdout.trim()).toBe(previous.version);

    await runInstalledZoltSelf(t, installRoot, ["use", expectedVersion]);
    const restoredVersion = await runInstalledZolt(t, installRoot, ["--version"]);
    expect.value(restoredVersion.stdout.trim()).toBe(expectedVersion);
  });
});
