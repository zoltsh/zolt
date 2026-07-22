import { appendFile } from "node:fs/promises";
import { join } from "node:path";

export interface MavenArtifact {
  readonly group: string;
  readonly artifact: string;
  readonly version: string;
  readonly extension?: string;
}

export function mavenArtifactPath(cacheRoot: string, artifact: MavenArtifact): string {
  const filename = `${artifact.artifact}-${artifact.version}.${artifact.extension ?? "jar"}`;
  return join(cacheRoot, ...artifact.group.split("."), artifact.artifact, artifact.version, filename);
}

export async function corruptMavenArtifact(cacheRoot: string, artifact: MavenArtifact): Promise<string> {
  const path = mavenArtifactPath(cacheRoot, artifact);
  await appendFile(path, "\ncorrupted by cache integrity smoke\n", "utf8");
  return path;
}
