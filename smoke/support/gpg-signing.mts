import type { CommandResult, SmokeContext } from "smoque";
import { chmod, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

export const SIGNING_PASSPHRASE_ENV = "ZOLT_SMOKE_SIGNING_PASSPHRASE";

const PASSPHRASE = "zolt-smoke-passphrase";

export interface ThrowawaySigningKey {
  readonly env: Readonly<Record<string, string>>;
  readonly keyId: string;
}

/**
 * A throwaway GnuPG home and signing key, or `undefined` when gpg cannot produce one so the caller can
 * skip signing coverage instead of failing. The home is a short temp path on purpose: gpg-agent's Unix
 * socket has a far tighter length limit than the suite's temp directories.
 */
export async function throwawaySigningKey(t: SmokeContext): Promise<ThrowawaySigningKey | undefined> {
  const probe = await run(t, "gpg", ["--version"]);
  if (probe === undefined || probe.exitCode !== 0) {
    return undefined;
  }

  const home = await mkdtemp(join(tmpdir(), "zolt-gpg-"));
  await chmod(home, 0o700);
  const env = { GNUPGHOME: home };
  t.cleanup(async () => {
    await run(t, "gpgconf", ["--kill", "all"], env);
    await rm(home, { recursive: true, force: true });
  });
  t.redact(PASSPHRASE);

  const generated = await run(t, "gpg", [
    "--batch", "--pinentry-mode", "loopback", "--passphrase", PASSPHRASE,
    "--quick-generate-key", "Zolt Smoke Signing <signing@zolt.test>", "default", "sign", "0",
  ], env);
  if (generated === undefined || generated.exitCode !== 0) {
    return undefined;
  }

  const listed = await run(t, "gpg", ["--list-secret-keys", "--with-colons"], env);
  const keyId = listed?.stdout
    .split(/\r?\n/u)
    .find((line) => line.startsWith("fpr:"))
    ?.split(":")[9];
  if (keyId === undefined || keyId.length === 0) {
    return undefined;
  }

  return { env: { ...env, [SIGNING_PASSPHRASE_ENV]: PASSPHRASE }, keyId };
}

/** Runs a tool that may be absent: a missing binary fails to spawn, which `check: false` does not cover. */
async function run(
  t: SmokeContext,
  command: string,
  args: string[],
  env?: Readonly<Record<string, string>>,
): Promise<CommandResult | undefined> {
  try {
    return await t.cmd(command, args, { check: false, env });
  } catch {
    return undefined;
  }
}
