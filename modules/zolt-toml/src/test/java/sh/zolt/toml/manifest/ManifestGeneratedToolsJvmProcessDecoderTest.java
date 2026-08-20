package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedToolsJvmProcessDecoderTest {
    private static final String REQUEST =
            "{ coordinate = \"org.example:tool\", version = \"1.0.0\" }";

    @Test
    void decodesImmutableSourceOrderedJvmArtifactRequestsWithoutResolvingReferences() {
        AuthoredGeneratedTool.Jvm tool = jvm("""
                [generated.tools.codegen]
                kind = "jvm"
                coordinates = [
                    { coordinate = "org.example:runner", versionRef = "undeclared-version" },
                    { coordinate = "org.example:helper", version = "2.1.0" },
                ]
                mainClass = "org.example.codegen.Main"
                """);

        assertEquals(
                List.of("org.example:runner", "org.example:helper"),
                tool.coordinates().stream()
                        .map(GeneratedArtifactRequest::coordinate)
                        .map(Object::toString)
                        .toList());
        DependencySelector.VersionReference reference = assertInstanceOf(
                DependencySelector.VersionReference.class,
                tool.coordinates().getFirst().selector());
        assertEquals(new LocalId("undeclared-version"), reference.alias());
        assertInstanceOf(
                DependencySelector.FixedVersion.class,
                tool.coordinates().get(1).selector());
        assertEquals("org.example.codegen.Main", tool.mainClass().value());
        assertThrows(UnsupportedOperationException.class, () -> tool.coordinates().clear());
    }

    @Test
    void requiresJvmFieldsInCanonicalOrderAndRejectsAnEmptyCoordinateList() {
        assertFailure(
                "[generated.tools.codegen]\nkind = \"jvm\"\n",
                "Missing required manifest field `generated.tools.codegen.coordinates`.");
        assertFailure(
                "[generated.tools.codegen]\nkind = \"jvm\"\ncoordinates = [" + REQUEST + "]\n",
                "Missing required manifest field `generated.tools.codegen.mainClass`.");
        assertFailure(
                """
                [generated.tools.codegen]
                kind = "jvm"
                coordinates = []
                mainClass = "org.example.Tool"
                """,
                "Invalid value for `generated.tools.codegen.coordinates`",
                "requires at least one coordinate");
    }

    @ParameterizedTest
    @MethodSource("invalidArtifactMembers")
    void anchorsInvalidArtifactMembersAtTheirExactIndexedPaths(
            String request,
            String path) {
        assertFailure(
                """
                [generated.tools.codegen]
                kind = "jvm"
                coordinates = [%s]
                mainClass = "org.example.Tool"
                """.formatted(request),
                "Invalid value for `generated.tools.codegen.coordinates[0]." + path + "`");
    }

    static Stream<Arguments> invalidArtifactMembers() {
        return Stream.of(
                Arguments.of(
                        "{ coordinate = \"bad\", version = \"1.0.0\" }",
                        "coordinate"),
                Arguments.of(
                        "{ coordinate = \"org.example:tool\", version = \"LATEST\" }",
                        "version"),
                Arguments.of(
                        "{ coordinate = \"org.example:tool\", versionRef = \"Bad_Id\" }",
                        "versionRef"));
    }

    @Test
    void anchorsDuplicateJvmCoordinatesToTheLaterCoordinateMember() {
        assertFailure(
                """
                [generated.tools.codegen]
                kind = "jvm"
                coordinates = [
                    { coordinate = "org.example:tool", version = "1.0.0" },
                    { coordinate = "org.example:tool", versionRef = "other" },
                ]
                mainClass = "org.example.Tool"
                """,
                "Invalid value for `generated.tools.codegen.coordinates[1].coordinate`",
                "must not contain duplicate");
    }

    @Test
    void anchorsJvmMainClassBeforeAForbiddenLaterProcessField() {
        assertFailure(
                """
                [generated.tools.codegen]
                kind = "jvm"
                coordinates = [{ coordinate = "org.example:tool", version = "1.0.0" }]
                mainClass = "Unqualified"
                binary = "tool"
                """,
                "Invalid value for `generated.tools.codegen.mainClass`",
                "fully qualified");
    }

    @Test
    void decodesProcessIdentityWithoutLookingUpOrRunningTheBinary() {
        AuthoredGeneratedTool.Process tool = process("""
                [generated.tools.external]
                kind = "process"
                binary = "definitely-not-an-installed-zolt-tool"
                versionCommand = ["definitely-not-an-installed-zolt-tool", "--version"]
                versionExpect = ">=10 <11"
                allowUnpinnedTool = true
                """);

        assertEquals("definitely-not-an-installed-zolt-tool", tool.binary().value());
        assertEquals(
                List.of("definitely-not-an-installed-zolt-tool", "--version"),
                tool.versionCommand());
        assertEquals(">=10 <11", tool.versionExpect().orElseThrow().value());
        assertTrue(tool.allowUnpinnedTool());
        assertThrows(UnsupportedOperationException.class, () -> tool.versionCommand().clear());

        AuthoredGeneratedTool.Process withoutExpectation = process("""
                [generated.tools.external]
                kind = "process"
                binary = "not-installed"
                versionCommand = ["not-installed", "", "--version"]
                allowUnpinnedTool = true
                """);
        assertEquals(List.of("not-installed", "", "--version"), withoutExpectation.versionCommand());
        assertTrue(withoutExpectation.versionExpect().isEmpty());
    }

    @Test
    void requiresProcessFieldsInCanonicalOrder() {
        assertFailure(
                "[generated.tools.external]\nkind = \"process\"\n",
                "Missing required manifest field `generated.tools.external.binary`.");
        assertFailure(
                """
                [generated.tools.external]
                kind = "process"
                binary = "npm"
                """,
                "Missing required manifest field `generated.tools.external.versionCommand`.");
        assertFailure(
                """
                [generated.tools.external]
                kind = "process"
                binary = "npm"
                versionCommand = ["npm", "--version"]
                """,
                "Missing required manifest field `generated.tools.external.allowUnpinnedTool`.");
    }

    @Test
    void anchorsProcessInvariantFailuresToTheirExactFieldsAndIndices() {
        assertFailure(
                processSource("bin/npm", "[\"bin/npm\", \"--version\"]", null, true),
                "Invalid value for `generated.tools.external.binary`",
                "bare executable name");
        assertFailure(
                processSource("npm", "[]", null, true),
                "Invalid value for `generated.tools.external.versionCommand`",
                "nonempty version command");
        assertFailure(
                processSource("npm", "[\"node\", \"--version\"]", null, true),
                "Invalid value for `generated.tools.external.versionCommand[0]`",
                "probe its configured binary exactly");
        String nulEscape = "\\" + "u0000";
        assertFailure(
                processSource("npm", "[\"npm\", \"" + nulEscape + "\"]", null, true),
                "Invalid value for `generated.tools.external.versionCommand[1]`",
                "must not contain NUL");
        assertFailure(
                processSource("npm", "[\"npm\", \"--version\"]", "^10", true),
                "Invalid value for `generated.tools.external.versionExpect`",
                "numeric comparator terms");
        assertFailure(
                processSource("npm", "[\"npm\", \"--version\"]", null, false),
                "Invalid value for `generated.tools.external.allowUnpinnedTool`",
                "requires allowUnpinnedTool = true");
    }

    @Test
    void rejectsAnEarlierForbiddenJvmFieldBeforeProcessBinaryValidation() {
        assertFailure(
                """
                [generated.tools.external]
                kind = "process"
                binary = "bin/npm"
                versionCommand = ["npm", "--version"]
                allowUnpinnedTool = true
                mainClass = "org.example.Tool"
                """,
                "Invalid value for `generated.tools.external.mainClass`",
                "selected generated-tool kind does not allow this field");
    }

    @ParameterizedTest
    @MethodSource("disallowedExecutableFields")
    void enforcesTheExactJvmAndProcessAllowedFieldMatrix(
            String source,
            String field) {
        assertFailure(
                source,
                "Invalid value for `generated.tools.custom." + field + "`",
                "selected generated-tool kind does not allow this field");
    }

    static Stream<Arguments> disallowedExecutableFields() {
        Stream<Arguments> jvm = disallowed(
                jvmBase(),
                List.of(openApiFields(), protocFields(), grpcFields(), processFields()));
        Stream<Arguments> process = disallowed(
                processBase(),
                List.of(openApiFields(), protocFields(), grpcFields(), jvmFields()));
        return Stream.concat(jvm, process);
    }

    private static Stream<Arguments> disallowed(
            String base,
            List<List<String>> groups) {
        return groups.stream()
                .flatMap(List::stream)
                .map(assignment -> Arguments.of(
                        base + assignment + "\n", fieldName(assignment)));
    }

    private static List<String> openApiFields() {
        return List.of(
                "coordinate = \"org.example:tool\"",
                "version = \"1.0.0\"",
                "versionRef = \"tool\"");
    }

    private static List<String> protocFields() {
        return List.of(
                "protocCoordinate = \"org.example:protoc\"",
                "protocVersion = \"1.0.0\"",
                "protocVersionRef = \"protoc\"");
    }

    private static List<String> grpcFields() {
        return List.of(
                "grpcCoordinate = \"org.example:grpc\"",
                "grpcVersion = \"1.0.0\"",
                "grpcVersionRef = \"grpc\"");
    }

    private static List<String> jvmFields() {
        return List.of(
                "coordinates = [" + REQUEST + "]",
                "mainClass = \"org.example.Tool\"");
    }

    private static List<String> processFields() {
        return List.of(
                "binary = \"tool\"",
                "versionCommand = [\"tool\", \"--version\"]",
                "versionExpect = \">=1\"",
                "allowUnpinnedTool = true");
    }

    private static String jvmBase() {
        return "[generated.tools.custom]\nkind = \"jvm\"\ncoordinates = ["
                + REQUEST + "]\nmainClass = \"org.example.Tool\"\n";
    }

    private static String processBase() {
        return "[generated.tools.custom]\nkind = \"process\"\nbinary = \"tool\"\n"
                + "versionCommand = [\"tool\", \"--version\"]\n"
                + "allowUnpinnedTool = true\n";
    }

    private static String processSource(
            String binary,
            String command,
            String expectation,
            boolean acknowledgement) {
        return "[generated.tools.external]\nkind = \"process\"\n"
                + "binary = \"" + binary + "\"\n"
                + "versionCommand = " + command + "\n"
                + (expectation == null ? "" : "versionExpect = \"" + expectation + "\"\n")
                + "allowUnpinnedTool = " + acknowledgement + "\n";
    }

    private static String fieldName(String assignment) {
        return assignment.substring(0, assignment.indexOf(' '));
    }

    private static AuthoredGeneratedTool.Jvm jvm(String source) {
        return assertInstanceOf(
                AuthoredGeneratedTool.Jvm.class,
                declaration(source));
    }

    private static AuthoredGeneratedTool.Process process(String source) {
        return assertInstanceOf(
                AuthoredGeneratedTool.Process.class,
                declaration(source));
    }

    private static AuthoredGeneratedTool declaration(String source) {
        AuthoredGeneratedTools tools = decode(source).orElseThrow();
        return tools.declarations().values().iterator().next();
    }

    private static Optional<AuthoredGeneratedTools> decode(String source) {
        return new ManifestGeneratedToolsDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}
