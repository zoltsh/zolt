package sh.zolt.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.toolchain.jvm.JavaRuntimeInfo;
import sh.zolt.toolchain.jvm.JavaToolchainSource;
import sh.zolt.toolchain.jvm.ResolvedJavaToolchain;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaToolchainExecutionServiceTest {
    @TempDir
    private Path tempDir;

    private final ToolchainLockfileService lockfiles = new ToolchainLockfileService();

    @Test
    void returnsManagedNativeImageExecutableFromLockedStore() throws IOException {
        Path project = writeProject(JavaDistribution.GRAALVM_COMMUNITY, Set.of(JavaFeature.NATIVE_IMAGE), ToolchainPolicy.PREFER_MANAGED);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.PREFER_MANAGED);
        lockfiles.writeJava(project.resolve("zolt.lock"), locked);
        install(store, locked);
        JavaToolchainExecutionService service = executionService(ambientFailure());

        Optional<Path> nativeImage = service.nativeImage(
                project,
                project,
                parse(project),
                HostPlatform.parse("linux-x64"),
                store);

        assertEquals(store.nativeImage(locked), nativeImage);
    }

    @Test
    void rejectsPinnedToolchainThatDoesNotProvideNativeImage() throws IOException {
        Path project = writeProject(JavaDistribution.TEMURIN, Set.of(), ToolchainPolicy.PREFER_MANAGED);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(JavaDistribution.TEMURIN, Set.of(), ToolchainPolicy.PREFER_MANAGED);
        lockfiles.writeJava(project.resolve("zolt.lock"), locked);
        install(store, locked);
        JavaToolchainExecutionService service = executionService(ambientFailure());

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> service.nativeImage(project, project, parse(project), HostPlatform.parse("linux-x64"), store));

        assertTrue(exception.getMessage().contains("does not provide native-image"));
        assertTrue(exception.getMessage().contains("features = [\"native-image\"]"));
    }

    @Test
    void rejectsStrictPinnedToolchainWhenLockMetadataIsMissing() throws IOException {
        Path project = writeProject(JavaDistribution.GRAALVM_COMMUNITY, Set.of(JavaFeature.NATIVE_IMAGE), ToolchainPolicy.REQUIRE_MANAGED);
        JavaToolchainExecutionService service = executionService(ambientFailure());

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> service.nativeImage(
                        project,
                        project,
                        parse(project),
                        HostPlatform.parse("linux-x64"),
                        new ToolchainStore(tempDir.resolve("toolchains"))));

        assertTrue(exception.getMessage().contains("Java toolchain is not ready for Native Image"));
        assertTrue(exception.getMessage().contains("lock metadata is missing"));
    }

    @Test
    void leavesUnpinnedProjectOnExistingNativeImageFallbackWhenAmbientDoesNotResolveOne() throws IOException {
        Path project = writeProjectWithoutToolchain();
        JavaToolchainExecutionService service = executionService(request -> new ResolvedJavaToolchain(
                JavaToolchainSource.AMBIENT,
                Optional.of(Path.of("/ambient/jdk")),
                Optional.of(Path.of("/ambient/jdk/bin/java")),
                Optional.of(Path.of("/ambient/jdk/bin/javac")),
                Optional.of(Path.of("/ambient/jdk/bin/jar")),
                Optional.empty(),
                new JavaRuntimeInfo(Optional.of("21"), Optional.of("21"), Optional.of("ambient")),
                request,
                List.of(),
                List.of()));

        Optional<Path> nativeImage = service.nativeImage(
                project,
                project,
                parse(project),
                HostPlatform.parse("linux-x64"),
                new ToolchainStore(tempDir.resolve("toolchains")));

        assertTrue(nativeImage.isEmpty());
    }

    @Test
    void reportsAmbientJavaHomeProblemInsteadOfGenericExposureError() throws IOException {
        Path project = writeProjectWithoutToolchain();
        JavaToolchainExecutionService service = executionService(request -> new ResolvedJavaToolchain(
                JavaToolchainSource.AMBIENT,
                Optional.empty(),
                Optional.of(Path.of("/shims/java")),
                Optional.of(Path.of("/shims/javac")),
                Optional.of(Path.of("/shims/jar")),
                Optional.empty(),
                new JavaRuntimeInfo(Optional.of("21"), Optional.of("21"), Optional.of("ambient")),
                request,
                List.of("Could not determine the Java home for `/shims/java`. Set JAVA_HOME to a JDK directory,"
                        + " or configure [toolchain.java] and run `zolt toolchain sync`."),
                List.of()));

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> service.environment(
                        project,
                        project,
                        parse(project),
                        HostPlatform.parse("linux-x64"),
                        new ToolchainStore(tempDir.resolve("toolchains"))));

        assertTrue(exception.getMessage().contains("Could not determine the Java home for `/shims/java`."));
        assertFalse(exception.getMessage().contains("does not expose JAVA_HOME"));
    }

    @Test
    void resolvesEnvironmentFromAmbientJavaHomeReportedByTheJvm() throws IOException {
        Path project = writeProjectWithoutToolchain();
        Path javaHome = tempDir.resolve("probed-jdk");
        JavaToolchainExecutionService service = executionService(request -> new ResolvedJavaToolchain(
                JavaToolchainSource.AMBIENT,
                Optional.of(javaHome),
                Optional.of(Path.of("/shims/java")),
                Optional.of(Path.of("/shims/javac")),
                Optional.of(Path.of("/shims/jar")),
                Optional.empty(),
                new JavaRuntimeInfo(Optional.of("21"), Optional.of("21"), Optional.of("ambient")),
                request,
                List.of(),
                List.of()));

        JavaToolchainEnvironment environment = service.environment(
                project,
                project,
                parse(project),
                HostPlatform.parse("linux-x64"),
                new ToolchainStore(tempDir.resolve("toolchains")));

        assertEquals(javaHome.toAbsolutePath().normalize(), environment.javaHome());
        assertEquals(javaHome.resolve("bin").toAbsolutePath().normalize(), environment.bin());
    }

    private Path writeProject(
            JavaDistribution distribution,
            Set<JavaFeature> features,
            ToolchainPolicy policy) throws IOException {
        Path project = tempDir.resolve("project-" + distribution.id() + "-" + policy.id());
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.java]
                version = 21
                distribution = "%s"
                features = [%s]
                policy = "%s"
                """.formatted(
                distribution.id(),
                featureToml(features),
                policy.id()));
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n\n");
        return project;
    }

    private Path writeProjectWithoutToolchain() throws IOException {
        Path project = tempDir.resolve("project-without-toolchain");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        return project;
    }

    private static JavaToolchainExecutionService executionService(sh.zolt.toolchain.jvm.JavaToolchainProbe ambient) {
        return new JavaToolchainExecutionService(new JavaToolchainStatusService(
                new ToolchainConfigReader(),
                new ToolchainLockfileService(),
                ambient));
    }

    private static sh.zolt.toolchain.jvm.JavaToolchainProbe ambientFailure() {
        return request -> {
            throw new AssertionError("ambient Java should not be probed");
        };
    }

    private static ProjectConfig parse(Path project) {
        return new ManifestProjectConfigLoader().load(project.resolve("zolt.toml"));
    }

    private static LockedJavaToolchain locked(
            JavaDistribution distribution,
            Set<JavaFeature> features,
            ToolchainPolicy policy) {
        JavaToolchainRequest request = new JavaToolchainRequest("21", distribution, features, policy);
        return new LockedJavaToolchain(
                "java-" + distribution.id() + "-21" + (features.contains(JavaFeature.NATIVE_IMAGE) ? "-native-image" : ""),
                request,
                HostPlatform.parse("linux-x64"),
                "21",
                distribution,
                "builtin:java-" + distribution.id() + "-21",
                "https://example.test/jdk.tar.gz",
                "0".repeat(64),
                JavaToolchainLayout.standard(request.requiresNativeImage()));
    }

    private static void install(ToolchainStore store, LockedJavaToolchain locked) throws IOException {
        tool(store.java(locked));
        tool(store.javac(locked));
        tool(store.jar(locked));
        if (locked.request().requiresNativeImage()) {
            tool(store.nativeImage(locked).orElseThrow());
        }
    }

    private static void tool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        path.toFile().setExecutable(true);
    }

    private static String featureToml(Set<JavaFeature> features) {
        return String.join(", ", features.stream()
                .map(feature -> "\"" + feature.id() + "\"")
                .sorted()
                .toList());
    }
}
