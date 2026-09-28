import { expect, smoke, type SmokeContext } from "smoque";
import { readFile } from "node:fs/promises";

import { packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("Kotlin coverage smoke", { tags: ["compiler", "coverage", "kotlin"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-kotlin-coverage");
  const project = work.path("kotlin-coverage");
  const zolt = await packagedZolt(t);

  await t.step("generates a Kotlin project", async () => {
    await runZolt(t, zolt, [
      "--no-progress", "init", "--cwd", work.path(), "--language", "kotlin", "kotlin-coverage",
    ]);
  });

  await t.step("reports executed Kotlin source lines", async () => {
    const result = await runZolt(t, zolt, [
      "--no-progress", "coverage", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(result.stdout).toContain("Tests found: 1");
    expect.value(result.stdout).toContain("Tests succeeded: 1");
    expect.value(result.stdout).toContain("Coverage reports written");

    await expect.file(`${project}/target/coverage/jacoco.exec`).toExist();
    await expect.file(`${project}/target/coverage/jacoco.xml`).toExist();
    await expect.file(`${project}/target/coverage/html/com.example/Main.kt.html`).toExist();

    const xml = await readFile(`${project}/target/coverage/jacoco.xml`, "utf8");
    expect.value(xml).toContain('<package name="com/example">');
    expect.value(xml).toContain('<class name="com/example/Main" sourcefilename="Main.kt">');
    const greeting = xml.match(
      /<method name="greeting[^"]*"[^>]*>(?<body>(?:(?!<\/method>).)*)<\/method>/su,
    )?.groups?.body ?? "";
    expect.value(greeting).toMatch(/<counter type="LINE" missed="0" covered="[1-9]\d*"\/>/u);

    const html = await readFile(`${project}/target/coverage/html/com.example/Main.kt.html`, "utf8");
    expect.value(html).toContain("<h1>Main.kt</h1>");
    expect.value(html).toMatch(/<span class="fc"[^>]*>\s*internal fun greeting\(\)/u);
  });
});
