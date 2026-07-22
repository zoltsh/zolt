import { access, mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { createHash } from "node:crypto";

export async function singleJar(directory: string): Promise<string> {
  const jars = (await readdir(directory))
    .filter((name) => name.endsWith(".jar"))
    .filter((name) => !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"))
    .sort();
  if (jars.length !== 1) {
    throw new Error(`Expected exactly one jar under ${directory}, found ${jars.length}: ${jars.join(", ")}`);
  }
  return join(directory, jars[0]);
}

export async function writeOutput(path: string): Promise<void> {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, "output\n", "utf8");
}

export async function sha256File(path: string): Promise<string> {
  return createHash("sha256").update(await readFile(path)).digest("hex");
}

export async function pathExists(path: string): Promise<boolean> {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}
