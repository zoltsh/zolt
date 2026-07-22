import { smoke, type SmokeContext } from "smoque";

import { verifyEnterprisePackage } from "./support/enterprise-package.mts";
import { verifyEnterpriseBuildPlan, verifyEnterprisePolicy } from "./support/enterprise-policy.mts";
import { verifyEnterpriseReleaseChecks } from "./support/enterprise-release.mts";
import { verifyEnterpriseMigration, verifyEnterpriseTests } from "./support/enterprise-verification.mts";
import { copyFixture, packagedZolt } from "./support/zolt-smoke.mts";

smoke.suite("enterprise Spring Boot canary smoke", { tags: ["enterprise", "spring-boot"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-enterprise-canary");
  const zolt = await packagedZolt(t);
  const project = await copyFixture(root, work, "spring-boot-enterprise-canary");

  await t.step("resolves explainable policy and classpath lanes", async () => {
    await verifyEnterprisePolicy(t, zolt, project);
  });
  await t.step("generates OpenAPI sources and exposes a typed CI plan", async () => {
    await verifyEnterpriseBuildPlan(t, zolt, project);
  });
  await t.step("writes test and coverage evidence", async () => {
    await verifyEnterpriseTests(t, zolt, project);
  });
  await t.step("packages a policy-correct Spring Boot WAR", async () => {
    await verifyEnterprisePackage(t, zolt, project);
  });
  await t.step("maps the redacted Gradle build without executing Gradle", async () => {
    await verifyEnterpriseMigration(t, zolt, root.path("examples/migration-explain/gradle-enterprise-spring"));
  });
  await t.step("enforces CI and release publication policy", async () => {
    await verifyEnterpriseReleaseChecks(t, zolt, project);
  });
});
