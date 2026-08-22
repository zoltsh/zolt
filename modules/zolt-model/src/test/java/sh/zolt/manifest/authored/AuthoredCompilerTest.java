package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestRelativePath;

final class AuthoredCompilerTest {
    @Test
    void retainsOmittedTestModeAndOutputRootRelativeGeneratedPaths() {
        ArrayList<String> mainArgs = new ArrayList<>(List.of("-Xlint:all"));
        AuthoredCompiler compiler = new AuthoredCompiler(
                Optional.of("UTF-8"),
                Optional.of(AuthoredCompiler.JdkApiMode.RELEASE),
                mainArgs,
                Optional.of(new AuthoredCompiler.Test(
                        Optional.empty(), List.of("-parameters"))),
                Optional.of(new AuthoredCompiler.Generated(
                        Optional.of(new ManifestRelativePath("generated/sources/annotations")),
                        Optional.empty())));
        mainArgs.clear();

        assertEquals(List.of("-Xlint:all"), compiler.args());
        assertEquals(Optional.empty(), compiler.test().orElseThrow().jdkApi());
        assertEquals(
                "generated/sources/annotations",
                compiler.generated().orElseThrow().main().orElseThrow().value());
        assertThrows(UnsupportedOperationException.class, () -> compiler.args().clear());
    }

    @Test
    void rejectsEveryFirstClassOwnedJavacArgumentAndArgumentFiles() {
        for (String argument : List.of(
                "--release", "--release=21", "-source", "--target=21", "-encoding",
                "-d", "-classpath", "-cp=x", "--class-path", "-sourcepath",
                "-bootclasspath", "-Xbootclasspath/a:lib", "--module-path=x",
                "-processorpath", "--processor-path=x", "-s", "-h")) {
            assertThrows(IllegalArgumentException.class, () -> compilerWith(argument), argument);
        }
        assertThrows(IllegalArgumentException.class, () -> compilerWith("@javac.options"));
    }

    /**
     * Design §10.4: annotation processing is Zolt-owned, so raw args may not select processors, set a
     * processor path, or change the processing mode. Zolt emits {@code -proc:none} or
     * {@code -processorpath} itself and appends authored args afterwards, where they would win.
     */
    @Test
    void rejectsEveryProcessorSelectionPathAndModeFlagInBothCompilerLanes() {
        for (String argument : List.of(
                "-processor",
                "-processor=com.example.Processor",
                "-processorpath",
                "--processor-path",
                "--processor-path=lib",
                "--processor-module-path",
                "--processor-module-path=mods",
                "-proc",
                "-proc:none",
                "-proc:only",
                "-proc:full")) {
            IllegalArgumentException main = assertThrows(
                    IllegalArgumentException.class, () -> compilerWith(argument), argument);
            assertTrue(main.getMessage().contains("Zolt-owned javac option"), main.getMessage());
            assertTrue(main.getMessage().contains("[dependencies.processor]"), main.getMessage());
            assertTrue(main.getMessage().contains("[dependencies.test-processor]"), main.getMessage());

            IllegalArgumentException test = assertThrows(
                    IllegalArgumentException.class,
                    () -> new AuthoredCompiler.Test(Optional.empty(), List.of(argument)),
                    argument);
            assertTrue(test.getMessage().contains("Test compiler arguments"), test.getMessage());
            assertTrue(test.getMessage().contains("[dependencies.test-processor]"), test.getMessage());
        }
    }

    @Test
    void acceptsAdditionalJavacArgumentsAndRejectsEmptyCompilerTables() {
        assertEquals(
                List.of("-Xlint:all", "-parameters"),
                new AuthoredCompiler(
                                Optional.empty(),
                                Optional.empty(),
                                List.of("-Xlint:all", "-parameters"),
                                Optional.empty(),
                                Optional.empty())
                        .args());
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCompiler(
                Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCompiler(
                Optional.of(" "), Optional.empty(), List.of(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCompiler(
                Optional.of("UTF-8\n"),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCompiler.Test(
                Optional.empty(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredCompiler.Generated(
                Optional.empty(), Optional.empty()));
    }

    private static AuthoredCompiler compilerWith(String argument) {
        return new AuthoredCompiler(
                Optional.empty(),
                Optional.empty(),
                List.of(argument),
                Optional.empty(),
                Optional.empty());
    }
}
