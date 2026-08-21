package sh.zolt.resolve;

import sh.zolt.resolve.support.ResolveServiceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Isolated exec-tool closures (Hole 1) must not evade [dependencyPolicy].failOnVersionConflict, and every
 * mediation inside a tool closure must reach zolt.lock attributed to that tool. The fixture drives a real
 * version conflict <em>within</em> one tool's closure: gen-a pins shared 1.0.0, gen-b pins shared 2.0.0, so
 * the tool's own resolution mediates shared (newest wins 2.0.0) with no bearing on the main project graph.
 */
final class ResolveServiceExecToolConflictTest extends ResolveServiceTestSupport {
    private static final PackageId SHARED = new PackageId("com.example", "shared");
    private static final PackageId UTIL = new PackageId("com.example", "util");

    @Test
    void failOnVersionConflictInExecToolClosureFailsActionablyNamingTool() {
        addCodegenArtifacts();
        Path projectDir = tempDir.resolve("fail");
        Path cacheRoot = tempDir.resolve("fail-cache");
        createDirectory(projectDir);
        ProjectConfig config = codegenConfig("codegen", "codegen-step")
                .withDependencyPolicy(new DependencyPolicySettings(List.of(), Map.of(), true));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, config, cacheRoot));

        assertTrue(exception.getMessage().contains("`codegen` exec-tool closure"), exception.getMessage());
        assertTrue(exception.getMessage().contains("disallowed by [dependencyPolicy].failOnVersionConflict"));
        assertTrue(exception.getMessage().contains("com.example:shared selected 2.0.0"));
        assertTrue(exception.getMessage().contains("newest version wins"));
        // The remediation names the tool as well, so the user knows which closure to align.
        assertTrue(exception.getMessage().contains("in the `codegen` exec tool"));
    }

    @Test
    void recordsExecToolClosureConflictAndPolicyEffectsAttributedToTool() {
        addCodegenArtifacts();
        Path projectDir = tempDir.resolve("record");
        Path cacheRoot = tempDir.resolve("record-cache");
        createDirectory(projectDir);
        // failOnVersionConflict stays false (default), but a strict constraint changes a tool transitive, so
        // the closure yields BOTH a recorded conflict (shared) and a policy effect (util 1.0.0 -> 2.0.0).
        ProjectConfig config = codegenConfig("codegen", "codegen-step")
                .withDependencyPolicy(new DependencyPolicySettings(
                        List.of(),
                        Map.of("com.example:util", new DependencyConstraint(
                                "com.example:util", "2.0.0", DependencyConstraintKind.STRICT, Optional.empty())),
                        false));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());

        LockConflict sharedConflict = lockfile.conflicts().stream()
                .filter(conflict -> conflict.packageId().equals(SHARED))
                .findFirst()
                .orElseThrow();
        assertEquals(Optional.of("codegen"), sharedConflict.toolGroup());
        assertEquals("2.0.0", sharedConflict.selectedVersion());
        // The main graph is clean; every recorded conflict is attributed to the codegen closure.
        assertTrue(
                lockfile.conflicts().stream().allMatch(conflict -> conflict.toolGroup().equals(Optional.of("codegen"))),
                "every recorded conflict must be attributed to the codegen tool closure");
        // The tool closure's policy effect is merged into the aggregate audit list.
        assertTrue(lockfile.policyEffects().stream().anyMatch(effect ->
                effect.packageId().equals(UTIL) && "strict-version".equals(effect.kind())));
        // The lockfile text itself carries the tool attribution.
        assertTrue(readLock(result.lockfilePath()).contains("tool = \"codegen\""));
    }

    @Test
    void twoExecToolsWithIndependentConflictsEachGetTheirOwnAuditEntry() {
        addCodegenArtifacts();
        Path projectDir = tempDir.resolve("two-tools");
        Path cacheRoot = tempDir.resolve("two-tools-cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, twoToolConfig(), cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());

        List<Optional<String>> sharedToolGroups = lockfile.conflicts().stream()
                .filter(conflict -> conflict.packageId().equals(SHARED))
                .map(LockConflict::toolGroup)
                .toList();
        assertEquals(2, sharedToolGroups.size());
        assertTrue(sharedToolGroups.contains(Optional.of("codegen1")));
        assertTrue(sharedToolGroups.contains(Optional.of("codegen2")));
        String lockText = readLock(result.lockfilePath());
        assertTrue(lockText.contains("tool = \"codegen1\""));
        assertTrue(lockText.contains("tool = \"codegen2\""));
    }

    @Test
    void execToolConflictLockfileIsByteDeterministicAcrossResolves() {
        addCodegenArtifacts();
        Path firstDir = tempDir.resolve("det-a");
        Path secondDir = tempDir.resolve("det-b");
        createDirectory(firstDir);
        createDirectory(secondDir);
        ProjectConfig config = codegenConfig("codegen", "codegen-step");

        ResolveResult first = resolveService.resolve(firstDir, config, tempDir.resolve("det-a-cache"));
        ResolveResult second = resolveService.resolve(secondDir, config, tempDir.resolve("det-b-cache"));

        assertEquals(readLock(first.lockfilePath()), readLock(second.lockfilePath()));
        assertTrue(readLock(first.lockfilePath()).contains("tool = \"codegen\""));
    }

    private ProjectConfig codegenConfig(String toolName, String stepId) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [generated.tools.%s]
                kind = "jvm"
                coordinates = [
                    { coordinate = "com.example:gen-a", version = "1.0.0" },
                    { coordinate = "com.example:gen-b", version = "1.0.0" },
                ]
                mainClass = "com.example.Gen"

                [generated.main.%s]
                kind = "exec"
                tool = "%s"
                inputs = ["src/main/gen/input.txt"]
                output = "target/generated/sources/%s"
                produces = "java-sources"
                """.formatted(baseUri, toolName, stepId, toolName, stepId));
    }

    private ProjectConfig twoToolConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [generated.tools.codegen1]
                kind = "jvm"
                coordinates = [
                    { coordinate = "com.example:gen-a", version = "1.0.0" },
                    { coordinate = "com.example:gen-b", version = "1.0.0" },
                ]
                mainClass = "com.example.Gen"

                [generated.tools.codegen2]
                kind = "jvm"
                coordinates = [
                    { coordinate = "com.example:gen-a", version = "1.0.0" },
                    { coordinate = "com.example:gen-b", version = "1.0.0" },
                ]
                mainClass = "com.example.Gen"

                [generated.main.step1]
                kind = "exec"
                tool = "codegen1"
                inputs = ["src/main/gen/input.txt"]
                output = "target/generated/sources/step1"
                produces = "java-sources"

                [generated.main.step2]
                kind = "exec"
                tool = "codegen2"
                inputs = ["src/main/gen/input.txt"]
                output = "target/generated/sources/step2"
                produces = "java-sources"
                """.formatted(baseUri));
    }

    private void addCodegenArtifacts() {
        addArtifact("com.example", "gen-a", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>gen-a</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>shared</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>util</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "gen-b", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>gen-b</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>shared</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "shared", "1.0.0", simplePom("com.example", "shared", "1.0.0"));
        addArtifact("com.example", "shared", "2.0.0", simplePom("com.example", "shared", "2.0.0"));
        addArtifact("com.example", "util", "1.0.0", simplePom("com.example", "util", "1.0.0"));
        addArtifact("com.example", "util", "2.0.0", simplePom("com.example", "util", "2.0.0"));
    }

    private static String readLock(Path lockfilePath) {
        try {
            return Files.readString(lockfilePath);
        } catch (IOException exception) {
            throw new AssertionError("Could not read " + lockfilePath, exception);
        }
    }
}
