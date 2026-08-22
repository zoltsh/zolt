import { cp, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export const AUTHENTICATED_REPOSITORY_USERNAME_ENV = "ZOLT_INTERNAL_REPO_USERNAME";
export const AUTHENTICATED_REPOSITORY_PASSWORD_ENV = "ZOLT_INTERNAL_REPO_TOKEN";
export const AUTHENTICATED_REPOSITORY_BEARER_ENV = "ZOLT_INTERNAL_REPO_BEARER";

export async function writeAuthenticatedLibrary(
  directory: string,
  greeting = "hello \" + name + \" from authenticated repo",
): Promise<void> {
  const source = join(directory, "src/main/java/com/example/enterprise");
  await mkdir(source, { recursive: true });
  await writeFile(join(directory, "zolt.toml"), [
    "[project]", 'name = "internal-greeting"', 'version = "1.0.0"',
    'group = "com.example.enterprise"', "java = 21", "",
  ].join("\n"), "utf8");
  await writeFile(join(source, "InternalGreeting.java"), [
    "package com.example.enterprise;", "", "public final class InternalGreeting {",
    "    private InternalGreeting() {}",
    `    public static String message(String name) { return "${greeting}"; }`,
    "}", "",
  ].join("\n"), "utf8");
}

export async function installAuthenticatedArtifact(
  repository: string,
  jar: string,
  pomMarker = "default principal",
): Promise<void> {
  const artifact = join(repository, "maven2/com/example/enterprise/internal-greeting/1.0.0");
  await mkdir(artifact, { recursive: true });
  await cp(jar, join(artifact, "internal-greeting-1.0.0.jar"));
  await writeFile(join(artifact, "internal-greeting-1.0.0.pom"), [
    "<project>", "  <modelVersion>4.0.0</modelVersion>",
    "  <groupId>com.example.enterprise</groupId>", "  <artifactId>internal-greeting</artifactId>",
    "  <version>1.0.0</version>", `  <description>${pomMarker}</description>`, "</project>", "",
  ].join("\n"), "utf8");
}

export async function writeAuthenticatedConsumer(directory: string, repositoryUrl: string): Promise<void> {
  await writeConsumer(directory, repositoryUrl, [
    `usernameEnv = "${AUTHENTICATED_REPOSITORY_USERNAME_ENV}"`,
    `passwordEnv = "${AUTHENTICATED_REPOSITORY_PASSWORD_ENV}"`,
  ]);
}

export async function writeBearerAuthenticatedConsumer(directory: string, repositoryUrl: string): Promise<void> {
  await writeConsumer(directory, repositoryUrl, [
    `tokenEnv = "${AUTHENTICATED_REPOSITORY_BEARER_ENV}"`,
  ]);
}

async function writeConsumer(
  directory: string,
  repositoryUrl: string,
  credentialLines: readonly string[],
): Promise<void> {
  const source = join(directory, "src/main/java/com/example/consumer");
  await mkdir(source, { recursive: true });
  await writeFile(join(directory, "zolt.toml"), [
    "[project]", 'name = "authenticated-repository-app"', 'version = "0.1.0"',
    'group = "com.example"', "java = 21", 'main = "com.example.consumer.Main"', "",
    "[repositories]", `internal = { url = "${repositoryUrl}", credentials = "internal-repo" }`, "",
    "[credentials.internal-repo]",
    ...credentialLines,
    "",
    "[dependencies]", '"com.example.enterprise:internal-greeting" = "1.0.0"', "",
  ].join("\n"), "utf8");
  await writeFile(join(source, "Main.java"), [
    "package com.example.consumer;", "", "import com.example.enterprise.InternalGreeting;", "",
    "public final class Main {", "    private Main() {}",
    '    public static void main(String[] args) { System.out.println(InternalGreeting.message(args[0])); }',
    "}", "",
  ].join("\n"), "utf8");
}
