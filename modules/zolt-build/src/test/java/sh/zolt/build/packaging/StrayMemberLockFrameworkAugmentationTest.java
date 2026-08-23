package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.packageevidence.PackageArchiveDigests;
import sh.zolt.build.packageplan.PackageInputBudget;
import sh.zolt.build.packageplan.PackageInputSnapshot;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.framework.FrameworkPackageResult;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A framework adapter is the one core operation the build does not own. It plans its own dependency
 * closure, in another module, behind an interface — so whatever that interface fails to hand it, the
 * adapter has to go and find. When {@code augmentIfEnabled} took a project directory and nothing
 * else, a Quarkus member packaged through the workspace had exactly one place to look for a lockfile:
 * its own directory. That is the member-local {@code zolt.lock} design §4.5 says no command may
 * consume, and unlike the fingerprint sites it would not merely change a hash — it would change the
 * dependency closure baked into the runner jar.
 *
 * <p>This is the reachable half of the seam. {@code zolt build} and {@code zolt run} route a member
 * to its workspace service before reaching their augmenters, so those two lanes cannot get here
 * today; {@code zolt package} of a quarkus-mode member does, through
 * {@code WorkspacePackageService → PackageService → PackageArchiveModePackager}.
 */
final class StrayMemberLockFrameworkAugmentationTest {
    /** Parses as a lock, and names a dependency that would land in the runner jar if it were read. */
    private static final String POISONED_MEMBER_LOCK = """
            version = 7

            [[dependencyRoot]]
            member = "."
            id = "com.example:phantom"
            version = "9.9.9"
            lane = "implementation"
            resolvedScope = "compile"

            [[package]]
            id = "com.example:phantom"
            version = "9.9.9"
            source = "maven-central"
            scope = "compile"
            direct = true
            jar = "com/example/phantom/9.9.9/phantom-9.9.9.jar"
            dependencies = []
            """;

    @TempDir
    private Path tempDir;

    @Test
    void strayMemberLockDoesNotReachTheFrameworkAugmenter() throws IOException {
        Fixture fixture = fixture();

        PackageResult before = fixture.packageMember();
        ProjectBuildContext seenBefore = fixture.lastContext();
        List<String> closureBefore = fixture.packagedLibraryJars();

        Files.writeString(fixture.memberDirectory.resolve("zolt.lock"), POISONED_MEMBER_LOCK);
        PackageResult after = fixture.packageMember();
        ProjectBuildContext seenAfter = fixture.lastContext();

        assertEquals(
                seenBefore,
                seenAfter,
                "the adapter is handed the same context whether or not a member-local lock exists");
        assertEquals(
                closureBefore,
                fixture.packagedLibraryJars(),
                "the packaged runtime closure is unchanged by a lockfile no command may consume");
        assertEquals(
                before.mode() + "|" + before.entryCount() + "|" + before.jarPath(),
                after.mode() + "|" + after.entryCount() + "|" + after.jarPath(),
                "a member-local zolt.lock changes nothing about the packaged output");
        assertEquals(2, fixture.contexts.size());
    }

    /**
     * The control, and the point of the seam change: the adapter is told which lockfile is
     * authoritative, so it never has to guess — and what it is told is the workspace root's.
     */
    @Test
    void frameworkAugmenterReceivesTheWorkspaceLockNotTheMemberDirectory() throws IOException {
        Fixture fixture = fixture();

        fixture.packageMember();
        ProjectBuildContext seen = fixture.lastContext();

        assertEquals(fixture.workspaceRoot.resolve("zolt.lock"), seen.lockfilePath());
        assertNotEquals(fixture.memberDirectory.resolve("zolt.lock"), seen.lockfilePath());
        assertEquals(fixture.memberDirectory, seen.projectRoot());
        assertEquals("apps/api", seen.memberPath());
        assertTrue(
                Files.isRegularFile(seen.lockfilePath()),
                "the authoritative lock is the one that actually exists");
    }

