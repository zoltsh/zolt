package sh.zolt.build.generatedsource.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

final class KspJvmToolchainResolverTest {
    private static final String KOTLIN_VERSION = "2.2.0";
    private static final String KSP_VERSION = "2.2.0-2.0.2";
    private static final String ENGINE_GROUP = "ksp:main:engine";
    private static final String PROCESSOR_GROUP = "ksp:main:processors";
    private static final PackageId ENGINE_SUPPORT =
            new PackageId("com.google.devtools.ksp", "symbol-processing-api");
    private static final PackageId PROCESSOR =
            new PackageId("com.example", "demo-ksp-processor");
    private static final PackageId PROCESSOR_SUPPORT =
            new PackageId("com.example", "processor-support");

    @Test
    void resolvesSeparatedVerifiedClosuresWithRelocatableIdentity(@TempDir Path temporary)
            throws IOException {
        Fixture first = fixture(temporary.resolve("first"), "processor-v1");
        Fixture relocated = fixture(temporary.resolve("relocated"), "processor-v1");

        KspJvmToolchain original = resolve(first.packages());
        KspJvmToolchain moved = resolve(relocated.packages());

        assertEquals(KSP_VERSION, original.version());
        assertEquals(
                List.of(first.engine(), first.engineSupport()),
                original.engineClasspath());
        assertEquals(
                List.of(first.processor(), first.processorSupport()),
                original.processorClasspath());
        assertEquals(original.identity(), moved.identity());
        assertFalse(original.identity().contains(temporary.toString()));
        assertTrue(original.identity().startsWith("ksp:" + KSP_VERSION + "|engine=sha256:"));
    }

    @Test
    void rejectsVersionMismatchAndCollapsedToolGroups(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary, "processor");
        KspJvmToolchainResolver resolver = new KspJvmToolchainResolver();

