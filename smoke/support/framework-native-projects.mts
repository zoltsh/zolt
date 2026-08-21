import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export type NativeDiagnosticFramework = "micronaut" | "quarkus" | "spring-boot";

const FRAMEWORK_CONFIG: Readonly<Record<NativeDiagnosticFramework, readonly string[]>> = {
  "spring-boot": [
    "[platforms]",
    '"org.springframework.boot:spring-boot-dependencies" = "4.0.6"',
    "",
    "[dependencies]",
    '"org.springframework.boot:spring-boot-starter-webmvc" = { managed = true }',
    "",
    "[package]",
    'mode = "spring-boot"',
  ],
  micronaut: [
    "[platforms]",
    '"io.micronaut.platform:micronaut-platform" = "4.10.12"',
    "",
    "[dependencies]",
    '"io.micronaut:micronaut-http-server-netty" = { managed = true }',
    '"io.micronaut:micronaut-runtime" = { managed = true }',
    "",
    "[dependencies.processor]",
    '"io.micronaut:micronaut-inject-java" = { managed = true }',
  ],
  quarkus: [
    "[platforms]",
    '"io.quarkus.platform:quarkus-bom" = "3.33.2"',
    "",
    "[dependencies]",
    '"io.quarkus:quarkus-rest" = { managed = true }',
    "",
    "[package]",
    'mode = "quarkus"',
    'package = "fast-jar"',
  ],
};

export async function writeFrameworkNativeDiagnosticProject(
  root: string,
  framework: NativeDiagnosticFramework,
): Promise<void> {
  await mkdir(root, { recursive: true });
  await writeFile(join(root, "zolt.toml"), [
    "[project]",
    `name = "${framework}-native-diagnostic"`,
    'version = "0.1.0"',
    'group = "com.example"',
    "java = 21",
    'main = "com.example.Main"',
    "",
    ...FRAMEWORK_CONFIG[framework],
    "",
  ].join("\n"), "utf8");
}
