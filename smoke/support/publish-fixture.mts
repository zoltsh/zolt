import { expect, type SmokeContext } from "smoque";
import { cp, mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { delimiter, dirname, join } from "node:path";

import { javaCommands } from "./java-tools.mts";

/** The placeholder publish URL the example fixtures declare; smokes retarget it at a local server. */
export const FIXTURE_PUBLISH_URL = "https://repo.example.test/releases";

/** The placeholder repository the platform-family consumer resolves from. */
export const FIXTURE_CONSUMER_URL = "https://fixture.example.test/repo";

interface PublishedArtifactPaths {
  readonly artifactUploadPath: string;
  readonly pomUploadPath: string;
}

export async function writePublisherFixture(directory: string): Promise<void> {
  const source = join(directory, "src/main/java/com/example/publish");
  await mkdir(source, { recursive: true });
  await writeFile(join(directory, "zolt.toml"), [
    "[project]", 'name = "publisher-lib"', 'version = "1.2.3"', 'group = "com.example.publish"', "java = 21", "",
    "[publish]", 'release = "local-compat"', 'snapshot = "local-compat"', "",
    "[publish.repositories.local-compat]", 'url = "https://repo.example.test/releases"', "",
  ].join("\n"), "utf8");
  await writeFile(join(source, "Library.java"), [
    "package com.example.publish;", "", "public final class Library {", "    private Library() {}",
    '    public static String message() { return "published-ok"; }', "}", "",
  ].join("\n"), "utf8");
}

/** A standalone library carrying the metadata Maven Central requires, minus the signing configuration. */
export async function writeCentralCandidateFixture(directory: string, repositoryUrl: string): Promise<void> {
  const source = join(directory, "src/main/java/com/example/central");
  await mkdir(source, { recursive: true });
  await writeFile(join(directory, "zolt.toml"), [
    "[project]", 'name = "central-candidate"', 'version = "1.0.0"',
    'group = "com.example.central"', "java = 21",
    'description = "A library staged for a Maven Central readiness review."',
    'url = "https://example.test/central-candidate"',
    'license = { id = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0.txt" }',
    "",
    "[project.scm]",
    'url = "https://github.com/example/central-candidate"',
    'connection = "scm:git:https://github.com/example/central-candidate.git"',
    "",
    "[project.developers.maintainer]",
    'name = "Example Maintainer"',
    'email = "maintainer@example.test"',
    "",
    "[package]", "sources = true", "javadoc = true", "",
    "[publish]", 'release = "local"', "",
    "[publish.repositories.local]", `url = "${repositoryUrl}"`, "",
  ].join("\n"), "utf8");
  await writeFile(join(source, "Library.java"), [
    "package com.example.central;", "", "/** Published by the workspace publish smoke. */",
    "public final class Library {", "    private Library() {}",
    '    public static String message() { return "central-ok"; }', "}", "",
  ].join("\n"), "utf8");
}

/** Retargets every `zolt.toml` under a copied fixture from a placeholder URL onto a live local server. */
export async function retargetFixtureUrl(directory: string, from: string, to: string): Promise<void> {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      await retargetFixtureUrl(path, from, to);
    } else if (entry.name === "zolt.toml") {
      const content = await readFile(path, "utf8");
      if (content.includes(from)) {
        await writeFile(path, content.replaceAll(from, to), "utf8");
      }
    }
  }
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
