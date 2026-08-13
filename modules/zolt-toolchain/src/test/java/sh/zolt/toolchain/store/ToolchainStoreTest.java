package sh.zolt.toolchain.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolchainStoreTest {
    @TempDir
    private Path tempDir;

    @Test
    void mapsLockedToolchainToStableStorePath() {
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));

        Path javaHome = store.javaHome(locked());

        assertEquals(
                tempDir.resolve("toolchains/java/graalvm-community/21/linux-x64/jdk"),
                javaHome);
    }

    @Test
    void installedRequiresAllRequestedExecutables() throws IOException {
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        LockedJavaToolchain locked = locked();
        Path javaHome = store.javaHome(locked);

        assertFalse(store.installed(locked));
        tool(javaHome, "bin/java");
        tool(javaHome, "bin/javac");
        tool(javaHome, "bin/jar");
        assertFalse(store.installed(locked));
        tool(javaHome, "bin/native-image");
        assertTrue(store.installed(locked));
    }

    private static LockedJavaToolchain locked() {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.PREFER_MANAGED);
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

    private static void tool(Path javaHome, String relative) throws IOException {
        Path path = javaHome.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        path.toFile().setExecutable(true);
    }
}
