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

    /**
     * A shim that wraps only {@code java} leaves every other JDK on {@code PATH} in play. Taking
     * {@code javac} from that scan would compile with a JDK the resolved home never named, so the
     * probed home wins whenever it carries the tool.
     */
    @Test
    void prefersProbedJavaHomeToolsOverAForeignJavacOnPath() throws IOException {
        assumeShellShims();
        Path realJavaHome = realJavaHome();
        Path shims = tempDir.resolve("java-only-shims");
        shim(shims, "java", realJavaHome);
        Path foreign = tempDir.resolve("foreign-jdk/bin");
        tool(foreign, "javac");
        tool(foreign, "jar");
        AmbientJavaToolchainProbe probe = shimProbe(shims + java.io.File.pathSeparator + foreign);

        ResolvedJavaToolchain resolved = probe.resolve(JavaToolchainRequest.projectDefault("21"));

        assertTrue(resolved.ok(), () -> "problems: " + resolved.problems());
        assertEquals(
                realJavaHome.resolve("bin").resolve("javac").toRealPath(),
                resolved.javac().orElseThrow().toRealPath());
        assertEquals(
                realJavaHome.resolve("bin").resolve("jar").toRealPath(),
                resolved.jar().orElseThrow().toRealPath());
    }

    /** When the resolved home genuinely lacks a tool, the foreign copy is reported, never accepted. */
    @Test
    void reportsMixedToolchainWhenTheResolvedHomeLacksJavac() throws IOException {
        Path javaHome = tempDir.resolve("jre");
        tool(javaHome, "java");
        Path foreign = tempDir.resolve("other-jdk/bin");
        tool(foreign, "javac");
        tool(foreign, "jar");
        AmbientJavaToolchainProbe probe = probe(
                Map.of("JAVA_HOME", javaHome.toString(), "PATH", foreign.toString()),
                java -> Optional.of(runtimeProbe("Eclipse Temurin")));

        ResolvedJavaToolchain resolved = probe.resolve(JavaToolchainRequest.projectDefault("21"));

        assertFalse(resolved.ok());
        assertEquals(javaHome, resolved.javaHome().orElseThrow());
        assertTrue(
                resolved.problems().stream().anyMatch(problem ->
                        problem.contains("Mixed Java toolchain: `javac`")
                                && problem.contains(foreign.resolve("javac").toString())
                                && problem.contains(javaHome.toString())),
                () -> "problems: " + resolved.problems());
    }

    /** A JAVA_HOME that does not exist is a misconfiguration to report, not a home to hand out. */
    @Test
    void reportsConfiguredJavaHomeThatIsNotADirectory() throws IOException {
        Path missing = tempDir.resolve("missing-jdk");
        Path bin = tempDir.resolve("jdk/bin");
        tool(bin, "java");
        tool(bin, "javac");
        tool(bin, "jar");
        AmbientJavaToolchainProbe probe = probe(
                Map.of("JAVA_HOME", missing.toString(), "PATH", bin.toString()),
                java -> Optional.of(runtimeProbe("Eclipse Temurin")));

        ResolvedJavaToolchain resolved = probe.resolve(JavaToolchainRequest.projectDefault("21"));

        assertFalse(resolved.ok());
        assertEquals(tempDir.resolve("jdk"), resolved.javaHome().orElseThrow());
        assertTrue(
                resolved.problems().stream().anyMatch(problem ->
                        problem.contains("JAVA_HOME is set to `" + missing + "`")
                                && problem.contains("not a directory")),
                () -> "problems: " + resolved.problems());
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
        return shimProbe(shims.toString());
    }

    private static AmbientJavaToolchainProbe shimProbe(String path) {
        return new AmbientJavaToolchainProbe(
                Map.of("PATH", path)::get,
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
