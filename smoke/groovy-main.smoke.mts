import { expect, smoke, type SmokeContext } from "smoque";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

import {
  copyFixture,
  expectCommandFailureContains,
  expectJsonObject,
  expectTextFile,
  jsonArray,
  jsonString,
  packagedZolt,
  parseJsonObject,
  runZolt,
  type JsonObject,
} from "./support/zolt-smoke.mts";

smoke.suite("Groovy main joint compilation smoke", { tags: ["examples", "groovy"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-groovy-main");
  const zolt = await packagedZolt(t);

  await t.step("builds and runs circular Java and Groovy main sources", async () => {
    const project = await copyFixture(root, work, "groovy-main");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTextFile(join(project, "zolt.lock"), {
      contains: ["org.apache.groovy:groovy", "4.0.22", 'scope = "compile"', 'scope = "tool-groovy"'],
    });
    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--locked", "--offline", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const audit = await runZolt(t, zolt, [
      "--no-progress", "classpath", "audit", "--format", "json",
      "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expectGroovyAuditRows(t, audit.stdout, "compile");

    await runZolt(t, zolt, [
      "--no-progress", "build", "--offline", "--no-build-cache",
      "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    await expect.file(join(project, "target/classes/com/example/JavaGreeting.class")).toExist();
    await expect.file(join(project, "target/classes/com/example/GroovyGreeting.class")).toExist();

    const result = await runZolt(t, zolt, [
      "--no-progress", "run", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(result.stdout).toContain("Hello from joint Java/Groovy compilation");
  });

  await t.step("rejects compiler/runtime version skew before touching outputs", async () => {
    const project = await copyFixture(root, work, "groovy-main", "groovy-main-version-skew");
    const manifest = join(project, "zolt.toml");
    await writeFile(
      manifest,
      (await readFile(manifest, "utf8"))
        .replace('"org.apache.groovy:groovy" = "4.0.22"', '"org.apache.groovy:groovy" = "4.0.21"'),
      "utf8",
    );
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);

    const output = join(project, "target/classes");
    const preserved = join(output, "preserved-before-skew.txt");
    await mkdir(output, { recursive: true });
    await writeFile(preserved, "preserve me\n", "utf8");

    await expectCommandFailureContains(
      t,
      zolt,
      [
        "--no-progress", "build", "--offline", "--no-build-cache",
        "--cwd", project, "--cache-root", zolt.cacheRoot,
      ],
      "ordinary runtime version `4.0.21` does not match configured version `4.0.22`",
    );
    await expect.file(preserved).toExist();
    await expect.file(join(output, "com/example/JavaGreeting.class")).notToExist();
    await expect.file(join(output, "com/example/GroovyGreeting.class")).notToExist();
  });
});

function expectGroovyAuditRows(t: SmokeContext, text: string, ordinaryScope: string): void {
  const report = parseJsonObject(t, text, "Groovy classpath audit");
  const packages = jsonArray(t, report, "packages", "Groovy classpath audit");
  const runtime = findPackage(t, packages, "org.apache.groovy:groovy:4.0.22", ordinaryScope);
  expect.value(jsonString(t, runtime, "disposition", "ordinary Groovy package"))
    .toBe("package-default");

  const compiler = findPackage(t, packages, "org.apache.groovy:groovy:4.0.22", "tool-groovy");
  expect.value(compiler.packageDefault).toBe(false);
  expect.value(jsonString(t, compiler, "disposition", "Groovy compiler package"))
    .toBe("groovy-compiler-tooling-only");
  expect.value(JSON.stringify(jsonArray(t, compiler, "lanes", "Groovy compiler package")))
    .toBe('["tool-groovy"]');
}

function findPackage(
  t: SmokeContext,
  packages: readonly unknown[],
  coordinate: string,
  scope: string,
): JsonObject {
  for (const [index, value] of packages.entries()) {
    const entry = expectJsonObject(t, value, `Groovy classpath audit.packages[${index}]`);
    if (entry.coordinate === coordinate && entry.scope === scope) {
      return entry;
    }
  }
  t.fail(`Groovy classpath audit should contain ${coordinate} in scope ${scope}.`);
}
