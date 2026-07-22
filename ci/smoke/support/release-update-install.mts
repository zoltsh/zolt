import type { CommandOptions, CommandResult, SmokeContext } from "smoque";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export async function installFromChannel(
  t: SmokeContext,
  installScript: string,
  installRoot: string,
  channelUrl: string,
  target: string,
): Promise<void> {
  await mkdir(installRoot, { recursive: true });
  await t.cmd(installScript, [], {
    env: {
      ZOLT_INSTALL_ROOT: installRoot,
      ZOLT_INSTALL_CHANNEL_URL: channelUrl,
      ZOLT_INSTALL_TARGET: target,
    },
    timeout: "5m",
  });
}

export async function runInstalledZolt(
  t: SmokeContext,
  installRoot: string,
  args: string[],
  options: CommandOptions = {},
): Promise<CommandResult> {
  const binary = join(installRoot, "bin", "zolt");
  return await t.cmd(binary, args, {
    timeout: "2m",
    ...options,
  });
}

export async function runInstalledZoltSelf(
  t: SmokeContext,
  installRoot: string,
  args: string[],
  options: CommandOptions = {},
): Promise<CommandResult> {
  return await runInstalledZolt(t, installRoot, ["self", ...args, "--install-root", installRoot], options);
}

export function nativeHttpsProtocolDisabled(result: CommandResult): boolean {
  return `${result.stdout}\n${result.stderr}`.includes("The URL protocol https is supported but not enabled by default");
}

export async function writeChannelUrl(installRoot: string, channelUrl: string): Promise<void> {
  await writeFile(join(installRoot, "channel-url"), `${channelUrl}\n`, "utf8");
}
