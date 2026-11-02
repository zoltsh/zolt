package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class ResolveServiceKotlinToolchainTest extends ResolveServiceTestSupport {
    private static final PackageId KOTLIN_COMPILER =
            new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable");
    private static final PackageId KOTLIN_STDLIB =
            new PackageId("org.jetbrains.kotlin", "kotlin-stdlib");
    private static final PackageId KOTLIN_KAPT =
            new PackageId("org.jetbrains.kotlin", "kotlin-annotation-processing-embeddable");
    private static final PackageId PROCESSOR =
            new PackageId("com.example", "processor");
    private static final String APPLICATION_STDLIB_VERSION = "1.9.24";

    @Test
    void addsRefreshesAndRemovesAnIsolatedKotlinCompilerClosure() throws IOException {
        addArtifact(
                "org.jetbrains.kotlin",
                "kotlin-stdlib",
                APPLICATION_STDLIB_VERSION,
                simplePom("org.jetbrains.kotlin", "kotlin-stdlib", APPLICATION_STDLIB_VERSION));
        addKotlinArtifacts("2.2.0");
        addKotlinArtifacts("2.2.10");
        Path projectDirectory = tempDir.resolve("kotlin-toolchain");
        Path cacheRoot = tempDir.resolve("kotlin-toolchain-cache");
        createDirectory(projectDirectory);

        ResolveResult initial = resolveService.resolve(projectDirectory, config(""), cacheRoot);
        ZoltLockfile initialLock = lockfileReader.read(initial.lockfilePath());
        assertApplicationStdlib(initialLock);
        assertNoKotlinTool(initialLock);

        String initialText = Files.readString(initial.lockfilePath());
        ResolveException addedToolchain = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDirectory, config("2.2.0"), cacheRoot, true));
        assertTrue(addedToolchain.getMessage().contains("zolt.lock is out of date"));
        assertTrue(addedToolchain.getMessage().contains("toolchain.kotlin"));
        assertEquals(initialText, Files.readString(initial.lockfilePath()));

        ResolveResult added = resolveService.resolve(projectDirectory, config("2.2.0"), cacheRoot);
        ZoltLockfile addedLock = lockfileReader.read(added.lockfilePath());
        assertApplicationStdlib(addedLock);
        assertCompilerClosure(addedLock, "2.2.0");

        String addedText = Files.readString(added.lockfilePath());
        ResolveException changedToolchain = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDirectory, config("2.2.10"), cacheRoot, true));
        assertTrue(changedToolchain.getMessage().contains("zolt.lock is out of date"));
        assertTrue(changedToolchain.getMessage().contains("toolchain.kotlin"));
        assertEquals(addedText, Files.readString(added.lockfilePath()));

        ResolveResult changed = resolveService.resolve(projectDirectory, config("2.2.10"), cacheRoot);
        ZoltLockfile changedLock = lockfileReader.read(changed.lockfilePath());
        assertApplicationStdlib(changedLock);
        assertCompilerClosure(changedLock, "2.2.10");
        assertFalse(changedLock.packages().stream()
                .anyMatch(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_KOTLIN
                        && lockPackage.version().equals("2.2.0")));

        String changedText = Files.readString(changed.lockfilePath());
        ResolveException removedToolchain = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(projectDirectory, config(""), cacheRoot, true));
        assertTrue(removedToolchain.getMessage().contains("zolt.lock is out of date"));
        assertTrue(removedToolchain.getMessage().contains("toolchain.kotlin"));
        assertEquals(changedText, Files.readString(changed.lockfilePath()));

        ResolveResult removed = resolveService.resolve(projectDirectory, config(""), cacheRoot);
        ZoltLockfile removedLock = lockfileReader.read(removed.lockfilePath());
        assertApplicationStdlib(removedLock);
        assertNoKotlinTool(removedLock);
    }

    @Test
    void resolvesKaptBesideProcessorsWithoutLeakingTooling() throws IOException {
        addArtifact(
                "org.jetbrains.kotlin",
                "kotlin-stdlib",
                APPLICATION_STDLIB_VERSION,
                simplePom("org.jetbrains.kotlin", "kotlin-stdlib", APPLICATION_STDLIB_VERSION));
        addKotlinArtifacts("2.2.0");
        addArtifact(
                PROCESSOR.groupId(),
                PROCESSOR.artifactId(),
                "1.0.0",
                simplePom(PROCESSOR.groupId(), PROCESSOR.artifactId(), "1.0.0"));
        Path projectDirectory = tempDir.resolve("kapt-toolchain");
        Path cacheRoot = tempDir.resolve("kapt-toolchain-cache");
        createDirectory(projectDirectory);

        ResolveResult result = resolveService.resolve(
                projectDirectory,
                config("2.2.0", true),
                cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());

        assertCompilerClosure(lockfile, "2.2.0");
        LockPackage kapt = packageEntry(
                lockfile, KOTLIN_KAPT, DependencyScope.TOOL_KOTLIN, "2.2.0");
        LockPackage processor = packageEntry(
                lockfile, PROCESSOR, DependencyScope.PROCESSOR, "1.0.0");
        assertTrue(kapt.direct());
        assertTrue(kapt.dependencies().isEmpty());
        assertTrue(processor.direct());
        assertFalse(lockfile.packages().stream()
                .anyMatch(lockPackage -> lockPackage.packageId().equals(KOTLIN_KAPT)
                        && lockPackage.scope() != DependencyScope.TOOL_KOTLIN));
    }

    private void addKotlinArtifacts(String version) {
        addArtifact("org.jetbrains.kotlin", "kotlin-compiler-embeddable", version, """
                <project>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-compiler-embeddable</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.jetbrains.kotlin</groupId>
                      <artifactId>kotlin-stdlib</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version, version));
        addArtifact(
                "org.jetbrains.kotlin",
                "kotlin-stdlib",
                version,
                simplePom("org.jetbrains.kotlin", "kotlin-stdlib", version));
        addArtifact(
                KOTLIN_KAPT.groupId(),
                KOTLIN_KAPT.artifactId(),
                version,
                simplePom(KOTLIN_KAPT.groupId(), KOTLIN_KAPT.artifactId(), version));
    }

    private static void assertCompilerClosure(ZoltLockfile lockfile, String version) {
        LockPackage compiler = packageEntry(
                lockfile, KOTLIN_COMPILER, DependencyScope.TOOL_KOTLIN, version);
        LockPackage stdlib = packageEntry(
                lockfile, KOTLIN_STDLIB, DependencyScope.TOOL_KOTLIN, version);

        assertTrue(compiler.direct());
        assertFalse(stdlib.direct());
        assertTrue(compiler.toolGroups().isEmpty());
        assertTrue(stdlib.toolGroups().isEmpty());
        assertEquals(
                List.of("org.jetbrains.kotlin:kotlin-stdlib:" + version + ":jar:tool-kotlin"),
                compiler.dependencies());
        assertTrue(stdlib.dependencies().isEmpty());
        assertEquals(2, lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(KOTLIN_STDLIB))
                .count());
        assertFalse(lockfile.conflicts().stream()
                .anyMatch(conflict -> conflict.packageId().equals(KOTLIN_STDLIB)));
    }

    private static void assertApplicationStdlib(ZoltLockfile lockfile) {
        LockPackage stdlib = packageEntry(
                lockfile,
                KOTLIN_STDLIB,
                DependencyScope.COMPILE,
                APPLICATION_STDLIB_VERSION);
        assertTrue(stdlib.direct());
    }

    private static void assertNoKotlinTool(ZoltLockfile lockfile) {
        assertFalse(lockfile.packages().stream()
                .anyMatch(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_KOTLIN));
    }

    private static LockPackage packageEntry(
            ZoltLockfile lockfile,
            PackageId packageId,
            DependencyScope scope,
            String version) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .filter(lockPackage -> lockPackage.scope() == scope)
                .filter(lockPackage -> lockPackage.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing " + packageId + ":" + version + " in " + scope.lockfileName()));
    }

    private ProjectConfig config(String kotlinVersion) {
        return config(kotlinVersion, false);
    }

    private ProjectConfig config(
            String kotlinVersion,
            boolean processor) {
        String toolchain = kotlinVersion.isBlank()
                ? ""
                : """

                  [toolchain.kotlin]
                  version = "%s"
                  """.formatted(kotlinVersion);
        String processors = processor
                ? """

                  [dependencies.processor]
                  "com.example:processor" = "1.0.0"
                  """
                : "";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-toolchain"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                %s
                %s
                """.formatted(
                baseUri,
                APPLICATION_STDLIB_VERSION,
                toolchain,
                processors));
    }
}
