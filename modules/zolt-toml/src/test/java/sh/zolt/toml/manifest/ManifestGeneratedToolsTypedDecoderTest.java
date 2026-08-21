package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestGeneratedToolFields;
import sh.zolt.toml.schema.FinalManifestSchema;

final class ManifestGeneratedToolsTypedDecoderTest {
    @Test
    void failsClosedWhenValidatedToolKindEvidenceDriftsPastTheSchema() {
        ValidatedManifestShape shape = new ManifestShapeValidator().validate(
                new TomlSyntaxParser().parse("""
                        [generated.tools.custom]
                        kind = "process"
                        binary = "tool"
                        versionCommand = ["tool", "--version"]
                        allowUnpinnedTool = true
                        """));
        List<ValidatedManifestField> fields = shape.fields().stream()
                .map(field -> field.schema().descriptor()
                                == FinalManifestGeneratedToolFields.GENERATED_TOOL_KIND
                        ? new ValidatedManifestField(
                                field.path(), field.schema(), "future-kind", field.source())
                        : field)
                .toList();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new ManifestGeneratedToolsDecoder().decode(new ManifestDecodeIndex(
                        new ValidatedManifestShape(shape.sections(), fields))));

        assertEquals(
                "Final manifest schema accepted generated-tool kind `future-kind` at "
                        + "`generated.tools.custom.kind` but the decoder does not recognize it.",
                failure.getMessage());
    }

    @Test
    void preservesOmissionAndExplicitEmptyCollectionPresence() {
        assertTrue(decode("").isEmpty());

        for (String source : List.of(
                "[generated.tools]\n",
                "generated = { tools = {} }\n")) {
            AuthoredGeneratedTools tools = decode(source).orElseThrow();
            assertTrue(tools.declarations().isEmpty());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> tools.declarations().clear());
        }
    }

    @Test
    void decodesBuiltInOverridesAndCustomTypedToolsInCanonicalIdOrder() {
        AuthoredGeneratedTools tools = decode("""
                [generated.tools.protobuf]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersionRef = "protobuf-version"
                grpcCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcVersion = "1.70.0"

                [generated.tools.open-api-custom]
                kind = "openapi"
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.tools.protobuf-custom]
                kind = "protobuf"
                protocVersion = "4.30.0"
                grpcVersionRef = "grpc-version"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "openapi-version"
                """).orElseThrow();

        assertEquals(
                List.of("open-api-custom", "openapi", "protobuf", "protobuf-custom"),
                tools.declarations().keySet().stream().map(LocalId::value).toList());

        AuthoredGeneratedTool.OpenApi builtInOpenApi = assertInstanceOf(
                AuthoredGeneratedTool.OpenApi.class,
                tools.declarations().get(id("openapi")));
        assertEquals(
                "org.openapitools:openapi-generator-cli",
                builtInOpenApi.coordinate().orElseThrow().value());
        assertVersionReference(builtInOpenApi.version(), "openapi-version");

        AuthoredGeneratedTool.OpenApi customOpenApi = assertInstanceOf(
                AuthoredGeneratedTool.OpenApi.class,
                tools.declarations().get(id("open-api-custom")));
        assertFixedVersion(customOpenApi.version(), "7.11.0");

        AuthoredGeneratedTool.Protobuf builtInProtobuf = assertInstanceOf(
                AuthoredGeneratedTool.Protobuf.class,
                tools.declarations().get(id("protobuf")));
        assertEquals(
                "com.google.protobuf:protoc",
                builtInProtobuf.protocCoordinate().orElseThrow().value());
        assertVersionReference(builtInProtobuf.protocVersion(), "protobuf-version");
        assertEquals(
                "io.grpc:protoc-gen-grpc-java",
                builtInProtobuf.grpcCoordinate().orElseThrow().value());
        assertFixedVersion(builtInProtobuf.grpcVersion(), "1.70.0");

        AuthoredGeneratedTool.Protobuf customProtobuf = assertInstanceOf(
                AuthoredGeneratedTool.Protobuf.class,
                tools.declarations().get(id("protobuf-custom")));
        assertFixedVersion(customProtobuf.protocVersion(), "4.30.0");
        assertVersionReference(customProtobuf.grpcVersion(), "grpc-version");
    }

    @ParameterizedTest
    @MethodSource("builtInOverrideShapes")
    void decodesBuiltInOverridesFromEveryAcceptedTomlShape(
            String source,
            String id,
            Class<? extends AuthoredGeneratedTool> expectedType) {
        AuthoredGeneratedTool tool = decode(source)
                .orElseThrow()
                .declarations()
                .get(new LocalId(id));
        assertInstanceOf(expectedType, tool);
    }

    static Stream<Arguments> builtInOverrideShapes() {
        return Stream.of(
                Arguments.of(
                        "[generated.tools.openapi]\nversionRef = \"release\"\n",
                        "openapi",
                        AuthoredGeneratedTool.OpenApi.class),
                Arguments.of(
                        "generated.tools.openapi.versionRef = \"release\"\n",
                        "openapi",
                        AuthoredGeneratedTool.OpenApi.class),
                Arguments.of(
                        "generated = { tools = { openapi = { versionRef = \"release\" } } }\n",
                        "openapi",
                        AuthoredGeneratedTool.OpenApi.class),
                Arguments.of(
                        "[generated.tools.protobuf]\nprotocVersionRef = \"release\"\n",
                        "protobuf",
                        AuthoredGeneratedTool.Protobuf.class),
                Arguments.of(
                        "generated.tools.protobuf.protocVersionRef = \"release\"\n",
                        "protobuf",
                        AuthoredGeneratedTool.Protobuf.class),
                Arguments.of(
                        "generated = { tools = { protobuf = { protocVersionRef = \"release\" } } }\n",
                        "protobuf",
                        AuthoredGeneratedTool.Protobuf.class));
    }

    @Test
    void acceptsEmptyCustomTypedOverridesAndFreezesTheKindSymbolAuthority() {
        AuthoredGeneratedTools tools = decode("""
                [generated.tools.api]
                kind = "openapi"

                [generated.tools.proto]
                kind = "protobuf"
                """).orElseThrow();
        assertInstanceOf(AuthoredGeneratedTool.OpenApi.class, tools.declarations().get(id("api")));
        assertInstanceOf(AuthoredGeneratedTool.Protobuf.class, tools.declarations().get(id("proto")));

        String family = FinalManifestGeneratedToolFields.GENERATED_TOOL_KIND
                .symbolFamily()
                .orElseThrow();
        assertEquals(
                Set.of("openapi", "protobuf", "jvm", "process"),
                Set.copyOf(FinalManifestSchema.registry()
                        .symbols()
                        .family(family)
                        .orElseThrow()
                        .values()));
    }

    @Test
    void builtInsDeriveTheirKindsAndCustomToolsRequireKindFirst() {
        assertFailure(
                "[generated.tools.openapi]\nkind = \"openapi\"\n",
                "Invalid value for `generated.tools.openapi.kind`",
                "reserved built-in tool overrides derive their kind");
        assertFailure(
                "[generated.tools.protobuf]\nkind = \"protobuf\"\n",
                "Invalid value for `generated.tools.protobuf.kind`",
                "reserved built-in tool overrides derive their kind");
        assertFailure(
                "[generated.tools.custom]\ncoordinate = \"invalid\"\n",
                "Missing required manifest field `generated.tools.custom.kind`.");
    }

    @ParameterizedTest
    @MethodSource("conflictingSelectors")
    void rejectsTheLaterTypedSelectorAtItsExactPath(
            String kind,
            String fixed,
            String reference) {
        assertFailure(
                "[generated.tools.custom]\nkind = \"" + kind + "\"\n"
                        + fixed + " = \"1.2.3\"\n"
                        + reference + " = \"release\"\n",
                "Invalid value for `generated.tools.custom." + reference + "`",
                "must declare only one of `" + fixed + "` or `" + reference + "`");
    }

    static Stream<Arguments> conflictingSelectors() {
        return Stream.of(
                Arguments.of("openapi", "version", "versionRef"),
                Arguments.of("protobuf", "protocVersion", "protocVersionRef"),
                Arguments.of("protobuf", "grpcVersion", "grpcVersionRef"));
    }

    @Test
    void anchorsTypedValueFailuresBeforeLaterDisallowedFields() {
        assertFailure(
                """
                [generated.tools.custom]
                kind = "openapi"
                coordinate = "not-a-coordinate"
                protocCoordinate = "com.google.protobuf:protoc"
                """,
                "Invalid value for `generated.tools.custom.coordinate`",
                "Invalid dependency coordinate");
        assertFailure(
                """
                [generated.tools.custom]
                kind = "protobuf"
                protocCoordinate = "not-a-coordinate"
                coordinates = [{ coordinate = "org.example:tool", version = "1.0.0" }]
                """,
                "Invalid value for `generated.tools.custom.protocCoordinate`",
                "Invalid dependency coordinate");
    }

    @ParameterizedTest
    @MethodSource("invalidTypedValues")
    void anchorsEveryTypedValueToItsConcreteField(String source, String path) {
        assertFailure(source, "Invalid value for `generated.tools.custom." + path + "`");
    }

    static Stream<Arguments> invalidTypedValues() {
        return Stream.of(
                Arguments.of(
                        "[generated.tools.custom]\nkind = \"openapi\"\ncoordinate = \"bad\"\n",
                        "coordinate"),
                Arguments.of(
                        "[generated.tools.custom]\nkind = \"openapi\"\nversion = \"LATEST\"\n",
                        "version"),
                Arguments.of(
                        "[generated.tools.custom]\nkind = \"openapi\"\nversionRef = \"Bad_Id\"\n",
                        "versionRef"),
                Arguments.of(
                        "[generated.tools.custom]\nkind = \"protobuf\"\nprotocCoordinate = \"bad\"\n",
                        "protocCoordinate"),
                Arguments.of(
                        "[generated.tools.custom]\nkind = \"protobuf\"\nprotocVersion = \"LATEST\"\n",
                        "protocVersion"),
                Arguments.of(
                        "[generated.tools.custom]\nkind = \"protobuf\"\ngrpcCoordinate = \"bad\"\n",
                        "grpcCoordinate"),
                Arguments.of(
                        "[generated.tools.custom]\nkind = \"protobuf\"\ngrpcVersionRef = \"Bad_Id\"\n",
                        "grpcVersionRef"));
    }

    @ParameterizedTest
    @MethodSource("disallowedTypedFields")
    void enforcesTheExactTypedAllowedFieldMatrix(
            String kind,
            String assignment,
            String field) {
        assertFailure(
                "[generated.tools.custom]\nkind = \"" + kind + "\"\n" + assignment + "\n",
                "Invalid value for `generated.tools.custom." + field + "`",
                "selected generated-tool kind does not allow this field");
    }

    static Stream<Arguments> disallowedTypedFields() {
        return Stream.concat(
                disallowed(
                        "openapi",
                        List.of(
                                protocFields(),
                                grpcFields(),
                                jvmFields(),
                                processFields())),
                disallowed(
                        "protobuf",
                        List.of(openApiFields(), jvmFields(), processFields())));
    }

    private static Stream<Arguments> disallowed(String kind, List<List<String>> groups) {
        return groups.stream()
                .flatMap(List::stream)
                .map(assignment -> Arguments.of(kind, assignment, fieldName(assignment)));
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
                "coordinates = [{ coordinate = \"org.example:tool\", version = \"1.0.0\" }]",
                "mainClass = \"org.example.Tool\"");
    }

    private static List<String> processFields() {
        return List.of(
                "binary = \"tool\"",
                "versionCommand = [\"tool\", \"--version\"]",
                "versionExpect = \">=1\"",
                "allowUnpinnedTool = true");
    }

    private static String fieldName(String assignment) {
        return assignment.substring(0, assignment.indexOf(' '));
    }

    private static Optional<AuthoredGeneratedTools> decode(String source) {
        return new ManifestGeneratedToolsDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private static void assertFixedVersion(
            Optional<DependencySelector> selector,
            String expected) {
        DependencySelector.FixedVersion fixed = assertInstanceOf(
                DependencySelector.FixedVersion.class, selector.orElseThrow());
        assertEquals(expected, fixed.value());
    }

    private static void assertVersionReference(
            Optional<DependencySelector> selector,
            String expected) {
        DependencySelector.VersionReference reference = assertInstanceOf(
                DependencySelector.VersionReference.class, selector.orElseThrow());
        assertEquals(id(expected), reference.alias());
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}
