package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

final class ManifestShapeValueValidatorTest {
    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @ParameterizedTest
    @MethodSource("validValueKinds")
    void acceptsEveryRegisteredValueKind(String source) {
        validate(source);
    }

    static Stream<String> validValueKinds() {
        return Stream.of(
                "[project]\nname = \"demo\"\n",
                "[project]\njava = 21\n",
                "[coverage]\nline = 0.8\n",
                "[build.metadata]\nbuildInfo = true\n",
                "[build]\nsources = [\"src/main/java\"]\n",
                "[test.runtime]\nproperties = { answer = \"yes\" }\n",
                "[dependencies.policy]\ndeny = [{ coordinate = \"org.example:demo\" }]\n",
                "[project]\nlicense = { id = \"MIT\" }\n",
                "[bom]\nmembers = [\"apps/api\"]\n",
                "[repositories]\ncentral = { url = \"https://repo.example\" }\n");
    }

    @ParameterizedTest
    @MethodSource("invalidValueKinds")
    void rejectsWrongKindsAndHeterogeneousArrays(String source, String expectedKind) {
        assertFailureContains(source, "expected " + expectedKind);
    }

    static Stream<Arguments> invalidValueKinds() {
        return Stream.of(
                Arguments.of("[project]\nname = 1\n", "string"),
                Arguments.of("[project]\njava = \"21\"\n", "integer"),
                Arguments.of("[coverage]\nline = true\n", "number"),
                Arguments.of("[build.metadata]\nbuildInfo = \"true\"\n", "boolean"),
                Arguments.of("[build]\nsources = [\"src\", 1]\n", "string array"),
                Arguments.of("[test.runtime]\nproperties = \"bad\"\n", "inline table"),
                Arguments.of(
                        "[dependencies.policy]\ndeny = [{ coordinate = \"x:y\" }, \"bad\"]\n",
                        "inline table array"),
                Arguments.of("[project]\nlicense = 1\n", "string or inline table"),
                Arguments.of("[bom]\nmembers = 1\n", "boolean or string array"),
                Arguments.of(
                        "[repositories]\ncentral = 1\n",
                        "boolean or string or inline table"));
    }

    @Test
    void validatesScalarAndArraySymbolFamilies() {
        validate("""
                [toolchain.java]
                distribution = "temurin"
                features = ["native-image"]
                """);

        assertFailureContains("""
                [toolchain.java]
                distribution = "Temurin"
                """, "Invalid symbol `Temurin`");
        assertFailureContains("""
                [toolchain.java]
                features = ["native-image", "jlink"]
                """, "Invalid symbol `jlink`");
    }

    @ParameterizedTest
    @MethodSource("invalidDynamicKeys")
    void validatesEveryDynamicKeyGrammar(String source, String expected) {
        assertFailureContains(source, expected);
    }

    static Stream<Arguments> invalidDynamicKeys() {
        return Stream.of(
                Arguments.of(
                        "[credentials.Bad_Id]\ntokenEnv = \"TOKEN\"\n",
                        "Invalid dynamic key `Bad_Id`"),
                Arguments.of(
                        "[dependencies]\n\"not-a-coordinate\" = \"1\"\n",
                        "Invalid dynamic key `not-a-coordinate`"),
                Arguments.of(
                        "[package.manifest]\n\"\" = \"value\"\n",
                        "JAR manifest attribute names must be nonblank"));
    }

    @ParameterizedTest
    @MethodSource("reservedIds")
    void rejectsReservedIdsAcrossHeadersDottedAssignmentsAndInlineParents(String source) {
        assertFailureContains(source, "is reserved");
    }

    static Stream<String> reservedIds() {
        return Stream.of(
                "[repositories.central]\nurl = \"https://repo.example\"\n",
                "[repositories.order]\nurl = \"https://repo.example\"\n",
                "generated.tools.openapi.kind = \"openapi\"\n",
                "generated = { tools = { protobuf = { kind = \"protobuf\" } } }\n",
                "tasks.build.run = [\"test\"]\n",
                "tasks = { publish = { run = [\"check\"] } }\n",
                "aliases.build = [\"test\"]\n",
                "aliases = { clean = [\"check\"] }\n",
                "test.suites.all.classes = [\"**/*Test\"]\n",
                "test = { suites = { all = { classes = [\"**/*Test\"] } } }\n");
    }

    @Test
    void validatesLocalIdsPathsPatternsGlobsAndEnvironmentShapes() {
        assertFailureContains("[workspace]\nname = \"Bad_Name\"\n", "Invalid local ID");
        assertFailureContains(
                "[build]\nsources = [\"src/main\", \"../escape\"]\n",
                "Invalid manifest path");
        assertFailureContains(
                "[workspace.members]\ndefault = [\"apps/*\"]\n",
                "without pattern syntax");
        assertFailureContains(
                "[workspace.members]\ninclude = [\"apps/**\"]\n",
                "complete `*` segment");
        assertFailureContains(
                "[resources.filter]\ninclude = [\"src/**bad\"]\n",
                "use `**` only as a complete path segment");
        assertFailureContains(
                "[credentials.release]\ntokenEnv = \"BAD-NAME\"\n",
                "Invalid environment-variable name");
        assertFailureContains("""
                [generated.main.codegen]
                inheritEnv = ["PATH", "Path"]
                """, "differ only by ASCII case");
        assertFailureContains("""
                [tasks.release]
                env = { VALID = 1 }
                """, "expected a string");
        assertFailureContains("""
                [generated.main.codegen]
                secretEnv = { DB_PASSWORD = "bad-name" }
                """, "Invalid environment-variable name");

        validate("""
                [workspace.members]
                include = ["apps/*", "services/*/api"]
                [build]
                sources = ["src/é"]
                """);
    }

    @Test
    void leavesGenuineEmptyInlineCollectionsAndNestedSemanticsToConstruction() {
        validate("""
                [test.runtime]
                properties = {}
                env = {}
                """);
        validate("""
                [generated.presets.client]
                options = { nested = {} }
                """);
        validate("""
                [generated.presets.client]
                options = { nested = [{ value = "ok" }, {}] }
                """);
        validate("credentials = {}\n");
    }

    private void validate(String source) {
        validator.validate(parser.parse(source));
    }

    private void assertFailureContains(String source, String expected) {
        ZoltConfigException failure = assertThrows(ZoltConfigException.class, () -> validate(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
