package sh.zolt.toolchain.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AmbientJavaToolchainProbeTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolvesAmbientJavaFromJavaHome() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        tool(javaHome, "native-image");
        AmbientJavaToolchainProbe probe = probe(
                Map.of("JAVA_HOME", javaHome.toString()),
                java -> Optional.of(runtimeProbe("GraalVM Community")));

        ResolvedJavaToolchain resolved = probe.resolve(new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.PREFER_MANAGED));

        assertTrue(resolved.ok());
        assertEquals(JavaToolchainSource.AMBIENT, resolved.source());
        assertEquals(javaHome, resolved.javaHome().orElseThrow());
        assertEquals(javaHome.resolve("bin/native-image"), resolved.nativeImage().orElseThrow());
    }

    @Test
    void reportsMissingNativeImageWhenRequested() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        AmbientJavaToolchainProbe probe = probe(
                Map.of("JAVA_HOME", javaHome.toString()),
                java -> Optional.of(runtimeProbe("Eclipse Temurin")));

        ResolvedJavaToolchain resolved = probe.resolve(new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.PREFER_MANAGED));

        assertFalse(resolved.ok());
        assertTrue(resolved.problems().stream().anyMatch(problem -> problem.contains("Native Image is missing")));
    }

    @Test
    void reusesAmbientRuntimeProbeAcrossRequests() throws IOException {
        Path javaHome = tempDir.resolve("jdk");
        tool(javaHome, "java");
        tool(javaHome, "javac");
        tool(javaHome, "jar");
        AtomicInteger runtimeReads = new AtomicInteger();
        AmbientJavaToolchainProbe probe = probe(
                Map.of("JAVA_HOME", javaHome.toString()),
                java -> {
                    runtimeReads.incrementAndGet();
                    return Optional.of(runtimeProbe("Eclipse Temurin"));
                });

        ResolvedJavaToolchain java17 = probe.resolve(JavaToolchainRequest.projectDefault("17"));
        ResolvedJavaToolchain java23 = probe.resolve(JavaToolchainRequest.projectDefault("23"));

        assertTrue(java17.ok());
        assertFalse(java23.ok());
        assertEquals(1, runtimeReads.get());
    }

    @Test
    void resolvesJavaHomeFromShimOnPathWhenJavaHomeIsUnset() throws IOException {
        assumeShellShims();
        Path realJavaHome = realJavaHome();
        Path shims = tempDir.resolve("shims");
        shim(shims, "java", realJavaHome);
        shim(shims, "javac", realJavaHome);
        shim(shims, "jar", realJavaHome);
        AmbientJavaToolchainProbe probe = shimProbe(shims);

        ResolvedJavaToolchain resolved = probe.resolve(JavaToolchainRequest.projectDefault("21"));

        assertTrue(resolved.ok(), () -> "problems: " + resolved.problems());
        assertEquals(realJavaHome, resolved.javaHome().orElseThrow().toRealPath());
        assertEquals(shims.resolve("java"), resolved.java().orElseThrow());
        assertTrue(resolved.runtime().featureVersion().isPresent());
    }

    @Test
    void completesToolsFromProbedJavaHomeWhenOnlyJavaIsShimmed() throws IOException {
        assumeShellShims();
        Path realJavaHome = realJavaHome();
        Path shims = tempDir.resolve("java-only-shims");
        shim(shims, "java", realJavaHome);
        AmbientJavaToolchainProbe probe = shimProbe(shims);

        ResolvedJavaToolchain resolved = probe.resolve(JavaToolchainRequest.projectDefault("21"));

        assertTrue(resolved.ok(), () -> "problems: " + resolved.problems());
        assertEquals(
                realJavaHome.resolve("bin").resolve("javac").toRealPath(),
                resolved.javac().orElseThrow().toRealPath());
        assertEquals(
                realJavaHome.resolve("bin").resolve("jar").toRealPath(),
                resolved.jar().orElseThrow().toRealPath());
    }

    @Test
    void reportsUnresolvableJavaHomeWhenShimCannotRunJava() throws IOException {
        assumeShellShims();
        Path shims = tempDir.resolve("broken-shims");
        Files.createDirectories(shims);
        Path broken = shims.resolve("java");
        Files.writeString(broken, "#!/bin/sh\nexec " + shims.resolve("missing-jdk/bin/java") + " \"$@\"\n");
        broken.toFile().setExecutable(true);
        AmbientJavaToolchainProbe probe = shimProbe(shims);

        ResolvedJavaToolchain resolved = probe.resolve(JavaToolchainRequest.projectDefault("21"));

        assertFalse(resolved.ok());
        assertTrue(resolved.javaHome().isEmpty());
        assertTrue(
                resolved.problems().stream().anyMatch(problem -> problem.contains("Could not determine the Java home")
                        && problem.contains(broken.toString())),
                () -> "problems: " + resolved.problems());
        assertTrue(
                resolved.problems().stream().anyMatch(problem -> problem.contains("Set JAVA_HOME")),
                () -> "problems: " + resolved.problems());
    }

    private static void assumeShellShims() {
        assumeTrue(
                !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"),
                "shell wrapper shims require a POSIX shell");
    }

    private static Path realJavaHome() throws IOException {
        return Path.of(System.getProperty("java.home")).toRealPath();
    }

    private static AmbientJavaToolchainProbe shimProbe(Path shims) {
        return new AmbientJavaToolchainProbe(
                Map.of("PATH", shims.toString())::get,
                java.io.File.pathSeparator,
                System.getProperty("os.name"),
                Optional.empty(),
                JavaRuntimeProbe::read);
    }

    private static void shim(Path shims, String name, Path realJavaHome) throws IOException {
        Files.createDirectories(shims);
        Path shim = shims.resolve(name);
        Files.writeString(
                shim,
                "#!/bin/sh\nexec \"" + realJavaHome.resolve("bin").resolve(name) + "\" \"$@\"\n");
        shim.toFile().setExecutable(true);
    }

    private static JavaRuntimeProbe.Result runtimeProbe(String vendor) {
        return new JavaRuntimeProbe.Result(
                new JavaRuntimeInfo(Optional.of("21.0.2"), Optional.of("21"), Optional.of(vendor)),
                Optional.empty());
    }

    private static AmbientJavaToolchainProbe probe(
            Map<String, String> environment,
            AmbientJavaToolchainProbe.RuntimeInfoReader runtimeInfoReader) {
        Function<String, String> env = environment::get;
        return new AmbientJavaToolchainProbe(env, java.io.File.pathSeparator, "Linux", Optional.empty(), runtimeInfoReader);
    }

    private static Path tool(Path javaHomeOrBin, String name) throws IOException {
        Path bin = javaHomeOrBin.getFileName().toString().equals("bin")
                ? javaHomeOrBin
                : javaHomeOrBin.resolve("bin");
        Files.createDirectories(bin);
        Path tool = bin.resolve(name);
        Files.writeString(tool, "");
        tool.toFile().setExecutable(true);
        return tool;
    }
}
