import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import {
  copyFixture,
  expectJsonObject,
  expectTestsFound,
  expectTextFile,
  jsonArray,
  jsonString,
  packagedZolt,
  parseJsonObject,
  runZolt,
  type JsonObject,
} from "./support/zolt-smoke.mts";

smoke.suite("Spock test engine smoke", { tags: ["examples", "spock"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-spock");
  const zolt = await packagedZolt(t);

  await t.step("resolves Groovy and runs a Spock spec through JUnit Platform", async () => {
    const project = await copyFixture(root, work, "spock-basic");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTextFile(join(project, "zolt.lock"), {
      contains: ["org.spockframework:spock-core", "org.apache.groovy:groovy", 'scope = "tool-groovy"'],
    });
    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--locked", "--offline", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const audit = await runZolt(t, zolt, [
      "--no-progress", "classpath", "audit", "--format", "json",
      "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expectGroovyAuditRows(t, audit.stdout);

    await expectTestsFound(t, zolt, 1, [
      "--no-progress",
      "test",
      "--no-build-cache",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    await expect.file(join(project, "target/test-classes/com/example/CalculatorSpec.class")).toExist();
  });
});

function expectGroovyAuditRows(t: SmokeContext, text: string): void {
  const report = parseJsonObject(t, text, "Spock classpath audit");
  const packages = jsonArray(t, report, "packages", "Spock classpath audit");
  findPackage(t, packages, "org.apache.groovy:groovy:4.0.22", "test");

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
    const entry = expectJsonObject(t, value, `Spock classpath audit.packages[${index}]`);
    if (entry.coordinate === coordinate && entry.scope === scope) {
      return entry;
    }
  }
  t.fail(`Spock classpath audit should contain ${coordinate} in scope ${scope}.`);
}
