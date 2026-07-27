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
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyPolicySettings;
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

                License policy: 1 denied, 1 unknown of 3 dependencies.
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
        assertTrue(text.contains("License policy: 2 denied, 0 unknown of 3 dependencies."), text);
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
                    "enforcedBy": "zolt check --check license-policy"
                  }
                }
                """, json);
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
