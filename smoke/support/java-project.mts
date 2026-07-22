import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

export interface JavaProjectSpec {
  readonly java: number;
  readonly name: string;
  readonly source: string;
}

export async function writeJavaProject(directory: string, spec: JavaProjectSpec): Promise<void> {
  const sourceDirectory = join(directory, "src/main/java/com/example");
  await mkdir(sourceDirectory, { recursive: true });
  await writeFile(join(directory, "zolt.toml"), [
    "[project]",
    `name = "${spec.name}"`,
    'version = "0.1.0"',
    'group = "com.example"',
    `java = "${spec.java}"`,
    'main = "com.example.Main"',
    "",
    "[dependencies]",
    "",
  ].join("\n"), "utf8");
  await writeFile(join(sourceDirectory, "Main.java"), spec.source, "utf8");
}

export async function classFileMajor(path: string): Promise<number> {
  const content = await readFile(path);
  if (content.length < 8 || content.readUInt32BE(0) !== 0xcafebabe) {
    throw new Error(`Expected a Java class file at ${path}.`);
  }
  return content.readUInt16BE(6);
}
