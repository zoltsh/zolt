import { cp, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export const UPDATE_REPOSITORY_USERNAME_ENV = "ZOLT_SMOKE_UPDATE_REPO_USERNAME";
export const UPDATE_REPOSITORY_PASSWORD_ENV = "ZOLT_SMOKE_UPDATE_REPO_TOKEN";

export const UPDATE_GROUP = "com.example.smoke";
export const UPDATE_ARTIFACT = "widget";
export const UPDATE_COORDINATE = `${UPDATE_GROUP}:${UPDATE_ARTIFACT}`;
export const UPDATE_OLD_VERSION = "1.0.0";
export const UPDATE_NEW_VERSION = "1.2.0";

/** The library whose packaged jar is installed into the hosted repository at both versions. */
export async function writeUpdateLibrary(directory: string): Promise<void> {
  const source = join(directory, "src/main/java/com/example/smoke");
  await mkdir(source, { recursive: true });
  await writeFile(join(directory, "zolt.toml"), [
    "[project]", `name = "${UPDATE_ARTIFACT}"`, `version = "${UPDATE_NEW_VERSION}"`,
    `group = "${UPDATE_GROUP}"`, 'java = "21"', "", "[dependencies]", "",
  ].join("\n"), "utf8");
  await writeFile(join(source, "Widget.java"), [
    "package com.example.smoke;", "", "public final class Widget {", "    private Widget() {}",
    '    public static String name() { return "widget"; }', "}", "",
  ].join("\n"), "utf8");
}

/** Installs the same jar at every version, plus the maven-metadata.xml that version discovery reads. */
export async function installVersionedArtifact(
  repository: string,
  jar: string,
  versions: readonly string[],
): Promise<void> {
  const base = join(repository, "maven2", ...UPDATE_GROUP.split("."), UPDATE_ARTIFACT);
  for (const version of versions) {
    const directory = join(base, version);
    await mkdir(directory, { recursive: true });
    await cp(jar, join(directory, `${UPDATE_ARTIFACT}-${version}.jar`));
    await writeFile(join(directory, `${UPDATE_ARTIFACT}-${version}.pom`), [
      "<project>", "  <modelVersion>4.0.0</modelVersion>",
      `  <groupId>${UPDATE_GROUP}</groupId>`, `  <artifactId>${UPDATE_ARTIFACT}</artifactId>`,
      `  <version>${version}</version>`, "</project>", "",
    ].join("\n"), "utf8");
  }
  await mkdir(base, { recursive: true });
  await writeFile(join(base, "maven-metadata.xml"), [
    '<?xml version="1.0" encoding="UTF-8"?>',
    "<metadata>",
    `  <groupId>${UPDATE_GROUP}</groupId>`,
    `  <artifactId>${UPDATE_ARTIFACT}</artifactId>`,
    "  <versioning>",
    "    <versions>",
    ...versions.map((version) => `      <version>${version}</version>`),
    "    </versions>",
    "  </versioning>",
    "</metadata>",
    "",
  ].join("\n"), "utf8");
}

/** A consumer pinned to the older version, resolving only from the hosted repository. */
export async function writeUpdateConsumer(directory: string, repositoryUrl: string): Promise<void> {
  const source = join(directory, "src/main/java/com/example/consumer");
  await mkdir(source, { recursive: true });
  await writeFile(join(directory, "zolt.toml"), [
    "[project]", 'name = "update-consumer"', 'version = "0.1.0"',
    'group = "com.example"', 'java = "21"', "",
    "[repositories]", `smoke = { url = "${repositoryUrl}", credentials = "smoke-repo" }`, "",
    "[repositoryCredentials.smoke-repo]",
    `usernameEnv = "${UPDATE_REPOSITORY_USERNAME_ENV}"`,
    `passwordEnv = "${UPDATE_REPOSITORY_PASSWORD_ENV}"`,
    "",
    "[dependencies]", `"${UPDATE_COORDINATE}" = "${UPDATE_OLD_VERSION}"`, "",
  ].join("\n"), "utf8");
  await writeFile(join(source, "Main.java"), [
    "package com.example.consumer;", "", "import com.example.smoke.Widget;", "",
    "public final class Main {", "    private Main() {}",
    "    public static void main(String[] args) { System.out.println(Widget.name()); }", "}", "",
  ].join("\n"), "utf8");
}
