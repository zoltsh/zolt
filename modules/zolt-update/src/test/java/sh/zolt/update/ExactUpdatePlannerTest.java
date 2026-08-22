package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import sh.zolt.error.ActionableException;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ExactUpdatePlannerTest {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    private final ExactUpdatePlanner planner = new ExactUpdatePlanner();
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();
    private final UpdateApplier applier = new UpdateApplier();

    @Test
    void plansCallerSelectedPatchMinorMajorAndNonLatestVersions() {
        AuthoredManifest config = manifest("""
                [dependencies]
                "com.example:lib" = "1.2.3"
                """);
        UpdateTarget target = target(config, "com.example:lib");

        assertEquals(UpdateClass.PATCH, changed(target, "1.2.4").changeClass().orElseThrow());
        assertEquals(UpdateClass.MINOR, changed(target, "1.4.2").changeClass().orElseThrow());
        assertEquals(UpdateClass.MAJOR, changed(target, "2.0.0").changeClass().orElseThrow());
        assertEquals("1.4.2", changed(target, "1.4.2").toVersion());
    }

    @Test
    void exactSameStringIsSuccessfulNoOp() {
        AuthoredManifest config = manifest("""
                [dependencies]
                "com.example:lib" = "1.2.3"
                """);
        ExactUpdatePlan plan = planner.plan(target(config, "com.example:lib"), options("1.2.3"));

        assertFalse(plan.changed());
        assertTrue(plan.changeClass().isEmpty());
        assertTrue(plan.warnings().isEmpty());
        assertSame(config, applier.apply(config, plan));
    }

    @Test
    void rejectsDowngradesAndComparatorEquivalentSpellings() {
        UpdateTarget target = target(manifest("""
                [dependencies]
                "com.example:lib" = "1.2.3"
                """), "com.example:lib");

        ActionableException downgrade = assertThrows(
                ActionableException.class,
                () -> planner.plan(target, options("1.2.2")));
        ActionableException equivalent = assertThrows(
                ActionableException.class,
                () -> planner.plan(target, options("1.2.3-final")));

        assertTrue(downgrade.getMessage().contains("downgrade"));
        assertTrue(equivalent.getMessage().contains("does not advance"));
    }

    @Test
    void allowsReleasesAndRequiresOptInForPrereleases() {
        UpdateTarget target = target(manifest("""
                [dependencies]
                "com.example:lib" = "1.2.3"
                """), "com.example:lib");

        assertTrue(planner.plan(target, options("1.3.0")).changed());
        ActionableException rejected = assertThrows(
                ActionableException.class,
                () -> planner.plan(target, options("1.3.0-rc1")));
        ExactUpdatePlan allowed = planner.plan(target, new ExactUpdateOptions("1.3.0-rc1", true));

        assertTrue(rejected.getMessage().contains("prerelease"));
        assertTrue(allowed.changed());
        assertEquals(UpdateClass.MINOR, allowed.changeClass().orElseThrow());
    }

    @Test
    void usesVersionPolicyForEveryUnsupportedDestinationShape() {
        UpdateTarget target = target(manifest("""
                [dependencies]
                "com.example:lib" = "1.2.3"
                """), "com.example:lib");

        assertInvalid(target, "1.3.0-SNAPSHOT", "snapshot-version");
        assertInvalid(target, "[1.3,2.0)", "version-range");
        assertInvalid(target, "latest.release", "dynamic-version");
        assertInvalid(target, "${nextVersion}", "no-interpolation");
        assertInvalid(target, "1.3.", "incomplete-version");
        assertInvalid(target, " 1.3.0", "non-empty-literal");
        assertInvalid(target, null, "non-empty-literal");
    }

    @Test
    void rejectsNonUpdateableGeneratedTargetsBeforeVersionValidation() {
        AuthoredManifest config = manifest("""
                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """);
        UpdateTarget target = catalog.collect(config, "zolt.toml", "zolt.lock").getFirst();

        ActionableException failure = assertThrows(
                ActionableException.class,
                () -> planner.plan(target, options("")));

        assertTrue(failure.getMessage().contains("not updateable"));
        assertTrue(failure.getMessage().contains("generated-tool"));
    }

    @Test
    void applierRejectsProgrammaticallyConstructedPlansForUnsupportedSurfaces() {
        AuthoredManifest config = manifest("""
                [dependencies]
                "com.example:lib" = "1.2.3"
                """);
        UpdateTarget target = new UpdateTarget(
                UpdateTargetId.create(
                        "zolt.toml",
                        OutdatedSurface.OPENAPI_TOOL,
                        "[generated.tools.openapi]",
                        "org.openapitools:openapi-generator-cli"),
                "zolt.toml",
                "zolt.lock",
                OutdatedSurface.OPENAPI_TOOL,
                "org.openapitools:openapi-generator-cli",
                "[generated.tools.openapi]",
                "7.11.0",
                true,
                Optional.empty(),
                List.of());
        ExactUpdatePlan plan = new ExactUpdatePlan(
                target,
                "7.11.0",
                "7.12.0",
                Optional.of(UpdateClass.MINOR),
                true,
                List.of());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> applier.apply(config, plan));

        assertEquals("Update surface `openapiTool` is not mutable.", failure.getMessage());
    }

    @Test
    void unknownTargetRequiresARefreshedCatalog() {
        AuthoredManifest config = manifest("""
                [dependencies]
                "com.example:lib" = "1.2.3"
                """);
        UpdateTargetId unknown = UpdateTargetId.create(
                "zolt.toml",
                OutdatedSurface.DEPENDENCY,
                "[dependencies.test]",
                "com.example:lib");

        ActionableException failure = assertThrows(
                ActionableException.class,
                () -> planner.plan(config, "zolt.toml", "zolt.lock", unknown, options("1.2.4")));

        assertTrue(failure.getMessage().contains("Unknown Zolt update target"));
        assertTrue(failure.getMessage().contains("zolt outdated"));
    }

    @Test
    void aliasPlanCarriesFanOutAndAppliesOnlyThroughTheAlias() {
        AuthoredManifest config = manifest("""
                [versions]
                shared = "1.0.0"

                [dependencies]
                "com.example:one" = { versionRef = "shared" }
                "com.example:two" = { versionRef = "shared", optional = true }
                """);
        UpdateTarget target = target(config, "shared");
        ExactUpdatePlan plan = changed(target, "2.0.0");

        AuthoredManifest applied = applier.apply(config, plan);

        assertEquals(List.of(
                "[dependencies].com.example:one",
                "[dependencies].com.example:two"), target.governs());
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().getFirst().contains("updates 2 referencing coordinate(s)"));
        assertEquals(
                new VersionAliasValue("2.0.0"),
                applied.versions().orElseThrow().entries().get(new LocalId("shared")));
        assertEquals(
                new DependencySelector.VersionReference(new LocalId("shared")),
                declaration(applied, "com.example:one").selector());
        assertEquals(
                new DependencySelector.VersionReference(new LocalId("shared")),
                declaration(applied, "com.example:two").selector());
        assertTrue(declaration(applied, "com.example:two").metadata().optional());
    }

    @Test
    void dependencyApplicationPreservesRichMetadata() {
        AuthoredManifest config = manifest("""
                [dependencies]
                "com.example:lib" = { version = "1.2.3", optional = true, classifier = "tests", type = "jar", exclude = ["com.example:legacy"] }
                """);
        ExactUpdatePlan plan = changed(target(config, "com.example:lib"), "1.3.0");

        AuthoredManifest applied = applier.apply(config, plan);
        AuthoredDependency declaration = declaration(applied, "com.example:lib");

        assertEquals(new DependencySelector.FixedVersion("1.3.0"), declaration.selector());
        assertTrue(declaration.metadata().optional());
        assertFalse(declaration.metadata().publishOnly());
        assertEquals("tests", declaration.metadata().classifier().orElseThrow());
        assertTrue(
                declaration.metadata().type().isEmpty(),
                "an explicit jar type is the default variant, normalized away rather than preserved");
        assertEquals(1, declaration.metadata().exclusions().size());
    }

    @Test
    void constraintApplicationPreservesReason() {
        AuthoredManifest config = manifest("""
                [dependencies.constraints]
                "com.example:lib" = { version = "1.2.3", reason = "security floor" }
                """);
        ExactUpdatePlan plan = changed(target(config, "com.example:lib"), "1.3.0");

        AuthoredManifest applied = applier.apply(config, plan);
        AuthoredDependencyConstraint constraint = applied.dependencyConstraints()
                .orElseThrow()
                .entries()
                .get(new DependencyCoordinate("com.example:lib"));

        assertEquals(
                new DependencyConstraintSelector.FixedVersion("1.3.0"), constraint.selector());
        assertEquals("security floor", constraint.reason().orElseThrow());
    }

    @Test
    void hugeNumericVersionsPlanAndClassifyWithoutOverflow() {
        UpdateTarget target = target(manifest("""
                [dependencies]
                "com.example:lib" = "1.999999999999999999999999999999.1"
                """), "com.example:lib");

        ExactUpdatePlan plan = changed(target, "1.1000000000000000000000000000000.0");

        assertEquals(UpdateClass.MINOR, plan.changeClass().orElseThrow());
    }

    private ExactUpdatePlan changed(UpdateTarget target, String version) {
        ExactUpdatePlan plan = planner.plan(target, options(version));
        assertTrue(plan.changed());
        return plan;
    }

    private void assertInvalid(UpdateTarget target, String version, String rule) {
        ActionableException failure = assertThrows(
                ActionableException.class,
                () -> planner.plan(target, options(version)));
        assertTrue(failure.getMessage().contains(rule), failure.getMessage());
    }

    private ExactUpdateOptions options(String version) {
        return new ExactUpdateOptions(version, false);
    }

    private UpdateTarget target(AuthoredManifest config, String identifier) {
        return catalog.collect(config, "zolt.toml", "zolt.lock").stream()
                .filter(candidate -> candidate.identifier().equals(identifier))
                .findFirst()
                .orElseThrow();
    }

    private static AuthoredDependency declaration(AuthoredManifest manifest, String coordinate) {
        return manifest.dependencies().orElseThrow().declarations().stream()
                .filter(candidate -> candidate.coordinate().value().equals(coordinate))
                .findFirst()
                .orElseThrow();
    }

    private static AuthoredManifest manifest(String body) {
        return LOADER.document(source(body)).authored();
    }


    private static String source(String body) {
        return """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s
                """.formatted(body);
    }
}
