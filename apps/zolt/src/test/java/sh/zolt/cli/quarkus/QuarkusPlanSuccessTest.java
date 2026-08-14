package sh.zolt.cli.quarkus;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.ContentAddressedLockTestSupport.cachedJar;
import static sh.zolt.cli.quarkus.QuarkusPlanCommandTestSupport.createJarWithTextEntry;
import static sh.zolt.cli.quarkus.QuarkusPlanCommandTestSupport.enableQuarkus;
import static sh.zolt.cli.quarkus.QuarkusPlanCommandTestSupport.quarkusInputFingerprint;
import static sh.zolt.cli.quarkus.QuarkusPlanCommandTestSupport.writeProjectConfig;
import static sh.zolt.cli.quarkus.QuarkusPlanCommandTestSupport.writeQuarkusAugmentationMetadata;
import static sh.zolt.cli.quarkus.QuarkusPlanCommandTestSupport.writeQuarkusPlanLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuarkusPlanSuccessTest {
    @TempDir
    private Path tempDir;

    @Test
    void quarkusPlanPrintsAugmentationInputsFromLockfile() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeProjectConfig(projectDir, ".zolt/build");
        enableQuarkus(projectDir);
        Path root = projectDir.toAbsolutePath().normalize();
        Path legacyRuntimeJar = cacheRoot.resolve("io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar");
        createJarWithTextEntry(
                legacyRuntimeJar,
                "META-INF/quarkus-extension.properties",
                "deployment-artifact=io.quarkus:quarkus-rest-deployment:3.33.0\n");
        writeQuarkusPlanLockfile(projectDir, cacheRoot);
        Path runtimeJar = cachedJar(projectDir.resolve("zolt.lock"), cacheRoot, "io.quarkus:quarkus-rest");
        Path deploymentJar = cachedJar(
                projectDir.resolve("zolt.lock"), cacheRoot, "io.quarkus:quarkus-rest-deployment");

        CommandResult result = execute(
                "quarkus",
                "plan",
                "--directory", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        String inputFingerprint = quarkusInputFingerprint(result.stdout());
        assertTrue(inputFingerprint.matches("sha256:[0-9a-f]{64}"));
        assertEquals("""
                Quarkus augmentation plan
                Status: inputs resolved; augmentation runner not implemented yet
                Application classes: %s
                Package target: fast-jar
                Augmentation output: %s
                Package output: %s
                Input fingerprint: %s
                Augmentation metadata: missing (%s)
                Runtime classpath entries: 1
                  %s
                Deployment classpath entries: 1
                  %s
                Quarkus extensions: 1
                  io.quarkus:quarkus-rest -> io.quarkus:quarkus-rest-deployment:3.33.0
                    runtime jar: %s
                    deployment jar: %s
                Next: implement the Zolt-owned Quarkus augmentation runner with these inputs.
                """.formatted(
                root.resolve(".zolt/build/classes"),
                root.resolve(".zolt/build/quarkus"),
                root.resolve(".zolt/build/quarkus-app"),
                inputFingerprint,
                root.resolve(".zolt/build/quarkus/zolt-augmentation.properties"),
                runtimeJar,
                deploymentJar,
                runtimeJar,
                deploymentJar), result.stdout());
    }

    @Test
    void quarkusPlanReportsCurrentAugmentationMetadata() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeProjectConfig(projectDir);
        enableQuarkus(projectDir);
        Path runtimeJar = cacheRoot.resolve("io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar");
        createJarWithTextEntry(
                runtimeJar,
                "META-INF/quarkus-extension.properties",
                "deployment-artifact=io.quarkus:quarkus-rest-deployment:3.33.0\n");
        writeQuarkusPlanLockfile(projectDir, cacheRoot);
        String fingerprint = quarkusInputFingerprint(execute(
                "quarkus",
                "plan",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString()).stdout());
        writeQuarkusAugmentationMetadata(projectDir, fingerprint);

        CommandResult result = execute(
                "quarkus",
                "plan",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Augmentation metadata: current"));
        assertTrue(result.stdout().contains("recorded " + fingerprint));
    }

    @Test
    void quarkusPlanReportsStaleAugmentationMetadata() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        Path cacheRoot = tempDir.resolve("cache");
        writeProjectConfig(projectDir);
        enableQuarkus(projectDir);
        Path runtimeJar = cacheRoot.resolve("io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar");
        createJarWithTextEntry(
                runtimeJar,
                "META-INF/quarkus-extension.properties",
                "deployment-artifact=io.quarkus:quarkus-rest-deployment:3.33.0\n");
        writeQuarkusPlanLockfile(projectDir, cacheRoot);
        writeQuarkusAugmentationMetadata(projectDir, "sha256:" + "0".repeat(64));

        CommandResult result = execute(
                "quarkus",
                "plan",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Augmentation metadata: stale"));
        assertTrue(result.stdout().contains("recorded sha256:" + "0".repeat(64)));
    }
}
