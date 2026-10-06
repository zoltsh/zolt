package sh.zolt.build.compile.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.classpath.Classpath;

final class KotlinCompilerInvocationToolchainTest {
    @Test
    void normalizesVerifiedPluginPathsAndPreservesLauncherOrder() {
        Path compiler = Path.of("tools/compiler.jar");
        Path kapt = Path.of("tools/kapt.jar");
        Path serialization = Path.of("tools/serialization.jar");
        Classpath launcher = new Classpath(List.of(compiler, kapt, serialization));

        KotlinCompilerInvocationToolchain toolchain = new KotlinCompilerInvocationToolchain(
                launcher,
                kapt,
                List.of(serialization));

        assertEquals(launcher, toolchain.launcherClasspath());
        assertEquals(kapt.toAbsolutePath().normalize(), toolchain.kaptPluginJar().orElseThrow());
        assertEquals(
                List.of(serialization.toAbsolutePath().normalize()),
                toolchain.compilerPluginJars());
    }

    @Test
    void rejectsPluginsOutsideTheVerifiedLauncherClosureAndDuplicates() {
        Path compiler = Path.of("tools/compiler.jar");
        Path serialization = Path.of("tools/serialization.jar");
        Classpath launcher = new Classpath(List.of(compiler, serialization));

        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerInvocationToolchain(
                        launcher,
                        Path.of("tools/kapt.jar"),
                        List.of(serialization)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerInvocationToolchain(
                        launcher,
                        null,
                        List.of(Path.of("tools/other.jar"))));
        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerInvocationToolchain(
                        launcher,
                        null,
                        List.of(serialization, serialization)));
        assertTrue(duplicate.getMessage().contains("duplicates"));
    }
}
