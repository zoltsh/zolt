package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.license.SpdxExpressionParser;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.LicensePolicyException;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.UnknownLicensePolicy;

/**
 * The reporting half of the license policy: {@code zolt licenses} annotates, {@code zolt check --check
 * license-policy} enforces. These pin the annotated renderings and, critically, that the unannotated
 * ones stay byte-for-byte identical when no policy is configured.
 */
final class LicenseReportPolicyAnnotationTest extends SbomTestSupport {
    private final LockSbomAssembler assembler = new LockSbomAssembler();
    private final LicenseReportBuilder builder = new LicenseReportBuilder();

    private List<SbomComponent> components() {
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:demo-lock-fingerprint"),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()),
                maven("org.example", "lib-b", "2.0.0", DependencyScope.COMPILE, true, SHA_B, List.of()),
                maven("org.example", "lib-c", "3.0.0", DependencyScope.COMPILE, true, SHA_C, List.of()));
        return assembler.assemble(
                config(), lockfile, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION, index())
                .components();
    }

    private LicenseIndex index() {
        return new LicenseIndex(
                Map.of(
                        "org.example:lib-a:1.0.0", List.of(SbomLicense.spdx("Apache-2.0")),
                        "org.example:lib-b:2.0.0", List.of(SbomLicense.spdx("GPL-3.0-only")),
                        "org.example:lib-c:3.0.0", List.of(SbomLicense.unknown())),
                List.of("org.example:lib-c:3.0.0"));
    }

    private static ProjectConfig configWithPolicy(LicensePolicySettings policy) {
        return config().withDependencyPolicy(new DependencyPolicySettings(List.of(), Map.of(), false, policy));
    }

    private static LicensePolicySettings denyGpl() {
        return new LicensePolicySettings(List.of(), List.of("GPL-3.0-only"), UnknownLicensePolicy.WARN);
    }

    @Test
    void noConfiguredPolicyLeavesTextOutputByteUnchanged() {
        LicenseReport report = builder.build(components(), index());
        LicensePolicyAnnotations annotations =
                LicensePolicyAnnotations.evaluate(components(), index(), config());

        assertFalse(annotations.configured());
        assertEquals(
                new LicenseReportTextWriter().write(report),
                new LicenseReportTextWriter().write(report, annotations));
    }

    @Test
    void noConfiguredPolicyLeavesJsonOutputByteUnchanged() {
        LicenseReport report = builder.build(components(), index());
        LicensePolicyAnnotations annotations =
                LicensePolicyAnnotations.evaluate(components(), index(), config());

        assertEquals(
                new LicenseReportJsonWriter().write(report),
                new LicenseReportJsonWriter().write(report, annotations));
    }

    @Test
    void deniedAndUnknownEntriesAreMarkedWithASummaryAndEnforcementPointer() {
        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                components(), index(), configWithPolicy(denyGpl()));

        String text = new LicenseReportTextWriter().write(builder.build(components(), index()), annotations);

        assertEquals("""
                Apache-2.0 (1)
                  org.example:lib-a:1.0.0

                GPL-3.0-only (1)
                  org.example:lib-b:2.0.0  [denied] denied by [dependencyPolicy.licenses].deny

                UNKNOWN (1)
                  org.example:lib-c:3.0.0  [unknown] unrecognized license ([dependencyPolicy.licenses].unknown = warn)
                  note: no license found in the cached POM chain; run `zolt resolve` to cache POMs, then re-run.

                License policy: 1 denied, 1 unknown of 3 dependencies. 0 permitted by exception; 0 stale exceptions.
                Next: run `zolt check --check license-policy` to enforce it.
                """, text);
    }

    @Test
    void unknownStrictnessFailPromotesTheUnknownEntryToDenied() {
        LicensePolicySettings policy =
                new LicensePolicySettings(List.of(), List.of("GPL-3.0-only"), UnknownLicensePolicy.FAIL);
        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                components(), index(), configWithPolicy(policy));

        String text = new LicenseReportTextWriter().write(builder.build(components(), index()), annotations);

        assertEquals(2, annotations.denied());
        assertEquals(0, annotations.unknown());
        assertTrue(
                text.contains("org.example:lib-c:3.0.0  [denied] unrecognized license and "
                        + "[dependencyPolicy.licenses].unknown = fail"),
                text);
        assertTrue(text.contains(
                "License policy: 2 denied, 0 unknown of 3 dependencies. "
                        + "0 permitted by exception; 0 stale exceptions."), text);
    }

    @Test
    void jsonAddsPolicyFieldsWithoutChangingTheExistingShape() {
        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                components(), index(), configWithPolicy(denyGpl()));

        String json = new LicenseReportJsonWriter().write(builder.build(components(), index()), annotations);

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "command": "licenses",
                  "groups": [
                    {
                      "license": "Apache-2.0",
                      "status": "spdx",
                      "url": null,
                      "components": [
                        {
                          "coordinate": "org.example:lib-a:1.0.0",
                          "purl": "pkg:maven/org.example/lib-a@1.0.0?type=jar"
                        }
                      ]
                    },
                    {
                      "license": "GPL-3.0-only",
                      "status": "spdx",
                      "url": null,
                      "components": [
                        {
                          "coordinate": "org.example:lib-b:2.0.0",
                          "purl": "pkg:maven/org.example/lib-b@2.0.0?type=jar",
                          "policy": {
                            "status": "denied",
                            "license": "GPL-3.0-only",
                            "reason": "denied by [dependencyPolicy.licenses].deny"
                          }
                        }
                      ]
                    },
                    {
                      "license": "UNKNOWN",
                      "status": "unknown",
                      "url": null,
                      "components": [
                        {
                          "coordinate": "org.example:lib-c:3.0.0",
                          "purl": "pkg:maven/org.example/lib-c@3.0.0?type=jar",
                          "policy": {
                            "status": "unknown",
                            "license": "UNKNOWN",
                            "reason": "unrecognized license ([dependencyPolicy.licenses].unknown = warn)"
                          }
                        }
                      ]
                    }
                  ],
                  "licensePolicy": {
                    "evaluated": 3,
                    "denied": 1,
                    "unknown": 1,
                    "permittedByException": 0,
                    "staleExceptions": 0,
                    "enforcedBy": "zolt check --check license-policy",
                    "exceptions": []
                  }
                }
                """, json);
    }

    @Test
    void expressionExceptionIsRenderedWithEvidenceAndOnlyOnItsDeclarationRow() {
        List<SbomComponent> components = components().stream()
                .filter(component -> component.name().equals("lib-a"))
                .toList();
        String coordinate = "org.example:lib-a:1.0.0";
        SbomLicense expression = SbomLicense.expression(
                new SpdxExpressionParser().parse("MIT AND BSD-3-Clause"),
                Optional.of("MIT AND BSD-3-Clause"),
                Optional.empty());
        LicenseIndex index = new LicenseIndex(Map.of(coordinate, List.of(expression)), List.of());
        LicensePolicyException exception = new LicensePolicyException(
                "org.example:lib-a",
                List.of("BSD-3-Clause"),
                Optional.of("1.0.0"),
                "Reviewed transitive dependency");
        LicensePolicySettings policy = new LicensePolicySettings(
                List.of("MIT"),
                List.of(),
                UnknownLicensePolicy.FAIL,
                Map.of(exception.dependency(), exception));
        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                components, index, configWithPolicy(policy));
        LicenseReport report = builder.build(components, index);

        String text = new LicenseReportTextWriter().write(report, annotations);
        String json = new LicenseReportJsonWriter().write(report, annotations);

        assertTrue(text.contains(
                "org.example:lib-a:1.0.0  [exception] BSD-3-Clause permitted by "
                        + "[dependencyPolicy.licenses.exceptions.\"org.example:lib-a\"]\n"
                        + "    reason: Reviewed transitive dependency"), text);
        assertTrue(text.contains("org.example:lib-a@1.0.0  [used]"), text);
        assertTrue(text.contains("1 permitted by exception"), text);
        assertTrue(json.contains("\"expression\": \"MIT AND BSD-3-Clause\""), json);
        assertTrue(json.contains("\"status\": \"permitted-by-exception\""), json);
        assertTrue(json.contains("\"license\": \"BSD-3-Clause\""), json);
        assertTrue(json.contains("\"matchedVersion\": \"1.0.0\""), json);
        assertTrue(json.contains("\"status\": \"used\""), json);
    }

    @Test
    void decisiveExceptionDoesNotAnnotateAnotherMavenLicenseRow() {
        List<SbomComponent> components = components().stream()
                .filter(component -> component.name().equals("lib-a"))
                .toList();
        String coordinate = "org.example:lib-a:1.0.0";
        LicenseIndex index = new LicenseIndex(
                Map.of(coordinate, List.of(SbomLicense.spdx("BSD-3-Clause"), SbomLicense.spdx("GPL-3.0-only"))),
                List.of());
        LicensePolicyException exception = new LicensePolicyException(
                "org.example:lib-a", List.of("BSD-3-Clause"), Optional.empty(), "Reviewed dependency");
        LicensePolicySettings policy = new LicensePolicySettings(
                List.of("MIT"), List.of(), UnknownLicensePolicy.FAIL, Map.of(exception.dependency(), exception));
        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                components, index, configWithPolicy(policy));

        String text = new LicenseReportTextWriter().write(builder.build(components, index), annotations);

        assertTrue(text.contains("BSD-3-Clause (1)\n  " + coordinate + "  [exception]"), text);
        assertTrue(text.contains("GPL-3.0-only (1)\n  " + coordinate + "\n"), text);
        assertFalse(text.contains("GPL-3.0-only (1)\n  " + coordinate + "  [exception]"), text);
    }

    @Test
    void memberPoliciesMergeToTheStrictestVerdictAmongTheMembersThatConsumeACoordinate() {
        // Both members consume everything, so both policies reach every coordinate: one denies GPL and
        // only warns on unknowns, the other fails on unknowns. A member with no policy contributes nothing.
        ProjectConfig deniesGpl = configWithPolicy(denyGpl());
        ProjectConfig failsOnUnknown =
                configWithPolicy(new LicensePolicySettings(List.of(), List.of(), UnknownLicensePolicy.FAIL));

        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                components(),
                index(),
                List.of(
                        new LicensePolicyScope(deniesGpl, components()),
                        new LicensePolicyScope(failsOnUnknown, components()),
                        new LicensePolicyScope(config(), components())));

        assertTrue(annotations.configured());
        assertEquals(Optional.empty(), annotations.statusFor("org.example:lib-a:1.0.0"));
        assertEquals(Optional.of("denied"), annotations.statusFor("org.example:lib-b:2.0.0"));
        // WARN from the first member, VIOLATION from the second: the stricter verdict is reported.
        assertEquals(Optional.of("denied"), annotations.statusFor("org.example:lib-c:3.0.0"));
        assertEquals(3, annotations.evaluated());
        assertEquals(2, annotations.denied());
        assertEquals(0, annotations.unknown());
    }

    @Test
    void strictestMemberDecisionWinsBeforeItsMavenDeclarationRowIsAnnotated() {
        List<SbomComponent> components = components().stream()
                .filter(component -> component.name().equals("lib-a"))
                .toList();
        String coordinate = "org.example:lib-a:1.0.0";
        LicenseIndex alternatives = new LicenseIndex(
                Map.of(coordinate, List.of(
                        SbomLicense.spdx("GPL-3.0-only"),
                        SbomLicense.unmapped(Optional.of("Zzz Custom License"), Optional.empty()))),
                List.of());
        LicensePolicySettings warnsOnUnmapped =
                new LicensePolicySettings(List.of("MIT"), List.of(), UnknownLicensePolicy.WARN);
        LicensePolicySettings failsOnUnmapped =
                new LicensePolicySettings(List.of("MIT"), List.of(), UnknownLicensePolicy.FAIL);

        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                components,
                alternatives,
                List.of(
                        new LicensePolicyScope(configWithPolicy(warnsOnUnmapped), components),
                        new LicensePolicyScope(configWithPolicy(failsOnUnmapped), components)));

        assertEquals(Optional.of("denied"), annotations.statusFor(coordinate));
        assertTrue(annotations.forDeclaration(coordinate, "GPL-3.0-only").isPresent());
        assertEquals(Optional.empty(), annotations.forDeclaration(coordinate, "Zzz Custom License"));
        String text = new LicenseReportTextWriter().write(
                builder.build(components, alternatives), annotations);
        assertFalse(text.contains("Zzz Custom License (1)\n  " + coordinate + "  [unknown]"), text);
    }

    @Test
    void identicalWorkspaceAuditsRemainDistinctAndNameTheirMember() {
        LicensePolicyException exception = new LicensePolicyException(
                "org.example:missing", List.of("BSD-3-Clause"), Optional.empty(), "Reviewed dependency");
        ProjectConfig policyOwner = configWithPolicy(new LicensePolicySettings(
                List.of("MIT"),
                List.of(),
                UnknownLicensePolicy.FAIL,
                Map.of(exception.dependency(), exception)));

        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                List.of(),
                LicenseIndex.empty(),
                List.of(
                        new LicensePolicyScope(policyOwner, List.of(), Optional.of("modules/core")),
                        new LicensePolicyScope(policyOwner, List.of(), Optional.of("apps/admin"))));

        assertEquals(2, annotations.exceptionAudits().size());
        assertEquals(2, annotations.staleExceptions());
        String text = new LicenseReportTextWriter().write(
                builder.build(List.of(), LicenseIndex.empty()), annotations);
        String json = new LicenseReportJsonWriter().write(
                builder.build(List.of(), LicenseIndex.empty()), annotations);
        assertTrue(text.contains("apps/admin  org.example:missing  [missing]"), text);
        assertTrue(text.contains("modules/core  org.example:missing  [missing]"), text);
        assertTrue(json.contains("\"member\": \"apps/admin\""), json);
        assertTrue(json.contains("\"member\": \"modules/core\""), json);
    }

    /**
     * The defect this scoping exists to prevent: a strict member must not taint a coordinate it does not
     * consume, or {@code zolt licenses --workspace} would report a violation that
     * {@code zolt check --workspace --check license-policy} passes.
     */
    @Test
    void aMemberPolicyNeverReachesACoordinateThatMemberDoesNotConsume() {
        List<SbomComponent> all = components();
        // Member A consumes the GPL library but configures no policy; member B denies GPL but consumes
        // only the Apache-2.0 one.
        List<SbomComponent> onlyApache = all.stream()
                .filter(component -> component.name().equals("lib-a"))
                .toList();

        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                all,
                index(),
                List.of(
                        new LicensePolicyScope(config(), all),
                        new LicensePolicyScope(configWithPolicy(denyGpl()), onlyApache)));

        assertTrue(annotations.configured());
        assertEquals(Optional.empty(), annotations.statusFor("org.example:lib-b:2.0.0"));
        assertEquals(Optional.empty(), annotations.statusFor("org.example:lib-c:3.0.0"));
        assertEquals(0, annotations.denied());
        assertEquals(0, annotations.unknown());
        // Still the whole report's dependencies, not just the scoped member's.
        assertEquals(3, annotations.evaluated());
    }

    @Test
    void evaluatedCountsDistinctReportedCoordinatesRatherThanComponentEntries() {
        // A workspace assembles one component per member context, so a shared coordinate can appear more
        // than once while the report lists it once.
        List<SbomComponent> duplicated = new ArrayList<>(components());
        duplicated.addAll(components());

        LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                duplicated, index(), List.of(new LicensePolicyScope(configWithPolicy(denyGpl()), duplicated)));

        assertEquals(6, duplicated.size());
        assertEquals(3, annotations.evaluated());
        // The denominator now matches what the report actually lists.
        assertEquals(3, builder.build(duplicated, index()).groups().stream()
                .mapToInt(group -> group.components().size())
                .sum());
        assertEquals(1, annotations.denied());
        assertEquals(1, annotations.unknown());
    }
}
