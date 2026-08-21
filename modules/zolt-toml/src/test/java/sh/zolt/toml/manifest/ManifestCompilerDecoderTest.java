package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestCompilerFields;
import sh.zolt.toml.schema.FinalManifestSchema;

final class ManifestCompilerDecoderTest {
    @Test
    void preservesOmissionWithoutApplyingDefaultsOrInheritance() {
        assertTrue(decode("").isEmpty());

        AuthoredCompiler compiler = decode("""
                [compiler.test]
                jdkApi = "host"
                """).orElseThrow();
        assertTrue(compiler.encoding().isEmpty());
        assertTrue(compiler.jdkApi().isEmpty());
        assertTrue(compiler.args().isEmpty());
        assertEquals(
                AuthoredCompiler.JdkApiMode.HOST,
                compiler.test().orElseThrow().jdkApi().orElseThrow());
        assertTrue(compiler.generated().isEmpty());
    }

    @Test
    void decodesAllSevenCompilerFieldsExactly() {
        AuthoredCompiler compiler = decode("""
                [compiler]
                encoding = "UTF-16"
                jdkApi = "release"
                args = ["-parameters", "-Xlint:all"]

                [compiler.test]
                jdkApi = "host"
                args = ["-g", "-Xlint:none"]

                [compiler.generated]
                main = "generated/sources"
                test = "generated/test-sources"
                """).orElseThrow();

        assertEquals("UTF-16", compiler.encoding().orElseThrow());
        assertEquals(AuthoredCompiler.JdkApiMode.RELEASE, compiler.jdkApi().orElseThrow());
        assertEquals(List.of("-parameters", "-Xlint:all"), compiler.args());
        AuthoredCompiler.Test test = compiler.test().orElseThrow();
        assertEquals(AuthoredCompiler.JdkApiMode.HOST, test.jdkApi().orElseThrow());
        assertEquals(List.of("-g", "-Xlint:none"), test.args());
        AuthoredCompiler.Generated generated = compiler.generated().orElseThrow();
        assertEquals(path("generated/sources"), generated.main().orElseThrow());
        assertEquals(path("generated/test-sources"), generated.test().orElseThrow());
    }

    @Test
    void childOnlyFieldsCreateOnlyTheirAuthoredChildren() {
        AuthoredCompiler testOnly = decode("""
                [compiler.test]
                args = ["-parameters"]
                """).orElseThrow();
        assertTrue(testOnly.encoding().isEmpty());
        assertTrue(testOnly.jdkApi().isEmpty());
        assertTrue(testOnly.args().isEmpty());
        assertTrue(testOnly.test().orElseThrow().jdkApi().isEmpty());

        AuthoredCompiler generatedOnly = decode("""
                [compiler.generated]
                test = "generated/tests"
                """).orElseThrow();
        assertTrue(generatedOnly.encoding().isEmpty());
        assertTrue(generatedOnly.jdkApi().isEmpty());
        assertTrue(generatedOnly.args().isEmpty());
        assertTrue(generatedOnly.test().isEmpty());
        assertTrue(generatedOnly.generated().orElseThrow().main().isEmpty());
        assertEquals(
                path("generated/tests"),
                generatedOnly.generated().orElseThrow().test().orElseThrow());
    }

    @Test
    void preservesArgumentOrderDuplicatesAndModelImmutability() {
        AuthoredCompiler compiler = decode("""
                [compiler]
                args = ["-Xlint:all", "-parameters", "-Xlint:all"]

                [compiler.test]
                args = ["-g", "-g"]
                """).orElseThrow();

        assertEquals(
                List.of("-Xlint:all", "-parameters", "-Xlint:all"),
                compiler.args());
        assertEquals(List.of("-g", "-g"), compiler.test().orElseThrow().args());
        assertThrows(UnsupportedOperationException.class, () -> compiler.args().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> compiler.test().orElseThrow().args().clear());
    }