        assertMessageContains(
                () -> resolver.resolve(
                        fixture.packages(), ENGINE_GROUP, PROCESSOR_GROUP, "2.2.1", KSP_VERSION),
                "not built for configured Kotlin `2.2.1`");
        assertMessageContains(
                () -> resolver.resolve(
                        fixture.packages(), ENGINE_GROUP, ENGINE_GROUP, KOTLIN_VERSION, KSP_VERSION),
                "engine and processor closures use the same tool group");
    }

    @Test
    void rejectsEngineWithoutCliAndProcessorWithoutProvider(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary.resolve("valid"), "processor");
        Path invalidEngine = jar(temporary.resolve("invalid-engine.jar"), "content.txt", "engine");
        verify(invalidEngine, KspJvmToolchainResolver.ENGINE, KSP_VERSION, true);
        List<ResolvedClasspathPackage> missingEngineEntry = replace(
                fixture.packages(), KspJvmToolchainResolver.ENGINE, invalidEngine, ENGINE_GROUP);

        assertMessageContains(
                () -> resolve(missingEngineEntry),
                "does not contain " + KspJvmToolchainResolver.ENGINE_ENTRY);

        Path invalidProcessor = jar(temporary.resolve("invalid-processor.jar"), "content.txt", "processor");
        verify(invalidProcessor, PROCESSOR, "1.0.0", true);
        List<ResolvedClasspathPackage> missingProvider = replace(
                fixture.packages(), PROCESSOR, invalidProcessor, PROCESSOR_GROUP);
        assertMessageContains(
                () -> resolve(missingProvider),
                "does not contain " + KspJvmToolchainResolver.PROVIDER_ENTRY);
    }

    @Test
    void processorContentParticipatesInIdentity(@TempDir Path temporary) throws IOException {
        KspJvmToolchain first = resolve(fixture(temporary.resolve("first"), "processor-v1").packages());
        KspJvmToolchain changed = resolve(fixture(temporary.resolve("changed"), "processor-v2").packages());

        assertFalse(first.identity().equals(changed.identity()));
    }

    @Test
    void rejectsArtifactsOutsideTheIntegrityVerifierBoundary(@TempDir Path temporary)
            throws IOException {
        Fixture fixture = fixture(temporary.resolve("valid"), "processor");
        Path unverified = jar(
                temporary.resolve("unverified-support.jar"),
                "support.class",
                "unverified");
        List<ResolvedClasspathPackage> packages = replace(
                fixture.packages(), PROCESSOR_SUPPORT, unverified, PROCESSOR_GROUP);

        assertMessageContains(
                () -> resolve(packages),
                "could not hash verified tool artifact " + PROCESSOR_SUPPORT);
    }

    private static KspJvmToolchain resolve(List<ResolvedClasspathPackage> packages) {
        return new KspJvmToolchainResolver().resolve(
                packages, ENGINE_GROUP, PROCESSOR_GROUP, KOTLIN_VERSION, KSP_VERSION);
    }

    private static Fixture fixture(Path directory, String processorContent) throws IOException {
        Files.createDirectories(directory);
        Path engine = jar(directory.resolve("engine.jar"), KspJvmToolchainResolver.ENGINE_ENTRY, "engine");
        Path engineSupport = jar(directory.resolve("engine-support.jar"), "api.class", "api");
        Path processor = jar(
                directory.resolve("processor.jar"),
                KspJvmToolchainResolver.PROVIDER_ENTRY,
                "com.example.DemoProcessorProvider\n" + processorContent);
        Path processorSupport = jar(directory.resolve("processor-support.jar"), "support.class", "support");
        verify(engine, KspJvmToolchainResolver.ENGINE, KSP_VERSION, true);
        verify(engineSupport, ENGINE_SUPPORT, KSP_VERSION, false);
        verify(processor, PROCESSOR, "1.0.0", true);
        verify(processorSupport, PROCESSOR_SUPPORT, "1.0.0", false);
        return new Fixture(
                engine.toAbsolutePath().normalize(),
                engineSupport.toAbsolutePath().normalize(),
                processor.toAbsolutePath().normalize(),
                processorSupport.toAbsolutePath().normalize(),
                List.of(
                        dependency(KspJvmToolchainResolver.ENGINE, KSP_VERSION, true, engine, ENGINE_GROUP),
                        dependency(ENGINE_SUPPORT, KSP_VERSION, false, engineSupport, ENGINE_GROUP),
                        dependency(PROCESSOR, "1.0.0", true, processor, PROCESSOR_GROUP),
                        dependency(PROCESSOR_SUPPORT, "1.0.0", false, processorSupport, PROCESSOR_GROUP)));
    }

    private static List<ResolvedClasspathPackage> replace(
            List<ResolvedClasspathPackage> packages,
            PackageId target,
            Path replacement,
            String group) {
        return packages.stream()
                .map(dependency -> dependency.resolvedPackage().packageId().equals(target)
                        ? dependency(
                                target,
                                dependency.resolvedPackage().selectedVersion(),
                                dependency.resolvedPackage().direct(),
                                replacement,
                                group)
                        : dependency)
                .toList();
    }

    private static ResolvedClasspathPackage dependency(
            PackageId id,
            String version,
            boolean direct,
            Path jar,
            String group) {
        NestedArtifactIdentity identity = new NestedArtifactIdentity(
                id.groupId(), id.artifactId(), version, "jar", Optional.empty(),
                NestedArtifactIdentity.SourceKind.EXTERNAL);
        return new ResolvedClasspathPackage(
                new ResolvedPackage(id, version, direct, jar.resolveSibling("artifact.pom"), jar, identity),
                DependencyScope.TOOL_EXEC,
                List.of(group));
    }

    private static Path jar(Path path, String entryName, String content) throws IOException {
        Files.createDirectories(path.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path.toAbsolutePath().normalize();
    }

    private static void verify(
            Path jar,
            PackageId packageId,
            String version,
            boolean direct) throws IOException {
        Path cacheRoot = jar.getParent().toAbsolutePath().normalize();
        LockPackage lockPackage = new LockPackage(
                packageId,
                version,
                "central",
                DependencyScope.TOOL_EXEC,
                direct,
                Optional.of(jar.getFileName().toString()),
                Optional.empty(),
                Optional.of(sha256(jar)),
                Optional.empty(),
                List.of());
        new ArtifactIntegrityVerifier().verify(
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(lockPackage), List.of()),
                cacheRoot);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertMessageContains(Runnable action, String expected) {
        BuildException exception = assertThrows(BuildException.class, action::run);
        assertTrue(exception.getMessage().contains(expected), exception::getMessage);
    }

    private record Fixture(
            Path engine,
            Path engineSupport,
            Path processor,
            Path processorSupport,
            List<ResolvedClasspathPackage> packages) {
    }
}
