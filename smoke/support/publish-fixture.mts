import { expect, type SmokeContext } from "smoque";
import { cp, mkdir, writeFile } from "node:fs/promises";
import { delimiter, dirname, join } from "node:path";

import { javaCommands } from "./java-tools.mts";

interface PublishedArtifactPaths {
  readonly artifactUploadPath: string;
  readonly pomUploadPath: string;
}

export async function writePublisherFixture(directory: string): Promise<void> {
  const source = join(directory, "src/main/java/com/example/publish");
  await mkdir(source, { recursive: true });
  await writeFile(join(directory, "zolt.toml"), [
    "[project]", 'name = "publisher-lib"', 'version = "1.2.3"', 'group = "com.example.publish"', 'java = "21"', "",
    "[repositories]", 'central = "https://repo.maven.apache.org/maven2"', "", "[dependencies]", "", "[test.dependencies]", "",
    "[publish]", 'releaseRepository = "local-compat"', 'snapshotRepository = "local-compat"', 'artifacts = ["main"]', "",
    "[publish.repositories.local-compat]", 'url = "https://repo.example.test/releases"', "",
  ].join("\n"), "utf8");
  await writeFile(join(source, "Library.java"), [
    "package com.example.publish;", "", "public final class Library {", "    private Library() {}",
    '    public static String message() { return "published-ok"; }', "}", "",
  ].join("\n"), "utf8");
}

export async function installPublishedArtifact(
  repository: string,
  artifact: string,
  pom: string,
  paths: PublishedArtifactPaths,
): Promise<string> {
  const installedArtifact = join(repository, paths.artifactUploadPath);
  await mkdir(dirname(installedArtifact), { recursive: true });
  await cp(artifact, installedArtifact);
  const installedPom = join(repository, paths.pomUploadPath);
  await mkdir(dirname(installedPom), { recursive: true });
  await cp(pom, installedPom);
  return installedArtifact;
}

export async function compilePublishedConsumer(
  t: SmokeContext,
  directory: string,
  artifact: string,
): Promise<void> {
  const source = join(directory, "src/com/example/consumer");
  const classes = join(directory, "classes");
  await mkdir(source, { recursive: true });
  await mkdir(classes, { recursive: true });
  const javaFile = join(source, "Consumer.java");
  await writeFile(javaFile, [
    "package com.example.consumer;", "", "import com.example.publish.Library;", "",
    "public final class Consumer {", "    private Consumer() {}",
    "    public static void main(String[] args) { System.out.println(Library.message()); }", "}", "",
  ].join("\n"), "utf8");
  const java = await javaCommands(t, 21);
  await t.cmd(java.javac, ["-cp", artifact, "-d", classes, javaFile], { cwd: directory });
  const result = await t.cmd(java.java, ["-cp", `${artifact}${delimiter}${classes}`, "com.example.consumer.Consumer"], {
    cwd: directory,
  });
  expect.value(result.stdout.trim()).toBe("published-ok");
}