    /** A two-member workspace whose {@code apps/api} member packages in quarkus mode. */
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
        member(workspaceRoot, "modules/core", "core", PackageMode.THIN);
        Path memberDirectory = member(workspaceRoot, "apps/api", "api", PackageMode.QUARKUS);

        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(cacheRoot);
        List<ProjectBuildContext> contexts = new ArrayList<>();
        return new Fixture(
                workspaceRoot,
                memberDirectory,
                cacheRoot,
                new ManifestProjectConfigLoader().load(memberDirectory.resolve("zolt.toml")),
                contexts,
                new PackagePrimaryArtifactAssembler(
                        new ManifestGenerator(),
                        new ZoltLockfileReader(),
                        new ClasspathBuilder(),
                        recordingAugmenter(contexts)));
    }

    /**
     * Stands in for the Quarkus adapter, and reads the lockfile the way the real one does: the closure
     * it finds there becomes the {@code lib/} contents of the package it lays out. That is what makes
     * this an output test rather than an argument test — if the seam points the adapter at the member
     * directory, a planted lock does not just look different, it ships different jars.
     */
    private static FrameworkPackageAugmenter recordingAugmenter(List<ProjectBuildContext> contexts) {
        return (context, config, cacheRoot) -> {
            contexts.add(context);
            Path packageDirectory = context.projectRoot().resolve("target/quarkus-app");
            Path runnerJar = packageDirectory.resolve("quarkus-run.jar");
            Path libraryDirectory = packageDirectory.resolve("lib");
            try {
                deleteRecursively(packageDirectory);
                Files.createDirectories(libraryDirectory);
                try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(runnerJar))) {
                    // An empty deterministic runner is enough for the packaging contract.
                }
                for (String dependency : lockedClosure(context.lockfilePath())) {
                    try (JarOutputStream ignored =
                            new JarOutputStream(Files.newOutputStream(libraryDirectory.resolve(dependency)))) {
                        // One jar per locked package, exactly as a real augmentation lays them out.
                    }
                }
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            return Optional.of(new FrameworkPackageResult(
                    PackageMode.QUARKUS, packageDirectory, runnerJar, "target/quarkus-app/app"));
        };
    }

    /** The runtime closure the adapter would bake into the package, from the lock it was pointed at. */
    private static List<String> lockedClosure(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return List.of();
        }
        return new ZoltLockfileReader().read(lockfilePath).packages().stream()
                .map(locked -> locked.packageId().artifactId() + "-" + locked.version() + ".jar")
                .sorted()
                .toList();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static Path member(Path workspaceRoot, String memberPath, String name, PackageMode mode)
            throws IOException {
        Path directory = workspaceRoot.resolve(memberPath);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [package]
                mode = "%s"
                """.formatted(name, currentJavaMajorVersion(), mode.configValue()));
        Path mainClass = directory.resolve("target/classes/com/example/Component.class");
        Files.createDirectories(mainClass.getParent());
        Files.writeString(mainClass, "main bytecode");
        return directory;
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }

    private record Fixture(
            Path workspaceRoot,
            Path memberDirectory,
            Path cacheRoot,
            ProjectConfig config,
            List<ProjectBuildContext> contexts,
            PackagePrimaryArtifactAssembler assembler) {
        /** Exactly what WorkspacePackageService hands PackageService for this member. */
        PackageResult packageMember() throws IOException {
            Path outputDirectory = memberDirectory.resolve("target/classes");
            return assembler.assemble(
                    ProjectBuildContext.member(
                            memberDirectory, workspaceRoot.resolve("zolt.lock"), "apps/api"),
                    config,
                    new BuildResult(Optional.empty(), 1, 0, outputDirectory, ""),
                    Optional.of(cacheRoot),
                    Optional.empty(),
                    Optional.empty(),
                    PackageInputSnapshot.of(outputDirectory, PackageInputBudget.streaming()),
                    new PackageArchiveDigests());
        }

        /** What the augmentation actually laid down, which is the observable the invariant protects. */
        List<String> packagedLibraryJars() throws IOException {
            Path libraryDirectory = memberDirectory.resolve("target/quarkus-app/lib");
            try (var paths = Files.list(libraryDirectory)) {
                return paths.map(path -> path.getFileName().toString()).sorted().toList();
            }
        }

        ProjectBuildContext lastContext() {
            assertNotNull(contexts, "the augmenter ran");
            assertTrue(!contexts.isEmpty(), "the augmenter ran");
            return contexts.get(contexts.size() - 1);
        }
    }
}
