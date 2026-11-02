import { expect, smoke, type SmokeContext } from "smoque";
import { existsSync } from "node:fs";
import { mkdir, readFile, rm, stat, utimes, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { isolatedUserGlobalHome } from "./support/user-global-config.mts";
import { packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("rebuild correctness smoke", { tags: ["build", "rebuild", "stable"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-rebuild");
  const zolt = await packagedZolt(t);
  const java = await t.tools.java({ minVersion: 21 });
  const project = work.path("project with spaces Ω");
  const source = join(project, "src/main/java/sample/Main.java");
  const helper = join(project, "src/main/java/sample/Helper.java");
  const resources = join(project, "src/main/resources");
  const output = join(project, "target/classes");
  const mainClass = join(output, "sample/Main.class");
  const innerClass = join(output, "sample/Main$Nested.class");
  const home = await isolatedUserGlobalHome(t, work, "user-home");
  await home.write([
    "version = 1", "[buildCache]", "enabled = true",
    `dir = "${join(home.path, "build-cache")}"`, "maxSizeMb = 64",
  ]);
  const command = async (name: string, args: string[] = [], check = true) => await runZolt(t, zolt, [
    "--no-progress", name, "--cwd", project,
    ...(name === "clean" ? [] : ["--cache-root", zolt.cacheRoot]), ...args,
  ], { env: home.env, check });
  const run = async (text: string) => {
    const result = await t.cmd(java.command, ["-cp", output, "sample.Main"], { cwd: project });
    expect.value(result.stdout).toContain(text);
  };
  const helperSource = 'package sample; class Helper { static String value() { return "helper"; } }\n';

  await t.step("builds a cold project and reuses unchanged output", async () => {
    await mkdir(join(project, "src/main/java/sample"), { recursive: true });
    await mkdir(resources, { recursive: true });
    await writeFile(join(project, "zolt.toml"), [
      "[project]", 'name = "rebuild-probe"', 'version = "1.0.0"',
      'group = "sample"', "java = 21", 'main = "sample.Main"', "",
    ].join("\n"));
    await writeFile(source, 'package sample; public class Main { static class Nested {} public static void main(String[] args) { System.out.println("value-v1:" + Helper.value()); } }\n');
    // Use an exactly representable timestamp so preserving it cannot accidentally
    // invalidate a metadata-only cache through sub-millisecond truncation.
    const sourceTime = new Date("2026-01-01T00:00:00Z");
    await utimes(source, sourceTime, sourceTime);
    await writeFile(helper, helperSource);
    await writeFile(join(resources, "settings.txt"), "old resource\n");
    await command("resolve");
    await command("build");
    await run("value-v1:helper");
    const before = (await stat(mainClass)).mtimeMs;
    await command("build", ["--offline"]);
    expect.value((await stat(mainClass)).mtimeMs).toBe(before);
  });

  await t.step("recompiles same-size source edits with preserved timestamps", async () => {
    const before = await stat(source);
    await writeFile(source, (await readFile(source, "utf8")).replace("value-v1", "value-v2"));
    await utimes(source, before.atime, before.mtime);
    await command("build", ["--offline"]);
    await run("value-v2:helper");
  });

  await t.step("repairs missing, damaged, and unexpected class output", async () => {
    await rm(innerClass);
    await command("build");
    await expect.file(innerClass).toExist();
    await writeFile(mainClass, "damaged");
    await writeFile(join(output, "Unexpected.class"), "stale");
    await command("build");
    expect.value(existsSync(join(output, "Unexpected.class"))).toBe(false);
    await run("value-v2:helper");
  });

  await t.step("removes deleted resources without recompiling Java", async () => {
    const before = (await stat(mainClass)).mtimeMs;
    await rm(join(resources, "settings.txt"));
    await mkdir(join(resources, "settings.txt"));
    await writeFile(join(resources, "settings.txt/new.txt"), "new resource\n");
    await command("build");
    expect.value(await readFile(join(output, "settings.txt/new.txt"), "utf8")).toBe("new resource\n");
    expect.value((await stat(mainClass)).mtimeMs).toBe(before);
  });

  await t.step("rejects missing dependencies and recovers without cleaning", async () => {
    await rm(helper);
    const failure = await command("build", [], false);
    expect.value(failure.exitCode === 0).toBeFalsy();
    expect.value(failure.stdout + failure.stderr).toContain("Helper");
    await writeFile(helper, helperSource);
    await command("build");
    await run("value-v2:helper");
  });

  await t.step("restores cached output and retains resource ownership", async () => {
    await command("clean");
    const restored = await command("build", ["--offline"]);
    expect.value(restored.stdout).toContain("Restored");
    await run("value-v2:helper");
    await rm(join(resources, "settings.txt"), { recursive: true });
    await command("build");
    expect.value(existsSync(join(output, "settings.txt"))).toBe(false);
    await command("package");
    const packaged = await command("run-package");
    expect.value(packaged.stdout).toContain("value-v2:helper");
  });
});
