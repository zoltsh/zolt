import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export async function writeSpringBootNativeAotProject(root: string): Promise<void> {
  await mkdir(join(root, "src/main/java/com/example"), { recursive: true });
  await writeFile(join(root, "zolt.toml"), [
    "[project]",
    'name = "spring-boot-native-aot-canary"',
    'version = "0.1.0"',
    'group = "com.example"',
    'java = "21"',
    'main = "com.example.Main"',
    "",
    "[repositories]",
    'central = "https://repo.maven.apache.org/maven2"',
    "",
    "[platforms]",
    '"org.springframework.boot:spring-boot-dependencies" = "3.3.6"',
    "",
    "[dependencies]",
    '"org.springframework.boot:spring-boot" = {}',
    "",
    "[framework.springBoot.native]",
    "enabled = true",
    "",
    "[native]",
    'imageName = "spring-boot-native-aot-canary"',
    'args = ["--no-fallback", "--native-image-info"]',
    "",
  ].join("\n"), "utf8");
  await writeFile(join(root, "src/main/java/com/example/Main.java"), [
    "package com.example;",
    "",
    "import org.springframework.boot.SpringApplication;",
    "",
    "public final class Main {",
    "    private Main() {}",
    "    public static void main(String[] args) { SpringApplication.run(Main.class, args); }",
    "}",
    "",
  ].join("\n"), "utf8");
}