    @Test
    void anchorsMainAndTestArgumentFailuresToTheirExactIndexes() {
        assertFailure("""
                [compiler]
                args = ["-Xlint:all", "--release=21"]
                """, "Invalid value for `compiler.args[1]`", "Zolt-owned javac option");
        assertFailure("""
                [compiler.test]
                args = ["-parameters", "@javac.options"]
                """, "Invalid value for `compiler.test.args[1]`", "argument files");
        assertFailure("""
                [compiler]
                args = [""]
                """, "Invalid value for `compiler.args[0]`", "must not be blank");
    }

    @Test
    void rejectsMeaninglessExplicitEmptyArgumentAggregates() {
        assertFailure("""
                [compiler]
                args = []
                """, "Invalid value for `compiler.args`", "settings must not be empty");
        assertFailure("""
                [compiler.test]
                args = []
                """, "Invalid value for `compiler.test.args`", "test compiler settings must not be empty");
        assertFailure("""
                [compiler]
                args = []

                [compiler.test]
                args = []
                """, "Invalid value for `compiler.args`", "compiler settings must not be empty");
    }

    @Test
    void acceptsExplicitEmptyArgumentsWhenAnotherFieldHasAJob() {
        AuthoredCompiler main = decode("""
                [compiler]
                encoding = "UTF-8"
                args = []
                """).orElseThrow();
        assertEquals("UTF-8", main.encoding().orElseThrow());
        assertTrue(main.args().isEmpty());

        AuthoredCompiler test = decode("""
                [compiler.test]
                jdkApi = "release"
                args = []
                """).orElseThrow();
        assertEquals(
                AuthoredCompiler.JdkApiMode.RELEASE,
                test.test().orElseThrow().jdkApi().orElseThrow());
        assertTrue(test.test().orElseThrow().args().isEmpty());
    }

    @Test
    void preservesArbitraryEncodingButAppliesModelStringRules() {
        AuthoredCompiler compiler = decode("""
                [compiler]
                encoding = "custom-encoding"
                """).orElseThrow();
        assertEquals("custom-encoding", compiler.encoding().orElseThrow());

        assertFailure("""
                [compiler]
                encoding = "   "
                """, "Invalid value for `compiler.encoding`", "must not be blank");
    }

    @Test
    void usesTheSchemaAsTheExactJdkApiSymbolAuthority() {
        AuthoredCompiler compiler = decode("""
                [compiler]
                jdkApi = "host"

                [compiler.test]
                jdkApi = "release"
                """).orElseThrow();
        assertEquals(AuthoredCompiler.JdkApiMode.HOST, compiler.jdkApi().orElseThrow());
        assertEquals(
                AuthoredCompiler.JdkApiMode.RELEASE,
                compiler.test().orElseThrow().jdkApi().orElseThrow());

        assertFailure("""
                [compiler]
                jdkApi = "current"
                """, "Invalid symbol `current` for `compiler.jdkApi`");
        String family = FinalManifestCompilerFields.COMPILER_JDK_API
                .symbolFamily()
                .orElseThrow();
        List<String> schemaValues = FinalManifestSchema.registry()
                .symbols()
                .family(family)
                .orElseThrow()
                .values();
        Set<String> modelValues = Set.copyOf(Arrays.stream(AuthoredCompiler.JdkApiMode.values())
                .map(AuthoredCompiler.JdkApiMode::configValue)
                .toList());
        assertEquals(Set.copyOf(schemaValues), modelValues);
    }

    @Test
    void anchorsGeneratedPathFailuresToTheirExactScalarFields() {
        assertFailure("""
                [compiler.generated]
                main = "../generated"
                """, "compiler.generated.main");
        assertFailure("""
                [compiler.generated]
                test = "/generated"
                """, "compiler.generated.test");
    }

    private static Optional<AuthoredCompiler> decode(String source) {
        return new ManifestCompilerDecoder().decode(
                ManifestSemanticTestSupport.index(source), ignored -> {});
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
        assertFalse(failure.getMessage().isBlank());
    }
}
