import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export type NativeDiagnosticFramework = "micronaut" | "quarkus" | "spring-boot";

const FRAMEWORK_CONFIG: Readonly<Record<NativeDiagnosticFramework, readonly string[]>> = {
  "spring-boot": [
    "[platforms]",
    '"org.springframework.boot:spring-boot-dependencies" = "4.0.6"',
    "",
    "[dependencies]",
    '"org.springframework.boot:spring-boot-starter-webmvc" = {}',
    "",
    "[package]",
    'mode = "spring-boot"',
  ],
  micronaut: [
    "[platforms]",
    '"io.micronaut.platform:micronaut-platform" = "4.10.12"',
    "",
    "[dependencies]",
    '"io.micronaut:micronaut-http-server-netty" = {}',
    '"io.micronaut:micronaut-runtime" = {}',
    "",
    "[annotationProcessors]",
    '"io.micronaut:micronaut-inject-java" = {}',
  ],
  quarkus: [
    "[platforms]",
    '"io.quarkus.platform:quarkus-bom" = "3.33.2"',
    "",
    "[dependencies]",
    '"io.quarkus:quarkus-rest" = {}',
    "",
    "[framework.quarkus]",
    "enabled = true",
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
    'java = "21"',
    'main = "com.example.Main"',
    "",
    "[repositories]",
    'central = "https://repo.maven.apache.org/maven2"',
    "",
    ...FRAMEWORK_CONFIG[framework],
    "",
  ].join("\n"), "utf8");
}
