package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.SpdxLicenseTerm;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicenseException;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;
import sh.zolt.manifest.authored.DependencyVariant;
import sh.zolt.project.UnknownLicensePolicy;

final class ManifestDependencyWriterTest {
    @Test
    void emitsTheLibraryApiBoundaryDependencyProjectionInCanonicalOrder() {
        AuthoredDependencies dependencies = dependencies(
                fixed(DependencyLane.IMPLEMENTATION,
                        "com.fasterxml.jackson.core:jackson-databind", "2.19.0"),
                fixed(DependencyLane.API, "org.slf4j:slf4j-api", "2.0.17"),
                fixed(DependencyLane.TEST,
                        "org.junit.jupiter:junit-jupiter", "5.13.4"));

        String output = write(Optional.of(dependencies), Optional.empty(), Optional.empty());

        assertEquals(
                """
                [dependencies]
                "com.fasterxml.jackson.core:jackson-databind" = "2.19.0"

                [dependencies.api]
                "org.slf4j:slf4j-api" = "2.0.17"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """,
                output);
        assertValid(output);
        assertEquals(dependencies, decode(output).dependencies().orElseThrow());
    }

    @Test
    void emitsTheSpringServiceDependencyProjectionExactly() {
        AuthoredDependencies dependencies = dependencies(
                managed(DependencyLane.IMPLEMENTATION,
                        "org.springframework.boot:spring-boot-starter-webmvc"),
                managed(DependencyLane.RUNTIME, "org.postgresql:postgresql"),
                managed(DependencyLane.DEV,
                        "org.springframework.boot:spring-boot-devtools"),
                managed(DependencyLane.TEST,
                        "org.springframework.boot:spring-boot-starter-test"));

        String output = write(Optional.of(dependencies), Optional.empty(), Optional.empty());

        assertEquals(
                """
                [dependencies]
                "org.springframework.boot:spring-boot-starter-webmvc" = { managed = true }

                [dependencies.runtime]
                "org.postgresql:postgresql" = { managed = true }

                [dependencies.dev]
                "org.springframework.boot:spring-boot-devtools" = { managed = true }

                [dependencies.test]
                "org.springframework.boot:spring-boot-starter-test" = { managed = true }
                """,
                output);
        assertValid(output);
        assertEquals(dependencies, decode(output).dependencies().orElseThrow());
    }

    @Test
    void sortsDynamicDependencyRowsByTheModelVariantOrder() {
        AuthoredDependencies dependencies = dependencies(
                fixed(DependencyLane.IMPLEMENTATION, "org.example:zeta", "2.0.0"),
                fixed(DependencyLane.IMPLEMENTATION, "org.example:alpha", "1.0.0"));

        assertEquals(
                """
                [dependencies]
                "org.example:alpha" = "1.0.0"
                "org.example:zeta" = "2.0.0"
                """,
                write(Optional.of(dependencies), Optional.empty(), Optional.empty()));
    }

