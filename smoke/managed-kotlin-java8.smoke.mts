import { expect, smoke, type SmokeContext } from "smoque";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { classFileMajor } from "./support/java-project.mts";
import {
  expectJsonObject,
  jsonString,
  packagedZolt,
  parseJsonObject,
  runZolt,
} from "./support/zolt-smoke.mts";

smoke.suite(
  "managed Java 8 Kotlin smoke",
  { tags: ["compiler", "java-release", "kotlin", "toolchain"] },
  async (t: SmokeContext) => {
    if (process.platform === "darwin" && process.arch === "arm64") {
      t.skip("Adoptium does not publish Temurin 8 for macos-aarch64.");
    }
    const work = await t.tempDir("zolt-managed-kotlin-java8");
    const project = work.path("project");
    const zolt = await packagedZolt(t);
    await writeProject(project);

    await t.step("resolves and installs the managed Java 8 toolchain", async () => {
      await runZolt(t, zolt, [
        "--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot,
      ]);
      await runZolt(t, zolt, [
        "--no-progress", "toolchain", "sync", "--directory", project,
      ], { timeout: "10m" });

      const status = await runZolt(t, zolt, [
        "--no-progress", "toolchain", "status", "--json", "--directory", project,
      ]);
      const report = parseJsonObject(t, status.stdout, "managed Java 8 toolchain status");
      const request = expectJsonObject(t, report.request, "toolchain status.request");
      const resolved = expectJsonObject(t, report.resolved, "toolchain status.resolved");

      expect.value(report.ok).toBe(true);
      expect.value(jsonString(t, request, "version", "toolchain status.request")).toBe("8");
      expect.value(jsonString(t, request, "source", "toolchain status.request"))
        .toBe("[toolchain.java]");
      expect.value(jsonString(t, request, "distribution", "toolchain status.request"))
        .toBe("temurin");
      expect.value(jsonString(t, request, "policy", "toolchain status.request"))
        .toBe("require-managed");
      expect.value(jsonString(t, resolved, "source", "toolchain status.resolved"))
        .toBe("managed");
      const runtimeVersion = jsonString(t, resolved, "version", "toolchain status.resolved");
      expect.value(runtimeVersion).toMatch(/^(?:1\.)?8(?:[._+-]|$)/u);
    });

    await t.step("compiles mixed main and Kotlin test sources with locked inputs for Java 8", async () => {
      await runZolt(t, zolt, [
        "--no-progress", "resolve", "--locked", "--offline",
        "--cwd", project, "--cache-root", zolt.cacheRoot,
      ]);
      await runZolt(t, zolt, [
        "--no-progress", "build", "--offline", "--no-build-cache",
        "--cwd", project, "--cache-root", zolt.cacheRoot,
      ]);
      await runZolt(t, zolt, [
        "--no-progress", "test", "--compile-only", "--no-build-cache",
        "--cwd", project, "--cache-root", zolt.cacheRoot,
      ]);

      for (const classFile of [
        "target/classes/com/example/ApplicationKt.class",
        "target/classes/com/example/KotlinApi.class",
        "target/classes/com/example/JavaBridge.class",
        "target/test-classes/com/example/ManagedKotlinTest.class",
      ]) {
        expect.value(await classFileMajor(join(project, classFile))).toBe(52);
      }

      const run = await runZolt(t, zolt, [
        "--no-progress", "run",
        "--cwd", project, "--cache-root", zolt.cacheRoot,
      ]);
      expect.value(run.stdout).toContain("managed-kotlin-java8");
      expect.value(run.stdout).toMatch(/^runtime-java=(?:1\.)?8(?:[._+-]|$)/mu);
    });
  },
);

async function writeProject(project: string): Promise<void> {
  const kotlinMain = join(project, "src/main/kotlin/com/example");
  const javaMain = join(project, "src/main/java/com/example");
  const kotlinTest = join(project, "src/test/kotlin/com/example");
  await mkdir(kotlinMain, { recursive: true });
  await mkdir(javaMain, { recursive: true });
  await mkdir(kotlinTest, { recursive: true });

  await writeFile(join(project, "zolt.toml"), [
    "[project]",
    'name = "managed-kotlin-java8"',
    'version = "0.1.0"',
    'group = "com.example"',
    "java = 8",
    'main = "com.example.ApplicationKt"',
    "",
    "[toolchain.java]",
    "version = 8",
    'distribution = "temurin"',
    "features = []",
    'policy = "require-managed"',
    "",
    "[toolchain.kotlin]",
    'version = "2.2.0"',
    "",
    "[build]",
    'sources = ["src/main/kotlin", "src/main/java"]',
    "",
    "[test.sources]",
    'kotlin = ["src/test/kotlin"]',
    "",
    "[dependencies]",
    '"org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"',
    "",
  ].join("\n"), "utf8");

  await writeFile(join(kotlinMain, "Application.kt"), [
    "package com.example",
    "",
    "object KotlinApi {",
    "    @JvmStatic",
    '    fun word(): String = "kotlin-java8"',
    "",
    '    internal fun internalWord(): String = "internal-java8"',
    "}",
    "",
    "fun main() {",
    "    println(JavaBridge.message())",
    '    println("runtime-java=" + System.getProperty("java.version"))',
    "}",
    "",
  ].join("\n"), "utf8");

  await writeFile(join(javaMain, "JavaBridge.java"), [
    "package com.example;",
    "",
    "public final class JavaBridge {",
    "    private JavaBridge() {}",
    "",
    "    public static String message() {",
    '        return "managed-" + KotlinApi.word();',
    "    }",
    "}",
    "",
  ].join("\n"), "utf8");

  await writeFile(join(kotlinTest, "ManagedKotlinTest.kt"), [
    "package com.example",
    "",
    "class ManagedKotlinTest {",
    "    fun value(): String = KotlinApi.internalWord() + JavaBridge.message()",
    "}",
    "",
  ].join("\n"), "utf8");
}
