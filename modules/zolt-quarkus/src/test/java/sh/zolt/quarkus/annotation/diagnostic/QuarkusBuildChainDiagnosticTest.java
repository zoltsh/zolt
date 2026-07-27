package sh.zolt.quarkus.annotation.diagnostic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.quarkus.testsupport.QuarkusTestRuntimeClasspath;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class QuarkusBuildChainDiagnosticTest {
    @Test
    void writesSystemBuildChainDiagnostic() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QuarkusBuildChainDiagnostic diagnostic = diagnostic(out);

        diagnostic.write(null);

        String output = output(out);
        assertTrue(output.contains("Zolt Quarkus build-chain diagnostic:"));
        assertTrue(output.contains("  systemLoader="));
        assertTrue(output.contains("    TestBuildChainFunction=<unavailable: ClassNotFoundException>"));
        assertFalse(output.contains("  quarkusRuntimeLoader="));
    }

    @Test
    void skipsRuntimeDiagnosticWhenRuntimeLoaderMatchesSystemLoader() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QuarkusBuildChainDiagnostic diagnostic = diagnostic(out);

        diagnostic.write(ClassLoader.getSystemClassLoader());

        assertFalse(output(out).contains("  quarkusRuntimeLoader="));
    }

    @Test
    void writesRuntimeDiagnosticWhenRuntimeLoaderDiffers() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QuarkusBuildChainDiagnostic diagnostic = diagnostic(out);
        ClassLoader runtimeLoader = new ClassLoader(ClassLoader.getSystemClassLoader()) {
        };

        diagnostic.write(runtimeLoader);

        String output = output(out);
        assertTrue(output.contains("  systemLoader="));
        assertTrue(output.contains("  quarkusRuntimeLoader="));
    }

    @Test
    void reportsBuildChainFunctionAndEmptyProvidersWhenRuntimeLoaderCanSeeQuarkusJUnit() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        QuarkusBuildChainDiagnostic diagnostic = diagnostic(out);
        try (URLClassLoader runtimeLoader = new URLClassLoader(
                new URL[] {
                        quarkusJar("quarkus-junit"),
                        quarkusJar("quarkus-builder")
                },
                ClassLoader.getPlatformClassLoader())) {

            diagnostic.write(runtimeLoader);
        }

        String output = output(out);
        assertTrue(output.contains("  quarkusRuntimeLoader="), output);
        assertTrue(output.contains("    TestBuildChainFunction.loader="), output);
        assertTrue(output.contains("    serviceResources=[<none>]"), output);
        assertTrue(output.contains("    providers=[<none>]"), output);
    }

    private static QuarkusBuildChainDiagnostic diagnostic(ByteArrayOutputStream out) {
        return new QuarkusBuildChainDiagnostic(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private static String output(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8);
    }

    private static URL quarkusJar(String artifactId) {
        String relativeJar = "io/quarkus/" + artifactId + "/3.33.2/" + artifactId + "-3.33.2.jar";
        List<Path> jars = QuarkusTestRuntimeClasspath.existingRepoCacheJars(repoRoot(), List.of(relativeJar));
        if (jars.isEmpty()) {
            throw new AssertionError("Could not locate " + relativeJar + " in any candidate artifact cache.");
        }
        return QuarkusTestRuntimeClasspath.url(jars.get(0));
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("zolt.lock"))
                    && Files.exists(current.resolve("modules/zolt-quarkus"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate Zolt repository root.");
    }
}