    @Test
    void emitsEveryLaneSelectorConstraintAndPolicyFieldInSchemaOrder() {
        AuthoredDependencies dependencies = dependencies(
                dependency(
                        DependencyLane.IMPLEMENTATION,
                        "org.example:rich",
                        new DependencySelector.FixedVersion("2.0.0"),
                        new AuthoredDependencyMetadata(
                                true,
                                true,
                                Optional.of("tests"),
                                Optional.of("test-jar"),
                                List.of(coordinate("legacy:logging"), coordinate("legacy:bridge")))),
                dependency(
                        DependencyLane.API,
                        "org.example:workspace",
                        new DependencySelector.Workspace(),
                        new AuthoredDependencyMetadata(
                                true, false, Optional.empty(), Optional.empty(), List.of())),
                dependency(
                        DependencyLane.RUNTIME,
                        "org.example:referenced",
                        new DependencySelector.VersionReference(id("release")),
                        AuthoredDependencyMetadata.none()),
                dependency(
                        DependencyLane.PROVIDED,
                        "org.example:provided",
                        new DependencySelector.FixedVersion("1.0.0"),
                        new AuthoredDependencyMetadata(
                                false, true, Optional.empty(), Optional.empty(), List.of())),
                managed(DependencyLane.DEV, "org.example:managed"),
                fixed(DependencyLane.TEST, "org.example:test", "3.0.0"),
                managed(DependencyLane.PROCESSOR, "org.example:processor"),
                dependency(
                        DependencyLane.TEST_PROCESSOR,
                        "org.example:test-processor",
                        new DependencySelector.VersionReference(id("processor")),
                        AuthoredDependencyMetadata.none()));
        AuthoredDependencyConstraints constraints = new AuthoredDependencyConstraints(Map.of(
                coordinate("org.example:zeta"),
                new AuthoredDependencyConstraint(
                        new DependencyConstraintSelector.VersionReference(id("release")),
                        Optional.of("Keep the graph aligned")),
                coordinate("org.example:alpha"),
                new AuthoredDependencyConstraint(
                        new DependencyConstraintSelector.FixedVersion("1.1.0"),
                        Optional.empty()),
                coordinate("org.example:middle"),
                new AuthoredDependencyConstraint(
                        new DependencyConstraintSelector.FixedVersion("1.5.0"),
                        Optional.of("Security floor"))));
        AuthoredDependencyPolicy policy = policy();

        String output = write(
                Optional.of(dependencies), Optional.of(constraints), Optional.of(policy));

        assertEquals(
                """
                [dependencies]
                "org.example:rich" = { version = "2.0.0", optional = true, publishOnly = true, classifier = "tests", type = "test-jar", exclude = ["legacy:bridge", "legacy:logging"] }

                [dependencies.api]
                "org.example:workspace" = { workspace = true, optional = true }

                [dependencies.runtime]
                "org.example:referenced" = { versionRef = "release" }

                [dependencies.provided]
                "org.example:provided" = { version = "1.0.0", publishOnly = true }

                [dependencies.dev]
                "org.example:managed" = { managed = true }

                [dependencies.test]
                "org.example:test" = "3.0.0"

                [dependencies.processor]
                "org.example:processor" = { managed = true }

                [dependencies.test-processor]
                "org.example:test-processor" = { versionRef = "processor" }

                [dependencies.constraints]
                "org.example:alpha" = "1.1.0"
                "org.example:middle" = { version = "1.5.0", reason = "Security floor" }
                "org.example:zeta" = { versionRef = "release", reason = "Keep the graph aligned" }

                [dependencies.policy]
                conflicts = "fail"
                deny = [
                    { coordinate = "org.example:alpha" },
                    { coordinate = "org.example:zeta", reason = "Unmaintained" },
                ]

                [dependencies.policy.licenses]
                allow = ["Apache-2.0", "MIT"]
                deny = ["GPL-3.0-only"]
                unknown = "fail"

                [dependencies.license-exceptions."org.example:alpha"]
                allow = ["Apache-2.0"]
                reason = "Reviewed alpha"

                [dependencies.license-exceptions."org.example:zeta"]
                allow = ["BSD-3-Clause", "Unicode-3.0"]
                version = "0.8.4-SNAPSHOT"
                reason = "Reviewed zeta"
                """,
                output);
        assertValid(output);
        assertFalse(output.contains("{}"));
        assertFalse(output.contains("{ }"));
        var decoded = decode(output);
        assertEquals(
                List.of(coordinate("legacy:bridge"), coordinate("legacy:logging")),
                decoded.dependencies()
                        .orElseThrow()
                        .inLane(DependencyLane.IMPLEMENTATION)
                        .getFirst()
                        .metadata()
                        .exclusions());
        assertEquals(constraints, decoded.dependencyConstraints().orElseThrow());
        assertEquals(policy, decoded.dependencyPolicy().orElseThrow());
    }

