package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
