package sh.zolt.cli.toolchain;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolchainSyncV2CommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void syncLocksMainAndFeatureOverriddenTestRuntimeToolchains() throws IOException {
        Path project = tempDir.resolve("project-with-test-runtime");
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

                [toolchain.java.test]
                version = "21"
                distribution = "temurin"
                features = []
                """);
        Files.writeString(project.resolve("zolt.lock"), "version = 1\n\n");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        install(store, graalLocked());
        install(store, temurinLocked());

        var result = execute(
                "toolchain",
                "sync",
                "--directory",
                project.toString(),
                "--target",
                "linux-x64",
                "--install-root",
                tempDir.resolve("toolchains").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        String lock = Files.readString(project.resolve("zolt.lock"));
        assertEquals(8, countJavaLocks(lock));
        assertTrue(lock.contains("request.distribution = \"graalvm-community\""));
        assertTrue(lock.contains("request.distribution = \"temurin\""));
        assertTrue(lock.contains("request.features = [\"native-image\"]"));
        assertTrue(lock.contains("request.features = []"));
    }

    @Test
    void syncHelpExplainsExplicitRefresh() {
        var result = execute("toolchain", "sync", "--help");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("--refresh"));
        assertTrue(result.stdout().contains("newest GA patch"));
    }

    @Test
    void installRejectsFloatingJavaVersionAliases() {
        var result = execute("toolchain", "install", "java", "latest", "--temurin");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("not a concrete feature release"));
        assertTrue(result.stderr().contains("aliases such as `latest` are not supported"));
    }

    @Test
    void installRejectsNonAsciiJavaVersionNumerals() {
        var result = execute("toolchain", "install", "java", "٢٥", "--temurin");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("not a concrete feature release"));
    }

    @Test
    void installHelpExplainsExplicitRefresh() {
        var result = execute("toolchain", "install", "java", "--help");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("--refresh"));
        assertTrue(result.stdout().contains("newest GA patch"));
    }

    private static LockedJavaToolchain graalLocked() {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.PREFER_MANAGED);
        return new LockedJavaToolchain(
                "java-graalvm-community-21-native-image",
                request,
                HostPlatform.parse("linux-x64"),
                "21.0.2",
                JavaDistribution.GRAALVM_COMMUNITY,
                "builtin:java-graalvm-community-21-native-image",
                "https://example.test/graalvm.tar.gz",
                "0".repeat(64),
                new JavaToolchainLayout(
                        ".",
                        "bin/java",
                        "bin/javac",
                        "bin/jar",
                        "lib/svm/bin/native-image"));
    }

    private static LockedJavaToolchain temurinLocked() {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.TEMURIN,
                Set.of(),
                ToolchainPolicy.PREFER_MANAGED);
        return new LockedJavaToolchain(
                "java-temurin-21",
                request,
                HostPlatform.parse("linux-x64"),
                "21.0.11+10",
                JavaDistribution.TEMURIN,
                "builtin:java-temurin-21",
                "https://example.test/temurin.tar.gz",
                "0".repeat(64),
                JavaToolchainLayout.standard(false));
    }

    private static int countJavaLocks(String content) {
        return content.split("\\[\\[toolchain\\.java]]", -1).length - 1;
    }

    private static void install(ToolchainStore store, LockedJavaToolchain locked) throws IOException {
        tool(store.java(locked));
        tool(store.javac(locked));
        tool(store.jar(locked));
        if (store.nativeImage(locked).isPresent()) {
            tool(store.nativeImage(locked).orElseThrow());
        }
    }

    private static void tool(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        path.toFile().setExecutable(true);
    }
}
