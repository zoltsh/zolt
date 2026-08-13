import { appendFile, readFile } from "node:fs/promises";
import { join } from "node:path";

export interface MavenArtifact {
  readonly group: string;
  readonly artifact: string;
  readonly version: string;
  readonly extension?: string;
}

export async function mavenArtifactPath(
  cacheRoot: string,
  lockfile: string,
  artifact: MavenArtifact,
): Promise<string> {
  const content = await readFile(lockfile, "utf8");
  const coordinate = `${artifact.group}:${artifact.artifact}`;
  const entry = content
    .split(/\n(?=\[\[package\]\]\n)/u)
    .find((block) => field(block, "id") === coordinate && field(block, "version") === artifact.version);
  if (entry === undefined) {
    throw new Error(`Lockfile has no package entry for ${coordinate}:${artifact.version}`);
  }
  const pathField = artifact.extension === "pom" ? "pom" : "jar";
  const relativePath = field(entry, pathField);
  if (relativePath === undefined) {
    throw new Error(`Lockfile package ${coordinate}:${artifact.version} has no ${pathField} path`);
  }
  return join(cacheRoot, ...relativePath.split("/"));
}

export async function corruptMavenArtifact(
  cacheRoot: string,
  lockfile: string,
  artifact: MavenArtifact,
): Promise<string> {
  const path = await mavenArtifactPath(cacheRoot, lockfile, artifact);
  await appendFile(path, "\ncorrupted by cache integrity smoke\n", "utf8");
  return path;
}

function field(block: string, name: string): string | undefined {
  const prefix = `${name} = \"`;
  const line = block.split("\n").find((candidate) => candidate.startsWith(prefix));
  return line?.slice(prefix.length, -1);
}
