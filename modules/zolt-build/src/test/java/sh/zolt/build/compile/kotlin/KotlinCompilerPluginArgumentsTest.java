package sh.zolt.build.compile.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.build.compile.kotlin.kapt.KotlinKaptOptions;
import sh.zolt.classpath.Classpath;

final class KotlinCompilerPluginArgumentsTest {
    @TempDir
    Path tempDir;

    @Test
    void passesVerifiedCompilerPluginsInSelectionOrder() {
        Path serialization = tempDir.resolve("tools/serialization.jar");
        Path futurePlugin = tempDir.resolve("tools/future.jar");
        KotlinCompilerPluginOption option = new KotlinCompilerPluginOption(
                "org.jetbrains.kotlin.allopen", "preset", "spring");

        List<String> arguments = KotlinCompilerInvocationArguments.build(
                Path.of("/jdk"),
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("classes"),
                new KotlinCompilerOptions("21", "plugins_main", false),
                List.of(serialization, futurePlugin),
                List.of(option),
                null,
                ":");

        String serializationArgument = "-Xplugin=" + serialization;
        String futureArgument = "-Xplugin=" + futurePlugin;
        assertTrue(arguments.indexOf(serializationArgument) >= 0, arguments.toString());
        assertTrue(arguments.indexOf(futureArgument) > arguments.indexOf(serializationArgument),
                arguments.toString());
        int optionFlag = arguments.indexOf("-P");
        assertTrue(optionFlag > arguments.indexOf(futureArgument), arguments.toString());
        assertEquals(option.argument(), arguments.get(optionFlag + 1));
    }

    @Test
    void compilerPluginsCoexistWithKaptWithoutBeingDuplicated() {
        Path serialization = tempDir.resolve("tools/serialization.jar");
        Path kapt = tempDir.resolve("tools/kapt.jar");

        List<String> arguments = KotlinCompilerInvocationArguments.build(
                Path.of("/jdk"),
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("classes-kapt"),
                new KotlinCompilerOptions("21", "plugins_kapt_main", false),
                List.of(serialization),
                List.of(new KotlinCompilerPluginOption(
                        "org.jetbrains.kotlin.allopen", "preset", "spring")),
                new KotlinKaptOptions(
                        kapt,
                        new Classpath(List.of(tempDir.resolve("processor.jar"))),
                        tempDir.resolve("generated/sources"),
                        tempDir.resolve("generated/classes"),
                        tempDir.resolve("generated/stubs")),
                ":");

        assertEquals(1, arguments.stream().filter(("-Xplugin=" + serialization)::equals).count());
        assertEquals(1, arguments.stream().filter(("-Xplugin=" + kapt)::equals).count());
        assertEquals(
                1,
                arguments.stream()
                        .filter("plugin:org.jetbrains.kotlin.allopen:preset=spring"::equals)
                        .count());
    }
}
