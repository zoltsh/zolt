package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import sh.zolt.error.ActionableException;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExactUpdatePlannerTest {
    private final ExactUpdatePlanner planner = new ExactUpdatePlanner();
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();
    private final UpdateApplier applier = new UpdateApplier();

    @Test
    void plansCallerSelectedPatchMinorMajorAndNonLatestVersions() {
        ProjectConfig config = config("""
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
        ProjectConfig config = config("""
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
        UpdateTarget target = target(config("""
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
        UpdateTarget target = target(config("""
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
        UpdateTarget target = target(config("""
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
        ProjectConfig config = config("""
                [generated.openapiTool]
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
    void unknownTargetRequiresARefreshedCatalog() {
        ProjectConfig config = config("""
                [dependencies]
                "com.example:lib" = "1.2.3"
                """);
        UpdateTargetId unknown = UpdateTargetId.create(
                "zolt.toml",
                OutdatedSurface.DEPENDENCY,
                "[test.dependencies]",
                "com.example:lib");

        ActionableException failure = assertThrows(
                ActionableException.class,
                () -> planner.plan(config, "zolt.toml", "zolt.lock", unknown, options("1.2.4")));

        assertTrue(failure.getMessage().contains("Unknown Zolt update target"));
        assertTrue(failure.getMessage().contains("zolt outdated"));
    }

    @Test
    void aliasPlanCarriesFanOutAndAppliesOnlyThroughTheAlias() {
        ProjectConfig config = config("""
                [versions]
                shared = "1.0.0"

                [dependencies]
                "com.example:one" = { versionRef = "shared" }
                "com.example:two" = { versionRef = "shared", optional = true }
                """);
        UpdateTarget target = target(config, "shared");
        ExactUpdatePlan plan = changed(target, "2.0.0");

        ProjectConfig applied = applier.apply(config, plan);

        assertEquals(List.of(
                "[dependencies].com.example:one",
                "[dependencies].com.example:two"), target.governs());
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().getFirst().contains("updates 2 referencing coordinate(s)"));
        assertEquals("2.0.0", applied.versionAliases().get("shared"));
        assertEquals("shared", metadata(applied, "dependencies", "com.example:one").versionRef());
        assertEquals("shared", metadata(applied, "dependencies", "com.example:two").versionRef());
        assertTrue(metadata(applied, "dependencies", "com.example:two").optional());
    }

    @Test
    void dependencyApplicationPreservesRichMetadata() {
        ProjectConfig config = config("""
                [dependencies]
                "com.example:lib" = { version = "1.2.3", optional = true, classifier = "tests", type = "jar", exclusions = [{ group = "com.example", artifact = "legacy" }] }
                """);
        ExactUpdatePlan plan = changed(target(config, "com.example:lib"), "1.3.0");

        ProjectConfig applied = applier.apply(config, plan);
        DependencyMetadata metadata = metadata(applied, "dependencies", "com.example:lib");

        assertEquals("1.3.0", applied.dependencies().get("com.example:lib"));
        assertTrue(metadata.optional());
        assertFalse(metadata.publishOnly());
        assertEquals("tests", metadata.classifier());
        assertEquals("jar", metadata.type());
        assertEquals(1, metadata.exclusions().size());
    }

    @Test
    void constraintApplicationPreservesKindAndReason() {
        ProjectConfig config = config("""
                [dependencyConstraints]
                "com.example:lib" = { version = "1.2.3", kind = "strict", reason = "security floor" }
                """);
        ExactUpdatePlan plan = changed(target(config, "com.example:lib"), "1.3.0");

        ProjectConfig applied = applier.apply(config, plan);
        DependencyConstraint constraint = applied.dependencyPolicy().constraints().get("com.example:lib");

        assertEquals("1.3.0", constraint.version());
        assertEquals("STRICT", constraint.kind().name());
        assertEquals("security floor", constraint.reason().orElseThrow());
        assertTrue(constraint.versionRef().isEmpty());
    }

    @Test
    void hugeNumericVersionsPlanAndClassifyWithoutOverflow() {
        UpdateTarget target = target(config("""
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

    private UpdateTarget target(ProjectConfig config, String identifier) {
        return catalog.collect(config, "zolt.toml", "zolt.lock").stream()
                .filter(candidate -> candidate.identifier().equals(identifier))
                .findFirst()
                .orElseThrow();
    }

    private static DependencyMetadata metadata(ProjectConfig config, String section, String coordinate) {
        return config.dependencyMetadata().get(DependencyMetadata.key(section, coordinate));
    }

    private static ProjectConfig config(String body) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                %s
                """.formatted(body));
    }
}
