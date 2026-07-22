import type { PathRef } from "smoque";
import { cp, readdir, rm } from "node:fs/promises";
import { join } from "node:path";

export async function copyFixture(
  root: PathRef,
  work: PathRef,
  fixtureName: string,
  destinationName = fixtureName,
): Promise<string> {
  const destination = work.path(destinationName);
  await rm(destination, { recursive: true, force: true });
  await cp(root.path("examples", fixtureName), destination, { recursive: true });
  await pruneGeneratedState(destination);
  return destination;
}

async function pruneGeneratedState(directory: string): Promise<void> {
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (hasErrorCode(error, "ENOENT")) {
      return;
    }
    throw error;
  }

  for (const entry of entries) {
    const path = join(directory, entry.name);
    if ((entry.name === "target" || entry.name === ".zolt") && entry.isDirectory()) {
      await rm(path, { recursive: true, force: true });
    } else if (entry.name === "zolt.lock" && entry.isFile()) {
      await rm(path, { force: true });
    } else if (entry.isDirectory()) {
      await pruneGeneratedState(path);
    }
  }
}

function hasErrorCode(error: unknown, code: string): boolean {
  return typeof error === "object"
    && error !== null
    && "code" in error
    && (error as { code?: unknown }).code === code;
}