    @Test
    void omitsEmptyDomainsAndCanonicalDefaultsWithoutEmptyTables() {
        AuthoredDependency explicitJar = dependency(
                DependencyLane.IMPLEMENTATION,
                "org.example:plain",
                new DependencySelector.FixedVersion("1.0.0"),
                new AuthoredDependencyMetadata(
                        false,
                        false,
                        Optional.empty(),
                        Optional.of(DependencyVariant.DEFAULT_TYPE),
                        List.of()));
        AuthoredDependencyPolicy defaults = new AuthoredDependencyPolicy(
                Optional.of(DependencyConflictPolicy.RESOLVE),
                List.of(),
                Optional.of(new AuthoredLicensePolicy(
                        List.of(), List.of(), Optional.of(UnknownLicensePolicy.WARN))),
                Map.of());

        String output = write(
                Optional.of(dependencies(explicitJar)),
                Optional.of(AuthoredDependencyConstraints.empty()),
                Optional.of(defaults));

        assertEquals(
                """
                [dependencies]
                "org.example:plain" = "1.0.0"
                """,
                output);
        assertFalse(output.contains("optional"));
        assertFalse(output.contains("publishOnly"));
        assertFalse(output.contains("type"));
        assertFalse(output.contains("policy"));
        assertValid(output);
        assertEquals(
                "",
                write(
                        Optional.of(AuthoredDependencies.empty()),
                        Optional.of(AuthoredDependencyConstraints.empty()),
                        Optional.empty()));
    }

    @Test
    void failsClosedWhenTheModelContainsTwoVariantsForOneTomlMapKey() {
        AuthoredDependencies dependencies = dependencies(
                fixed(DependencyLane.IMPLEMENTATION, "org.example:multi", "1.0.0"),
                dependency(
                        DependencyLane.IMPLEMENTATION,
                        "org.example:multi",
                        new DependencySelector.FixedVersion("1.0.0"),
                        new AuthoredDependencyMetadata(
                                false,
                                false,
                                Optional.of("tests"),
                                Optional.empty(),
                                List.of())));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> write(Optional.of(dependencies), Optional.empty(), Optional.empty()));

        assertEquals(
                "Manifest field `dependencies.org.example:multi` was emitted more than once.",
                failure.getMessage());
    }

    private static AuthoredDependencyPolicy policy() {
        AuthoredLicensePolicy licenses = new AuthoredLicensePolicy(
                List.of(
                        LicensePolicyTerm.fromAuthored("MIT"),
                        LicensePolicyTerm.fromAuthored("Apache-2.0")),
                List.of(LicensePolicyTerm.fromAuthored("GPL-3.0-only")),
                Optional.of(UnknownLicensePolicy.FAIL));
        return new AuthoredDependencyPolicy(
                Optional.of(DependencyConflictPolicy.FAIL),
                List.of(
                        new DependencyDenyEntry(
                                coordinate("org.example:zeta"), Optional.of("Unmaintained")),
                        new DependencyDenyEntry(
                                coordinate("org.example:alpha"), Optional.empty())),
                Optional.of(licenses),
                Map.of(
                        coordinate("org.example:zeta"),
                        new AuthoredLicenseException(
                                List.of(
                                        new SpdxLicenseTerm("Unicode-3.0"),
                                        new SpdxLicenseTerm("BSD-3-Clause")),
                                Optional.of("0.8.4-SNAPSHOT"),
                                "Reviewed zeta"),
                        coordinate("org.example:alpha"),
                        new AuthoredLicenseException(
                                List.of(new SpdxLicenseTerm("Apache-2.0")),
                                Optional.empty(),
                                "Reviewed alpha")));
    }

    private static AuthoredDependencies dependencies(AuthoredDependency... dependencies) {
        return new AuthoredDependencies(List.of(dependencies));
    }

    private static AuthoredDependency fixed(
            DependencyLane lane, String coordinate, String version) {
        return dependency(
                lane,
                coordinate,
                new DependencySelector.FixedVersion(version),
                AuthoredDependencyMetadata.none());
    }

    private static AuthoredDependency managed(
            DependencyLane lane, String coordinate) {
        return dependency(
                lane,
                coordinate,
                new DependencySelector.Managed(),
                AuthoredDependencyMetadata.none());
    }

    private static AuthoredDependency dependency(
            DependencyLane lane,
            String coordinate,
            DependencySelector selector,
            AuthoredDependencyMetadata metadata) {
        return new AuthoredDependency(lane, coordinate(coordinate), selector, metadata);
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private static String write(
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> constraints,
            Optional<AuthoredDependencyPolicy> policy) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestDependencyWriter().write(emitter, dependencies, constraints, policy);
        return emitter.finish();
    }

    private static sh.zolt.manifest.authored.AuthoredManifest decode(String source) {
        return decodeAuthoredManifest("[project]\nname = \"round-trip\"\n\n" + source);
    }

    private static void assertValid(String source) {
        assertFalse(Toml.parse(source).hasErrors());
    }
}
