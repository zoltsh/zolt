package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintService;
import sh.zolt.classpath.Classpath;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tests-JAR evidence gate is the worst place for the member-lock defect to hide, because it does
 * both things the invariant forbids: it fingerprints against the lockfile, AND — when it has to
 * reconstruct the test compile classpath — it READS it.
 *
 * <p>So a stray {@code <member>/zolt.lock} does not merely move a hash. Its package list becomes the
 * classpath the gate compares against the one the compile actually used, and the mismatch is reported
 * as stale test bytecode: {@code zolt package} refuses to build a tests JAR and tells the user to run
 * {@code zolt test} again, for a member whose test classes are perfectly current. The remedy it
 * suggests cannot help, because recompiling changes nothing about the file that caused it.
 *
 * <p>Design §4.5: the gate must be handed the workspace root's lockfile and never derive one from the
 * member directory.
 */
final class StrayMemberLockTestsJarEvidenceTest {
    /** Parses as a lock, names a package the member never depended on. */
    private static final String POISONED_MEMBER_LOCK = """
            version = 7

            [[package]]
            id = "com.example:phantom"
            version = "9.9.9"
            source = "maven-central"
            scope = "main"
            direct = true
            jar = "com/example/phantom/9.9.9/phantom-9.9.9.jar"
            dependencies = []
            """;

    @TempDir
    private Path tempDir;

    @Test
    void strayMemberLockDoesNotInvalidateTestsJarEvidence() throws IOException {
        Fixture fixture = fixture();

        assertTrue(
                Files.isRegularFile(fixture.workspaceRoot.resolve("zolt.lock")),
                "the workspace root owns the authoritative lock");
        assertDoesNotThrow(fixture::requireCurrent, "evidence is current before anything is planted");

        Files.writeString(fixture.memberDirectory.resolve("zolt.lock"), POISONED_MEMBER_LOCK);

        assertDoesNotThrow(
                fixture::requireCurrent,
                "a member-local zolt.lock is neither fingerprinted nor read, so tests-JAR evidence stands");
    }

    /**
     * A two-member workspace whose test evidence was captured against the ROOT lock — which is what
     * the test-compile lane writes once it is handed the authoritative path.
     */
    private Fixture fixture() throws IOException {
        Path workspaceRoot = tempDir.resolve("platform");
        Files.createDirectories(workspaceRoot);
        Files.writeString(workspaceRoot.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*", "modules/*"]
                """);
        Files.writeString(workspaceRoot.resolve("zolt.lock"), "version = 7\n");
        member(workspaceRoot, "modules/core", "core");
        Path memberDirectory = member(workspaceRoot, "apps/api", "api");

        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(cacheRoot);
        ProjectConfig config = new ManifestProjectConfigLoader()
                .load(memberDirectory.resolve("zolt.toml"));
        ProjectBuildContext context = ProjectBuildContext.member(
                memberDirectory, workspaceRoot.resolve("zolt.lock"), "apps/api");

        // What the compile lane records: the canonical test fingerprint, taken against the workspace
        // root's lock and an empty dependency classpath (the root lock declares no packages).
        new BuildFingerprintService().writeTestCompileFingerprint(
                memberDirectory,
                config,
                context.lockfilePath(),
                new SourceDiscoverer().discover(memberDirectory, config.build()),
                new GeneratedSourceProducerFingerprintService()
                        .fingerprintsTest(memberDirectory, config, List.of()),
                new Classpath(List.of(memberDirectory.resolve("target/classes"))),
                new Classpath(List.of()),
                memberDirectory.resolve("target/test-classes"),
                memberDirectory.resolve("target/generated/test-sources/annotations"));

        return new Fixture(
                workspaceRoot,
                memberDirectory,
                context,
                config,
                cacheRoot,
                new PackageTestCompileGate(new ZoltLockfileReader(), new ClasspathBuilder()));
    }

    private static Path member(Path workspaceRoot, String memberPath, String name) throws IOException {
        Path directory = workspaceRoot.resolve(memberPath);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [package]
                testJar = true
                """.formatted(name, currentJavaMajorVersion()));
        write(directory, "src/main/java/com/example/Component.java",
                "package com.example; public final class Component {}\n");
        write(directory, "src/test/java/com/example/ComponentTest.java",
                "package com.example; final class ComponentTest {}\n");
        write(directory, "target/classes/com/example/Component.class", "main bytecode");
        write(directory, "target/test-classes/com/example/ComponentTest.class", "test bytecode");
        return directory;
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }

    private static void write(Path directory, String relative, String content) throws IOException {
        Path path = directory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private record Fixture(
            Path workspaceRoot,
            Path memberDirectory,
            ProjectBuildContext context,
            ProjectConfig config,
            Path cacheRoot,
            PackageTestCompileGate gate) {
        /**
         * Neither classpath packages nor classpaths are supplied, so the gate takes the branch that
         * reconstructs the test compile classpath from the lockfile — the one that reads it.
         */
        void requireCurrent() {
            gate.requireCurrent(
                    context,
                    config,
                    new BuildResult(
                            Optional.empty(),
                            1,
                            0,
                            memberDirectory.resolve("target/classes"),
                            ""),
                    Optional.of(cacheRoot),
                    Optional.empty(),
                    Optional.empty());
        }
    }
}
