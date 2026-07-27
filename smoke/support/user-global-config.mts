import type { PathRef, SmokeContext } from "smoque";
import { mkdir, writeFile } from "node:fs/promises";
import { isAbsolute, join } from "node:path";

/**
 * An isolated user-global directory. Zolt reads `~/.zolt/config.toml`, the artifact cache, and the
 * toolchain store through one directory, and `ZOLT_USER_HOME` redirects that whole tree, so a smoke
 * isolates all of them with a plain environment variable instead of touching the machine's real home.
 */
export interface UserGlobalHome {
  readonly configPath: string;
  readonly env: Readonly<Record<string, string>>;
  readonly path: string;
  write(lines: readonly string[]): Promise<void>;
}

export async function isolatedUserGlobalHome(
  t: SmokeContext,
  work: PathRef,
  name: string,
): Promise<UserGlobalHome> {
  const home = work.path(name);
  const userHome = join(home, ".zolt");
  if (!isAbsolute(userHome)) {
    t.fail(`ZOLT_USER_HOME must be an absolute path: ${userHome}`);
  }
  await mkdir(userHome, { recursive: true });
  const configPath = join(userHome, "config.toml");
  return {
    configPath,
    env: { HOME: home, ZOLT_USER_HOME: userHome },
    path: home,
    write: async (lines: readonly string[]) => await writeFile(configPath, `${lines.join("\n")}\n`, "utf8"),
  };
}
