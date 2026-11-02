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
        KotlinCompilerPluginOption option = new KotlinCompilerPluginOption(
                "org.jetbrains.kotlin.allopen", "preset", "spring");

        KotlinCompilerInvocationToolchain toolchain = new KotlinCompilerInvocationToolchain(
                launcher,
                kapt,
                List.of(serialization),
                List.of(option));

        assertEquals(launcher, toolchain.launcherClasspath());
        assertEquals(kapt.toAbsolutePath().normalize(), toolchain.kaptPluginJar().orElseThrow());
        assertEquals(
                List.of(serialization.toAbsolutePath().normalize()),
                toolchain.compilerPluginJars());
        assertEquals(List.of(option), toolchain.compilerPluginOptions());
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

    @Test
    void rejectsOptionsWithoutAPluginAndExactDuplicates() {
        Classpath launcher = new Classpath(List.of(Path.of("tools/compiler.jar")));
        KotlinCompilerPluginOption option = new KotlinCompilerPluginOption(
                "org.jetbrains.kotlin.allopen", "preset", "spring");

        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerInvocationToolchain(
                        launcher, null, List.of(), List.of(option)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerInvocationToolchain(
                        new Classpath(List.of(
                                Path.of("tools/compiler.jar"), Path.of("tools/allopen.jar"))),
                        null,
                        List.of(Path.of("tools/allopen.jar")),
                        List.of(option, option)));
    }
}
