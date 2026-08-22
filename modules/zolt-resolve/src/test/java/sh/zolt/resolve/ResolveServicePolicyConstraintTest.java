package sh.zolt.resolve;

import sh.zolt.resolve.support.ResolveServiceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.VersionConflictPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ResolveServicePolicyConstraintTest extends ResolveServiceTestSupport {
    @Test
    void dependencyPolicyExcludesMatchingTransitives() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        ProjectConfig config = config().withDependencyPolicy(new DependencyPolicySettings(
                List.of(new DependencyPolicyExclusion(
                        "com.example",
                        "lib",
                        Optional.of("Use the internal logging bridge instead"))),
                Map.of()));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());

        assertEquals(1, result.resolvedCount());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "app"))));
        assertTrue(lockfile.packages().stream().noneMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "lib"))));
        LockPolicyEffect effect = lockfile.policyEffects().stream()
                .filter(policyEffect -> policyEffect.packageId().equals(new PackageId("com.example", "lib")))
                .findFirst()
                .orElseThrow();
        assertEquals("global-exclusion", effect.kind());
        assertEquals("1.0.0", effect.requestedVersion().orElseThrow());
        assertEquals("com.example:app:1.0.0", effect.source().orElseThrow());
        assertEquals("[dependencies.policy].deny com.example:lib (Use the internal logging bridge instead)", effect.policy());
    }

    @Test
    void strictDependencyConstraintSelectsTransitiveVersionAndRecordsPolicy() {
        addArtifact("com.example", "lib", "2.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>2.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        ProjectConfig config = config().withDependencyPolicy(new DependencyPolicySettings(
                List.of(),
                Map.of(
                        "com.example:lib",
                        new DependencyConstraint(
                                "com.example:lib",
                                "2.0.0",
                                DependencyConstraintKind.STRICT,
                                Optional.of("Enterprise baseline")))));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage lib = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "lib")))
                .findFirst()
                .orElseThrow();

        assertEquals(2, result.resolvedCount());
        assertEquals("2.0.0", lib.version());
        assertEquals(
                List.of("strict-version: com.example:lib requested 1.0.0 -> 2.0.0 (Enterprise baseline)"),
                lib.policies());
        assertTrue(lockfile.policyEffects().stream().anyMatch(effect ->
                "strict-version".equals(effect.kind())
                        && effect.packageId().equals(new PackageId("com.example", "lib"))
                        && effect.requestedVersion().orElseThrow().equals("1.0.0")
                        && effect.source().orElseThrow().equals("com.example:app:1.0.0")
                        && effect.policy().equals("strict-version: com.example:lib requested 1.0.0 -> 2.0.0 (Enterprise baseline)")));
    }

    @Test
    void directDependencyWinsOverStrictTransitiveConstraint() {
        addArtifact("com.example", "lib", "2.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>2.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        ProjectConfig config = configWithDependencies(Map.of(
                        "com.example:app", "1.0.0",
                        "com.example:lib", "1.0.0"))
                .withDependencyPolicy(new DependencyPolicySettings(
                        List.of(),
                        Map.of(
                                "com.example:lib",
                                new DependencyConstraint(
                                        "com.example:lib",
                                        "2.0.0",
                                        DependencyConstraintKind.STRICT,
                                        Optional.empty()))));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage lib = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "lib")))
                .findFirst()
                .orElseThrow();

        assertEquals("1.0.0", lib.version());
        assertTrue(lib.direct());
        assertTrue(lib.policies().isEmpty());
        assertEquals(1, result.conflictCount());
    }

    @Test
    void failOnVersionConflictRejectsRecordedConflictsActionably() {
        addArtifact("com.example", "lib", "2.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>2.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project-fail-on-conflict");
        Path cacheRoot = tempDir.resolve("cache-fail-on-conflict");
        createDirectory(projectDir);
        ProjectConfig config = configWithDependencies(Map.of(
                        "com.example:app", "1.0.0",
                        "com.example:lib", "2.0.0"))
                .withDependencyPolicy(new DependencyPolicySettings(
                        List.of(),
                        Map.of(),
                        true));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, config, cacheRoot));

        assertTrue(exception.getMessage().contains(
                "Dependency version conflicts are disallowed by [dependencies.policy].conflicts"));
        assertTrue(exception.getMessage().contains("com.example:lib selected 2.0.0"));
        assertTrue(exception.getMessage().contains("direct dependency wins"));
        assertTrue(exception.getMessage().contains("1.0.0 [transitive compile]"));
        assertTrue(exception.getMessage().contains("2.0.0 [direct compile]"));
        assertTrue(exception.getMessage().contains("Align the conflicting versions with a [platforms] BOM"));
        assertTrue(exception.getMessage().contains("[dependencies.constraints] strict constraint"));
        assertTrue(!exception.getMessage().contains("[dependencies.policy] strict constraint"));
    }

    /**
     * Design §9.11: {@code warn} keeps the mediated resolution and reports it, so the lock is written
     * and the resolve result carries a structured warning naming the same conflict {@code fail} rejects.
     */
    @Test
    void warnOnVersionConflictKeepsTheLockAndReportsTheMediatedConflict() {
        addArtifact("com.example", "lib", "2.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>2.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project-warn-on-conflict");
        Path cacheRoot = tempDir.resolve("cache-warn-on-conflict");
        createDirectory(projectDir);
        ProjectConfig config = configWithDependencies(Map.of(
                        "com.example:app", "1.0.0",
                        "com.example:lib", "2.0.0"))
                .withDependencyPolicy(new DependencyPolicySettings(
                        List.of(),
                        Map.of(),
                        VersionConflictPolicy.WARN,
                        LicensePolicySettings.defaults()));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);

        assertEquals(1, result.conflictCount());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        String warning = result.warnings().getFirst();
        assertTrue(warning.contains("[dependencies.policy].conflicts = \"warn\""), warning);
        assertTrue(warning.contains("com.example:lib selected 2.0.0"), warning);
        assertTrue(warning.contains("1.0.0 [transitive compile]"), warning);
        assertTrue(Files.isRegularFile(result.lockfilePath()));
    }

    @Test
    void versionConflictPolicyDefaultsToRecordingConflicts() {
        addArtifact("com.example", "lib", "2.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>2.0.0</version>
                </project>
                """);
        Path projectDir = tempDir.resolve("project-record-conflict");
        Path cacheRoot = tempDir.resolve("cache-record-conflict");
        createDirectory(projectDir);
        ProjectConfig config = configWithDependencies(Map.of(
                "com.example:app", "1.0.0",
                "com.example:lib", "2.0.0"));

        ResolveResult result = resolveService.resolve(projectDir, config, cacheRoot);

        assertEquals(1, result.conflictCount());
    }

    @Test
    void dependencyPolicyRejectsExcludedDirectDependency() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        ProjectConfig config = config().withDependencyPolicy(new DependencyPolicySettings(
                List.of(new DependencyPolicyExclusion(
                        "com.example",
                        "app",
                        Optional.of("Application artifact is banned in this policy"))),
                Map.of()));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, config, cacheRoot));

        assertTrue(exception.getMessage().contains("Dependency policy excludes direct dependency `com.example:app`"));
        assertTrue(exception.getMessage().contains("Application artifact is banned in this policy"));
        assertTrue(exception.getMessage().contains("Remove the direct dependency or remove the matching [dependencies.policy].deny entry"));
        assertEquals(0, totalRequests.get());
    }

    @Test
    void dependencyPolicyRejectsWildcardExclusion() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);
        ProjectConfig config = config().withDependencyPolicy(new DependencyPolicySettings(
                List.of(new DependencyPolicyExclusion(
                        "com.example",
                        "*",
                        Optional.empty())),
                Map.of()));

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDir, config, cacheRoot));

        assertTrue(exception.getMessage().contains(
                "Wildcard dependency exclusions are not supported in [dependencies.policy].deny: com.example:*"));
        assertTrue(exception.getMessage().contains("Replace it with explicit group and artifact exclusions"));
    }
}
