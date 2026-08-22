package sh.zolt.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.policy.DependencyPolicyReportService;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design §9.11: {@code conflicts = "warn"} resolves and emits a structured warning. The lock is the
 * durable record of what was mediated, so the machine-readable report of a warn policy is a
 * severity-tagged {@code dependency-policy} result rather than a resolve-time-only message.
 */
final class DependencyConflictPolicyCheckTest extends QualityCheckServiceTestSupport {
    private static final String LOCK = """
            version = 7

            [[package]]
            id = "com.example:transitive-lib"
            version = "2.0.0"
            source = "maven-central"
            scope = "compile"
            direct = false
            jar = "com/example/transitive-lib/2.0.0/transitive-lib-2.0.0.jar"
            dependencies = []

            [[conflict]]
            id = "com.example:transitive-lib"
            selected = "2.0.0"
            requested = ["1.0.0", "2.0.0"]
            reason = "newest version wins"
            """;

    private final DependencyQualityCheck check = new DependencyQualityCheck(
            new ZoltLockfileReader(),
            new DependencyPolicyReportService());

    @TempDir
    private Path tempDir;

    @Test
    void warnPolicyReportsMediatedConflictsAsASeverityTaggedWarning() throws IOException {
        QualityCheckResult warning = results("policy-warn", """

                [dependencies.policy]
                conflicts = "warn"
                """).stream()
                .filter(result -> result.status() == QualityCheckStatus.WARNING)
                .findFirst()
                .orElseThrow();

        assertEquals(QualityCheckService.DEPENDENCY_POLICY, warning.id());
        assertEquals(QualityCheckSeverity.WARN, warning.severity());
        assertEquals("[dependencies.policy].conflicts", warning.subject());
        assertEquals(
                "Dependency version conflicts were mediated under `conflicts = \"warn\"`: "
                        + "com.example:transitive-lib selected 2.0.0, requested 1.0.0, 2.0.0.",
                warning.message());
        assertTrue(warning.nextStep().contains("zolt resolve"), warning.nextStep());
        assertTrue(warning.nextStep().contains("conflicts = \"fail\""), warning.nextStep());
    }

    @Test
    void resolvePolicyStaysSilentAboutTheSameMediatedConflicts() throws IOException {
        assertTrue(results("policy-resolve", "").stream()
                .noneMatch(result -> result.status() == QualityCheckStatus.WARNING));
    }

    private List<QualityCheckResult> results(String name, String policy) throws IOException {
        Path projectDir = tempDir.resolve(name);
        ProjectConfig config = parseProject(projectDir, policy);
        Files.writeString(projectDir.resolve("zolt.lock"), LOCK);
        return check.checkPolicy(
                Optional.empty(), projectDir, config, projectDir.resolve("zolt.lock"), false);
    }
}
