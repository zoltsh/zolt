package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.policy.DependencyPolicyReportService;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DependencyQualityCheckTest extends QualityCheckServiceTestSupport {
    private final DependencyQualityCheck check = new DependencyQualityCheck(
            new ZoltLockfileReader(),
            new DependencyPolicyReportService());

    @TempDir
    private Path tempDir;

    @Test
    void dependencyMetadataRequiresLockfile() throws IOException {
        Path projectDir = tempDir.resolve("missing-lockfile");
        ProjectConfig config = parseProject(projectDir, "");

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_METADATA,
                QualityCheckStatus.FAILED,
                "zolt.lock",
                "zolt.lock is missing.",
                "Run `zolt resolve`.");
    }

    @Test
    void dependencyMetadataReportsMalformedLockfileWithProjectResolveAction() throws IOException {
        Path projectDir = tempDir.resolve("malformed-project-lockfile");
        ProjectConfig config = parseProject(projectDir, "");
        writeLockfile(projectDir, """

                [[package]]
                id = 42
                """);

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertEquals(QualityCheckService.DEPENDENCY_METADATA, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("zolt.lock", result.subject());
        assertTrue(result.message().contains("Invalid value type in zolt.lock"));
        assertEquals("Run `zolt resolve`.", result.nextStep());
    }

    @Test
    void dependencyMetadataPassesWhenNoDeclarationsNeedValidation() throws IOException {
        Path projectDir = tempDir.resolve("no-metadata");
        ProjectConfig config = parseProject(projectDir, "");
        writeLockfile(projectDir, "");

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_METADATA,
                QualityCheckStatus.PASSED,
                "no-metadata",
                "No dependency metadata declarations require validation.",
                "");
    }

    @Test
    void dependencyMetadataKeepsPublishOnlyDependenciesOutOfLockfile() throws IOException {
        Path projectDir = tempDir.resolve("publish-only-ok");
        ProjectConfig config = parseProject(projectDir, """

                [dependencies]
                "com.example:publish-helper" = { version = "1.0.0", publishOnly = true }
                """);
        writeLockfile(projectDir, "");

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_METADATA,
                QualityCheckStatus.PASSED,
                "com.example:publish-helper",
                "Publish-only dependency `com.example:publish-helper` is kept out of zolt.lock classpaths.",
                "");
    }

    @Test
    void dependencyMetadataRequiresClasspathDependencyInLockfile() throws IOException {
        Path projectDir = tempDir.resolve("classpath-missing");
        ProjectConfig config = parseProject(projectDir, """

                [dependencies]
                "com.example:missing-lib" = { version = "1.0.0", optional = true }
                """);
        writeLockfile(projectDir, "");

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_METADATA,
                QualityCheckStatus.FAILED,
                "com.example:missing-lib",
                "Dependency metadata for `com.example:missing-lib` is not represented in zolt.lock.",
                "Run `zolt resolve`.");
    }

    @Test
    void dependencyMetadataPassesWhenClasspathMetadataMatchesLockfile() throws IOException {
        Path projectDir = tempDir.resolve("classpath-metadata-ok");
        ProjectConfig config = parseProject(projectDir, """

                [dependencies]
                "com.example:root-lib" = { version = "1.0.0", optional = true, exclude = ["com.example:legacy"] }
                """);
        writeLockfile(projectDir, packageEntry(
                "com.example:root-lib",
                "1.0.0",
                "compile",
                true,
                "dependencies = [\"com.example:helper\"]"));

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_METADATA,
                QualityCheckStatus.PASSED,
                "com.example:root-lib",
                "Dependency metadata for `com.example:root-lib` is represented in zolt.lock.",
                "");
    }

    @Test
    void dependencyMetadataAcceptsAnExcludedEdgeWhenAnotherPathReintroducesThePackage() throws IOException {
        Path projectDir = tempDir.resolve("excluded-edge-reintroduced");
        ProjectConfig config = parseProject(projectDir, """

                [dependencies]
                "com.example:root-lib" = { version = "1.0.0", exclude = ["com.example:legacy"] }
                """);
        writeLockfile(projectDir, packageEntry(
                "com.example:root-lib",
                "1.0.0",
                "compile",
                true,
                "dependencies = [\"com.example:legacy:1.0.0:jar:runtime\"]")
                + """

                [[policy]]
                kind = "edge-exclusion"
                id = "com.example:legacy"
                requested = "1.0.0"
                source = "com.example:root-lib:1.0.0"
                policy = "dependency edge exclusion from com.example:root-lib:1.0.0 to com.example:legacy"
                """);

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_METADATA,
                QualityCheckStatus.PASSED,
                "com.example:root-lib",
                "Dependency metadata for `com.example:root-lib` is represented in zolt.lock.",
                "");
    }

    @Test
    void dependencyMetadataRejectsPublishOnlyDependenciesPresentInLockfile() throws IOException {
        Path projectDir = tempDir.resolve("publish-only-in-lockfile");
        ProjectConfig config = parseProject(projectDir, """

                [dependencies]
                "com.example:publish-helper" = { version = "1.0.0", publishOnly = true }
                """);
        writeLockfile(projectDir, packageEntry("com.example:publish-helper", "1.0.0", "compile", true, ""));

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_METADATA,
                QualityCheckStatus.FAILED,
                "com.example:publish-helper",
                "Publish-only dependency `com.example:publish-helper` is present in zolt.lock.",
                "Run `zolt resolve`; if it remains, remove publishOnly = true or move the dependency to a normal classpath section.");
    }

    @Test
    void dependencyMetadataReportsOptionalAndExclusionFailuresInCoordinateOrder() throws IOException {
        Path projectDir = tempDir.resolve("metadata-failures");
        ProjectConfig config = parseProject(projectDir, """

                [dependencies]
                "com.example:optional-lib" = { version = "1.0.0", optional = true }
                "com.example:root-lib" = { version = "1.0.0", exclude = ["com.example:legacy"] }
                """);
        writeLockfile(projectDir, packageEntry(
                "com.example:root-lib",
                "1.0.0",
                "compile",
                true,
                "dependencies = [\"com.example:legacy:1.0.0:jar:runtime\"]")
                + packageEntry("com.example:optional-lib", "1.0.0", "compile", false, ""));

        List<QualityCheckResult> results = check.checkProjectMetadata(Optional.empty(), projectDir, config, false);

        assertEquals(List.of(
                        "com.example:optional-lib|Optional direct dependency `com.example:optional-lib` is not marked direct in zolt.lock.|Run `zolt resolve`.",
                        "com.example:root-lib|Excluded dependency `com.example:legacy` is still present on direct dependency `com.example:root-lib` in zolt.lock.|Check [dependencies].com.example:root-lib.exclusions and run `zolt resolve`."),
                results.stream()
                        .map(result -> result.subject() + "|" + result.message() + "|" + result.nextStep())
                        .toList());
    }

    @Test
    void dependencyMetadataRequiresWorkspaceModeForWorkspaceDependencyMetadata() throws IOException {
        Path root = tempDir.resolve("optional-workspace-dependency");
        Path projectDir = root.resolve("apps/api");
        ProjectConfig config = parseWorkspaceMember(root, """

                [dependencies]
                "com.example:core" = { workspace = true, optional = true }
                """, List.of("apps/api", "modules/core"));
        writeLockfile(projectDir, "");

        QualityCheckResult result = check.checkProjectMetadata(Optional.empty(), projectDir, config, false).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_METADATA,
                QualityCheckStatus.FAILED,
                "com.example:core",
                "Workspace dependency `com.example:core` requires member-qualified workspace graph evidence.",
                "Run `zolt check --workspace --check dependency-metadata` from the workspace root.");
    }

    @Test
    void dependencyPolicyRequiresLockfileWithWorkspaceRemediationWhenRequested() throws IOException {
        Path projectDir = tempDir.resolve("policy-missing-lockfile");
        ProjectConfig config = parseProject(projectDir, "");

        QualityCheckResult result = check.checkPolicy(
                Optional.of("modules/api"),
                projectDir,
                config,
                tempDir.resolve("zolt.lock"),
                true).getFirst();

        assertResult(
                result,
                QualityCheckService.DEPENDENCY_POLICY,
                QualityCheckStatus.FAILED,
                "zolt.lock",
                "Dependency policy diagnostics require zolt.lock.",
                "Run `zolt resolve --workspace`.");
        assertEquals(Optional.of("modules/api"), result.member());
    }

    @Test
    void dependencyPolicyReportsMalformedLockfileWithActionableRefreshCommand() throws IOException {
        Path projectDir = tempDir.resolve("policy-malformed-lockfile");
        ProjectConfig config = parseProject(projectDir, "");
        writeLockfile(projectDir, """

                [[package]]
                id = 42
                """);

        QualityCheckResult result = check.checkPolicy(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false).getFirst();

        assertEquals(QualityCheckService.DEPENDENCY_POLICY, result.id());
        assertEquals(QualityCheckStatus.FAILED, result.status());
        assertEquals("zolt.lock", result.subject());
        assertTrue(result.message().contains("Invalid value type in zolt.lock"));
        assertEquals("Run `zolt resolve` to refresh dependency policy evidence.", result.nextStep());
    }

    @Test
    void dependencyPolicyReportsSummaryConstraintsExclusionsAndDirectVersionFailures() throws IOException {
        Path projectDir = tempDir.resolve("policy-conflicts");
        ProjectConfig config = parseProject(projectDir, """

                [dependencies]
                "com.example:direct-lib" = "1.2.3"
                "com.example:stale-direct" = "1.0.0"

                [dependencies.policy]
                deny = [{ coordinate = "com.example:direct-lib", reason = "Direct dependency conflict fixture" }]

                [dependencies.constraints]
                "com.example:direct-lib" = { version = "1.0.0" }
                "com.example:transitive-lib" = { version = "1.0.0" }
                """);
        writeLockfile(projectDir,
                packageEntry("com.example:direct-lib", "1.2.3", "compile", true, "")
                        + packageEntry("com.example:stale-direct", "2.0.0", "compile", true, "")
                        + packageEntry("com.example:transitive-lib", "2.0.0", "runtime", false, ""));

        List<QualityCheckResult> results = check.checkPolicy(
                Optional.empty(),
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                false);

        assertEquals(List.of(
                        "passed|policy-conflicts|Dependency policy baseline is explainable: 0 platforms, 2 constraints, 1 exclusion, and 2 direct explicit versions.|",
                        "failed|[dependencies.constraints].com.example:direct-lib|Strict constraint for `com.example:direct-lib` is overridden by a direct dependency version.|Align the direct dependency version with [dependencies.constraints], or remove the strict constraint if the direct override is intentional.",
                        "failed|[dependencies.constraints].com.example:transitive-lib|Strict constraint expected `com.example:transitive-lib` version `1.0.0`, but zolt.lock selected `2.0.0`.|Run `zolt resolve` after updating [dependencies.constraints], or change the strict constraint to the selected baseline.",
                        "failed|[dependencies.policy].deny com.example:direct-lib|Dependency policy excludes `com.example:direct-lib`, but that package is still a direct dependency.|Remove the direct dependency, or remove the exclusion if the dependency is intentional.",
                        "failed|[dependencies].com.example:stale-direct|Direct dependency `com.example:stale-direct:1.0.0` is declared, but zolt.lock did not select that version.|Run `zolt resolve`, then review the selected version or update the direct dependency declaration."),
                results.stream()
                        .map(result -> result.status().jsonValue()
                                + "|"
                                + result.subject()
                                + "|"
                                + result.message()
                                + "|"
                                + result.nextStep())
                        .toList());
    }

    private static String packageEntry(
            String coordinate,
            String version,
            String scope,
            boolean direct,
            String extra) {
        String jarName = coordinate.replace(':', '-') + "-" + version + ".jar";
        String dependencies = extra.contains("dependencies") ? "" : "dependencies = []";
        return dependencyRoot(coordinate, version, scope, direct) + """

                [[package]]
                id = "%s"
                version = "%s"
                source = "maven-central"
                scope = "%s"
                direct = %s
                jar = "com/example/%s/%s/%s"
                %s
                %s
                """.formatted(
                coordinate,
                version,
                scope,
                direct,
                coordinate.substring(coordinate.indexOf(':') + 1),
                version,
                jarName,
                extra,
                dependencies);
    }

    /** A v7 lock records one member-qualified authored root per direct package it selects. */
    private static String dependencyRoot(
            String coordinate,
            String version,
            String scope,
            boolean direct) {
        if (!direct) {
            return "";
        }
        return """

                [[dependencyRoot]]
                member = "."
                id = "%s"
                version = "%s"
                lane = "%s"
                resolvedScope = "%s"
                """.formatted(coordinate, version, lane(scope), scope);
    }

    private static String lane(String scope) {
        return switch (scope) {
            case "compile" -> "implementation";
            case "test-processor" -> "test-processor";
            case "runtime", "provided", "dev", "test", "processor" -> scope;
            default -> throw new IllegalArgumentException("No authored lane for scope `" + scope + "`.");
        };
    }

    private static void assertResult(
            QualityCheckResult result,
            String id,
            QualityCheckStatus status,
            String subject,
            String message,
            String nextStep) {
        assertEquals(id, result.id());
        assertEquals(status, result.status());
        assertEquals(subject, result.subject());
        assertEquals(message, result.message());
        assertEquals(nextStep, result.nextStep());
    }
}
