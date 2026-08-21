package sh.zolt.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.jvm.JavaRuntimeInfo;
import sh.zolt.toolchain.jvm.JavaToolchainProbe;
import sh.zolt.toolchain.jvm.JavaToolchainSource;
import sh.zolt.toolchain.jvm.ResolvedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestRuntimeToolchainResolverTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolveReturnsEmptyWithoutTestToolchain() throws IOException {
        Path project = writeProject("no-test", "17", "17", "allow-system", null);
        TestRuntimeToolchainResolver resolver = resolver(request -> {
            throw new AssertionError("ambient probe should not be reached without a test toolchain");
        });

        assertTrue(resolver.resolve(project, project, parse(project),
                HostPlatform.parse("linux-x64"), store()).isEmpty());
    }

    @Test
    void resolveExposesExactAmbientMatchAsReady() throws IOException {
        Path project = writeProject("exact", "17", "17", "allow-system", "17");
        Path java = fakeJava("17");
        TestRuntimeToolchainResolver resolver = resolver(request -> ambient(request, "17", java));

        TestRuntimeToolchain resolved = resolver.resolve(project, project, parse(project),
                HostPlatform.parse("linux-x64"), store()).orElseThrow();

        assertEquals("17", resolved.request().version());
        assertTrue(resolved.ready());
        assertEquals(java, resolved.requireJava());
    }

    @Test
    void rejectsTestRuntimeOlderThanProjectRelease() throws IOException {
        Path project = writeProject("floor", "21", "21", "allow-system", "17");
        Path java = fakeJava("21");
        TestRuntimeToolchainResolver resolver = resolver(request -> ambient(request, "21", java));

        TestRuntimeToolchain resolved = resolver.resolve(project, project, parse(project),
                HostPlatform.parse("linux-x64"), store()).orElseThrow();

        assertFalse(resolved.ready());
        assertTrue(resolved.releaseProblem().isPresent());
        ActionableException exception = assertThrows(ActionableException.class, resolved::requireJava);
        assertTrue(exception.getMessage().contains("17"));
        assertTrue(exception.getMessage().contains("21"));
        assertTrue(exception.getMessage().contains("UnsupportedClassVersionError"));
    }

    @Test
    void rejectsAmbientNewerThanRequestedTestRuntime() throws IOException {
        Path project = writeProject("mismatch", "17", "17", "allow-system", "17");
        Path java = fakeJava("21");
        TestRuntimeToolchainResolver resolver = resolver(request -> ambient(request, "21", java));

        TestRuntimeToolchain resolved = resolver.resolve(project, project, parse(project),
                HostPlatform.parse("linux-x64"), store()).orElseThrow();

        assertFalse(resolved.ready());
        assertTrue(resolved.releaseProblem().isEmpty());
        ActionableException exception = assertThrows(ActionableException.class, resolved::requireJava);
        assertTrue(exception.getMessage().contains("not installed"));
        assertTrue(exception.getMessage().contains("resolved Java 21"));
    }

    @Test
    void capturedResolutionPreservesExactRuntimeValidation()
            throws IOException {
        Path project = writeProject(
                "captured-mismatch",
                "17",
                "17",
                "allow-system",
                "17");
        JavaToolchainRequest request =
                new ToolchainConfigReader().readJavaTest(
                        project.resolve("zolt.toml")).orElseThrow();
        Path java = fakeJava("21");
        TestRuntimeToolchain resolved = resolver(
                ignored -> ambient(request, "21", java))
                .resolveCaptured(
                        request,
                        Optional.empty(),
                        parse(project),
                        HostPlatform.parse("linux-x64"),
                        store());

        ActionableException exception =
                assertThrows(ActionableException.class, resolved::requireJava);
        assertTrue(exception.getMessage().contains("resolved Java 21"));
    }

    @Test
    void requireManagedTestRuntimeWithoutLockIsNotReady() throws IOException {
        Path project = writeProject("managed-missing", "17", "17", "require-managed", "17");
        TestRuntimeToolchainResolver resolver = resolver(request -> {
            throw new AssertionError("require-managed must not fall back to ambient");
        });

        TestRuntimeToolchain resolved = resolver.resolve(project, project, parse(project),
                HostPlatform.parse("linux-x64"), store()).orElseThrow();

        assertFalse(resolved.ready());
        ActionableException exception = assertThrows(ActionableException.class, resolved::requireJava);
        assertTrue(exception.getMessage().contains("Test runtime Java toolchain 17 is not installed"));
    }

    @Test
    void managedResolutionExposesJavaExecutable() {
        JavaToolchainRequest request =
                new JavaToolchainRequest("17", JavaDistribution.TEMURIN, Set.of(), ToolchainPolicy.REQUIRE_MANAGED);
        Path java = Path.of("/jdk17/bin/java");
        ResolvedJavaToolchain resolved = new ResolvedJavaToolchain(
                JavaToolchainSource.MANAGED,
                Optional.of(Path.of("/jdk17")),
                Optional.of(java),
                Optional.of(Path.of("/jdk17/bin/javac")),
                Optional.of(Path.of("/jdk17/bin/jar")),
                Optional.empty(),
                new JavaRuntimeInfo(Optional.of("17.0.9"), Optional.of("17"), Optional.of("temurin")),
                request,
                List.of(),
                List.of());
        TestRuntimeToolchain toolchain = new TestRuntimeToolchain(
                request,
                new JavaToolchainStatus(request, TestRuntimeToolchainResolver.SOURCE, resolved),
                "17");

        assertTrue(toolchain.ready());
        assertEquals(java, toolchain.requireJava());
    }

    private TestRuntimeToolchainResolver resolver(JavaToolchainProbe probe) {
        return new TestRuntimeToolchainResolver(
                new ToolchainConfigReader(),
                new JavaToolchainStatusService(
                        new ToolchainConfigReader(), new ToolchainLockfileService(), probe));
    }

    private ToolchainStore store() {
        return new ToolchainStore(tempDir.resolve("toolchains"));
    }

    private Path fakeJava(String feature) throws IOException {
        Path java = tempDir.resolve("jdk" + feature).resolve("bin").resolve("java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "");
        return java;
    }

    private static ResolvedJavaToolchain ambient(JavaToolchainRequest request, String feature, Path java) {
        return new ResolvedJavaToolchain(
                JavaToolchainSource.AMBIENT,
                Optional.of(java.getParent().getParent()),
                Optional.of(java),
                Optional.of(java.getParent().resolve("javac")),
                Optional.of(java.getParent().resolve("jar")),
                Optional.empty(),
                new JavaRuntimeInfo(Optional.of(feature + ".0.1"), Optional.of(feature), Optional.of("temurin")),
                request,
                List.of(),
                List.of());
    }

    private static ProjectConfig parse(Path project) {
        return new ZoltTomlParser().parse(project.resolve("zolt.toml"));
    }

    private Path writeProject(
            String name, String projectJava, String mainVersion, String policy, String testVersion) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        StringBuilder toml = new StringBuilder("""
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "%s"

                [toolchain.java]
                version = "%s"
                distribution = "temurin"
                features = []
                policy = "%s"
                """.formatted(name, projectJava, mainVersion, policy));
        if (testVersion != null) {
            toml.append("\n[toolchain.java.test]\nversion = \"").append(testVersion).append("\"\n");
        }
        Files.writeString(project.resolve("zolt.toml"), toml.toString());
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n\n");
        return project;
    }
}
