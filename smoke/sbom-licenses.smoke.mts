import { expect, smoke, type SmokeContext } from "smoque";
import { appendFile, readFile } from "node:fs/promises";
import { join } from "node:path";

import {
  expectJsonObject,
  findJsonObjectByString,
  jsonArray,
  jsonNumber,
  jsonString,
  parseJsonObject,
} from "./support/json.mts";
import { copyFixture, expectTextFile, packagedZolt, runZolt, sha256File } from "./support/zolt-smoke.mts";

const GUAVA_PURL = "pkg:maven/com.google.guava/guava@33.4.0-jre?type=jar";
const SOURCE_DATE_EPOCH = "1577836800";

smoke.suite("SBOM and license reporting smoke", { tags: ["supply-chain", "enterprise"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-sbom-licenses");
  const zolt = await packagedZolt(t);

  const project = await copyFixture(root, work, "hello-zolt", "supply-chain-app");
  await t.step("resolves the fixture so cached POMs back the reports", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
  });

  await t.step("emits a CycloneDX 1.5 document naming resolved dependencies", async () => {
    const result = await runZolt(t, zolt, [
      "--no-progress", "sbom", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const document = parseJsonObject(t, result.stdout, "zolt sbom output");
    expect.value(jsonString(t, document, "bomFormat", "sbom")).toBe("CycloneDX");
    expect.value(jsonString(t, document, "specVersion", "sbom")).toBe("1.5");
    expect.value(jsonNumber(t, document, "version", "sbom")).toBe(1);
    expect.value(jsonString(t, document, "serialNumber", "sbom")).toMatch(/^urn:uuid:[0-9a-f-]{36}$/u);

    const components = jsonArray(t, document, "components", "sbom");
    const purls = components.map((component, index) =>
      jsonString(t, expectJsonObject(t, component, `sbom.components[${index}]`), "purl", "component"));
    expect.value(purls).toContain(GUAVA_PURL);
  });

  await t.step("produces byte-identical output across runs and pinned timestamps", async () => {
    const first = join(work.path("supply-chain-app"), "target/sbom-first.json");
    const second = join(work.path("supply-chain-app"), "target/sbom-second.json");
    const sbomArgs = ["--no-progress", "sbom", "--cwd", project, "--cache-root", zolt.cacheRoot];
    await runZolt(t, zolt, [...sbomArgs, "--output", first]);
    await runZolt(t, zolt, [...sbomArgs, "--output", second]);
    expect.value(await sha256File(second)).toBe(await sha256File(first));
    await expectTextFile(first, { excludes: ["timestamp"] });

    const pinned = join(work.path("supply-chain-app"), "target/sbom-pinned.json");
    const pinnedAgain = join(work.path("supply-chain-app"), "target/sbom-pinned-again.json");
    const epoch = { env: { SOURCE_DATE_EPOCH } };
    await runZolt(t, zolt, [...sbomArgs, "--output", pinned], epoch);
    await runZolt(t, zolt, [...sbomArgs, "--output", pinnedAgain], epoch);
    expect.value(await sha256File(pinnedAgain)).toBe(await sha256File(pinned));
    await expectTextFile(pinned, { contains: ['"timestamp": "2020-01-01T00:00:00Z"'] });

    const bare = parseJsonObject(t, await readFile(first, "utf8"), "default SBOM");
    const stamped = parseJsonObject(t, await readFile(pinned, "utf8"), "pinned SBOM");
    expect.value(jsonString(t, stamped, "serialNumber", "pinned SBOM"))
      .toBe(jsonString(t, bare, "serialNumber", "default SBOM"));
  });

  await t.step("reports SPDX identifiers, JSON groups, and a notices file", async () => {
    const text = await runZolt(t, zolt, [
      "--no-progress", "licenses", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(text.stdout).toMatch(/^Apache-2\.0 \(\d+\)$/mu);
    expect.value(text.stdout).toContain("com.google.guava:guava:33.4.0-jre");

    const notices = join(work.path("supply-chain-app"), "target/THIRD_PARTY.txt");
    const json = await runZolt(t, zolt, [
      "--no-progress", "licenses", "--format", "json", "--notices", notices,
      "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    const report = parseJsonObject(t, json.stdout, "zolt licenses --format json output");
    expect.value(jsonNumber(t, report, "schemaVersion", "licenses")).toBe(1);
    expect.value(jsonString(t, report, "command", "licenses")).toBe("licenses");
    const groups = jsonArray(t, report, "groups", "licenses");
    const licenses = groups.map((group, index) =>
      jsonString(t, expectJsonObject(t, group, `licenses.groups[${index}]`), "license", "group"));
    expect.value(licenses).toContain("Apache-2.0");

    await expectTextFile(notices, {
      contains: ["THIRD-PARTY SOFTWARE NOTICES", "com.google.guava:guava:33.4.0-jre", "Apache-2.0"],
    });
  });

  await t.step("fails the license policy check on a denied license and passes a permissive one", async () => {
    const denied = await copyFixture(root, work, "hello-zolt", "license-denied");
    await appendFile(join(denied, "zolt.toml"), [
      "", "[dependencyPolicy.licenses]", 'deny = ["Apache-2.0"]', 'unknown = "fail"', "",
    ].join("\n"), "utf8");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", denied, "--cache-root", zolt.cacheRoot]);

    // `zolt licenses` reports the policy verdict without enforcing it: annotated entries, a summary,
    // and a pointer at the command that does enforce. It still exits 0.
    const annotated = await runZolt(t, zolt, [
      "--no-progress", "licenses", "--cwd", denied, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(annotated.exitCode).toBe(0);
    expect.value(annotated.stdout).toContain(
      "com.google.guava:guava:33.4.0-jre  [denied] denied by [dependencyPolicy.licenses].deny",
    );
    expect.value(annotated.stdout).toMatch(/^License policy: \d+ denied, \d+ unknown of \d+ dependencies\.$/mu);
    expect.value(annotated.stdout).toContain("Next: run `zolt check --check license-policy` to enforce it.");

    const annotatedJson = await runZolt(t, zolt, [
      "--no-progress", "licenses", "--format", "json", "--cwd", denied, "--cache-root", zolt.cacheRoot,
    ]);
    const annotatedReport = parseJsonObject(t, annotatedJson.stdout, "annotated zolt licenses JSON");
    const annotatedGroups = jsonArray(t, annotatedReport, "groups", "annotated licenses");
    const apache = findJsonObjectByString(t, annotatedGroups, "license", "Apache-2.0", "annotated licenses.groups");
    const guava = findJsonObjectByString(
      t,
      jsonArray(t, apache, "components", "Apache-2.0 group"),
      "coordinate",
      "com.google.guava:guava:33.4.0-jre",
      "Apache-2.0 components",
    );
    const componentPolicy = expectJsonObject(t, guava["policy"], "guava.policy");
    expect.value(jsonString(t, componentPolicy, "status", "component policy")).toBe("denied");
    expect.value(jsonString(t, componentPolicy, "reason", "component policy"))
      .toContain("[dependencyPolicy.licenses].deny");
    const policy = expectJsonObject(t, annotatedReport["licensePolicy"], "licenses.licensePolicy");
    expect.value(jsonString(t, policy, "enforcedBy", "licensePolicy")).toBe("zolt check --check license-policy");

    // Enforcement stays in `zolt check --check license-policy`, which still fails.
    const failure = await runZolt(t, zolt, [
      "--no-progress", "check", "--check", "license-policy", "--cwd", denied, "--cache-root", zolt.cacheRoot,
    ], { check: false });
    expect.value(failure.exitCode).toBe(1);
    expect.value(failure.stdout).toContain(
      "error license-policy com.google.guava:guava:33.4.0-jre Apache-2.0 — denied by [dependencyPolicy.licenses].deny",
    );
    expect.value(failure.stdout).toContain("add `Apache-2.0` to [dependencyPolicy.licenses].allow");

    const allowed = await copyFixture(root, work, "hello-zolt", "license-allowed");
    await appendFile(join(allowed, "zolt.toml"), [
      "", "[dependencyPolicy.licenses]", 'allow = ["Apache-2.0", "MIT"]', 'unknown = "fail"', "",
    ].join("\n"), "utf8");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", allowed, "--cache-root", zolt.cacheRoot]);
    const passing = await runZolt(t, zolt, [
      "--no-progress", "check", "--check", "license-policy", "--cwd", allowed, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(passing.stdout).toContain("0 violation(s), 0 warning(s)");
    expect.value(passing.stdout).toContain("Check status: ok");
  });
});
