package sh.zolt.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toml.ZoltTomlParser;
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

final class JavaToolchainStatusServiceTest {
    @TempDir
    private Path tempDir;

    private final ToolchainLockfileService lockfiles = new ToolchainLockfileService();

    @Test
    void usesInstalledManagedToolchainFromLock() throws IOException {
        Path project = writeProject(ToolchainPolicy.PREFER_MANAGED);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.PREFER_MANAGED);
        lockfiles.writeJava(project.resolve("zolt.lock"), locked);
        install(store, locked);
        JavaToolchainStatusService service = serviceWithAmbientFailure();

        JavaToolchainStatus status = service.status(
                project,
                parse(project),
                HostPlatform.parse("linux-x64"),
                store);

        assertTrue(status.ok());
        assertEquals(JavaToolchainSource.MANAGED, status.resolved().source());
        assertEquals(Optional.of(store.javaHome(locked)), status.resolved().javaHome());
        assertEquals(Optional.of(store.java(locked)), status.resolved().java());
        assertEquals(Optional.of(store.nativeImage(locked).orElseThrow()), status.resolved().nativeImage());
    }

    @Test
    void preferManagedFallsBackToAmbientWhenLockedToolchainIsNotInstalled() throws IOException {
        Path project = writeProject(ToolchainPolicy.PREFER_MANAGED);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.PREFER_MANAGED);
        lockfiles.writeJava(project.resolve("zolt.lock"), locked);
        JavaToolchainStatusService service = serviceWithAmbientSuccess();

        JavaToolchainStatus status = service.status(
                project,
                parse(project),
                HostPlatform.parse("linux-x64"),
                store);

        assertTrue(status.ok());
        assertEquals(JavaToolchainSource.AMBIENT, status.resolved().source());
        assertTrue(status.resolved().notes().stream()
                .anyMatch(note -> note.contains("Locked managed Java toolchain is not installed")));
    }

    @Test
    void requireManagedDoesNotUseAmbientWhenLockMetadataIsMissing() throws IOException {
        Path project = writeProject(ToolchainPolicy.REQUIRE_MANAGED);
        JavaToolchainStatusService service = serviceWithAmbientFailure();

        JavaToolchainStatus status = service.status(
                project,
                parse(project),
                HostPlatform.parse("linux-x64"),
                new ToolchainStore(tempDir.resolve("toolchains")));

        assertFalse(status.ok());
        assertEquals(JavaToolchainSource.MANAGED, status.resolved().source());
        assertTrue(status.resolved().problems().stream()
                .anyMatch(problem -> problem.contains("lock metadata is missing")));
    }

    @Test
    void allowSystemPrefersMatchingAmbientToolchain() throws IOException {
        Path project = writeProject(ToolchainPolicy.ALLOW_SYSTEM);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.ALLOW_SYSTEM);
        lockfiles.writeJava(project.resolve("zolt.lock"), locked);
        install(store, locked);
        JavaToolchainStatusService service = serviceWithAmbientSuccess();

        JavaToolchainStatus status = service.status(
                project,
                parse(project),
                HostPlatform.parse("linux-x64"),
                store);

        assertTrue(status.ok());
        assertEquals(JavaToolchainSource.AMBIENT, status.resolved().source());
    }

    @Test
    void workspaceToolchainConfigAppliesToMemberWithoutProjectToolchain() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path member = workspace.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "demo"
                members = ["apps/api"]

                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"
                """);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked(ToolchainPolicy.REQUIRE_MANAGED);
        lockfiles.writeJava(workspace.resolve("zolt.lock"), locked);
        install(store, locked);
        JavaToolchainStatusService service = serviceWithAmbientFailure();

        JavaToolchainStatus status = service.status(
                member,
                workspace,
                parse(member),
                HostPlatform.parse("linux-x64"),
                store);

        assertTrue(status.ok());
        assertEquals("[workspace toolchain.java]", status.requestSource());
        assertEquals(JavaToolchainSource.MANAGED, status.resolved().source());
        assertEquals(Optional.of(store.javaHome(locked)), status.resolved().javaHome());
    }

    private Path writeProject(ToolchainPolicy policy) throws IOException {
        Path project = tempDir.resolve("project-" + policy.id());
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "%s"
                """.formatted(policy.id()));
        Files.writeString(project.resolve("zolt.lock"), "version = 1\n\n");
        return project;
    }

    private static ProjectConfig parse(Path project) {
        return new ZoltTomlParser().parse(project.resolve("zolt.toml"));
    }

    private static JavaToolchainStatusService serviceWithAmbientSuccess() {
        return new JavaToolchainStatusService(
                new ToolchainConfigReader(),
                new ToolchainLockfileService(),
                JavaToolchainStatusServiceTest::ambient);
    }

    private static JavaToolchainStatusService serviceWithAmbientFailure() {
        return new JavaToolchainStatusService(
                new ToolchainConfigReader(),
                new ToolchainLockfileService(),
                request -> {
                    throw new AssertionError("ambient Java should not be probed");
                });
    }

    private static ResolvedJavaToolchain ambient(JavaToolchainRequest request) {
        Path javaHome = Path.of("/ambient/jdk");
        return new ResolvedJavaToolchain(
                JavaToolchainSource.AMBIENT,
                Optional.of(javaHome),
                Optional.of(javaHome.resolve("bin/java")),
                Optional.of(javaHome.resolve("bin/javac")),
                Optional.of(javaHome.resolve("bin/jar")),
                Optional.of(javaHome.resolve("bin/native-image")),
                new JavaRuntimeInfo(
                        Optional.of("21"),
                        Optional.of("21"),
                        Optional.of("ambient")),
                request,
                List.of(),
                List.of());
    }

    private static LockedJavaToolchain locked(ToolchainPolicy policy) {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                policy);
        return new LockedJavaToolchain(
                "java-graalvm-community-21-native-image",
                request,
                HostPlatform.parse("linux-x64"),
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                "builtin:java-graalvm-community-21-native-image",
                "https://example.test/jdk.tar.gz",
                "0".repeat(64),
                JavaToolchainLayout.standard(true));
    }

    private static void install(ToolchainStore store, LockedJavaToolchain locked) throws IOException {
        tool(store.java(locked));
        tool(store.javac(locked));
        tool(store.jar(locked));
        tool(store.nativeImage(locked).orElseThrow());
    }

    private static void tool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        path.toFile().setExecutable(true);
    }
}
