import { expect, type SmokeContext } from "smoque";
import { mkdir, readFile, rm } from "node:fs/promises";
import { join } from "node:path";

export async function expectJarManifest(
  t: SmokeContext,
  jar: string,
  workDirectory: string,
  expectedLines: readonly string[],
): Promise<void> {
  await rm(workDirectory, { recursive: true, force: true });
  await mkdir(workDirectory, { recursive: true });
  const jarTool = await t.tools.jar({ minVersion: 8 });
  await t.cmd(jarTool.command, ["xf", jar, "META-INF/MANIFEST.MF"], { cwd: workDirectory });
  const manifest = await readFile(join(workDirectory, "META-INF/MANIFEST.MF"), "utf8");
  for (const line of expectedLines) {
    expect.value(manifest).toContain(line);
  }
}

export async function listJarEntries(t: SmokeContext, jar: string): Promise<readonly string[]> {
  const jarTool = await t.tools.jar({ minVersion: 8 });
  const result = await t.cmd(jarTool.command, ["tf", jar]);
  return result.stdout.split(/\r?\n/u).filter(Boolean);
}
