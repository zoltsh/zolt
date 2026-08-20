package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.GeneratedLanguage;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredDeclaredRootStep;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.manifest.authored.AuthoredOpenApiStep;
import sh.zolt.manifest.authored.AuthoredProtobufStep;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedTypedStepsDecoderTest {
    @ParameterizedTest
    @MethodSource("lanes")
    void decodesEveryOpenApiOverrideWithoutResolvingToolOrPreset(Lane lane) {
        AuthoredOpenApiStep step = assertInstanceOf(
                AuthoredOpenApiStep.class,
                step(lane, """
                        kind = "openapi"
                        language = "java"
                        tool = "undeclared-tool"
                        input = "openapi/api.yaml"
                        output = "missing/generated/openapi"
                        preset = "undeclared-preset"
                        generator = "java"
                        library = "webclient"
                        apiPackage = "org.example.api"
                        modelPackage = "org.example.model"
                        invokerPackage = "org.example.invoker"
                        config = "missing/openapi/config.json"
                        templateDir = "missing/openapi/templates"
                        validateSpec = false
                        options = { zKey = "", "Case.Key" = "line one\\nline two" }
                        additionalProperties = { hideGenerationTimestamp = "true" }
                        configOptions = { useJakartaEe = "true" }
                        globalProperties = { models = "" }
                        typeMappings = { OffsetDateTime = "Instant" }
                        importMappings = { Instant = "java.time.Instant" }
                        required = false
                        clean = false
                        """));

        assertEquals(Optional.of(GeneratedLanguage.JAVA), step.settings().language());
        assertEquals(Optional.of(false), step.settings().required());
        assertEquals(Optional.of(false), step.settings().clean());
        assertEquals(Optional.of(id("undeclared-tool")), step.tool());
        assertEquals(new ResourceGlob("openapi/api.yaml"), step.input());
        assertEquals(Optional.of(path("missing/generated/openapi")), step.output());
        assertEquals(Optional.of(id("undeclared-preset")), step.preset());

        AuthoredOpenApiOptions options = step.overrides();
        assertEquals("java", options.generator().orElseThrow());
        assertEquals("webclient", options.library().orElseThrow());
        assertEquals("org.example.api", options.apiPackage().orElseThrow());
        assertEquals("org.example.model", options.modelPackage().orElseThrow());
        assertEquals("org.example.invoker", options.invokerPackage().orElseThrow());
        assertEquals(path("missing/openapi/config.json"), options.config().orElseThrow());
        assertEquals(path("missing/openapi/templates"), options.templateDir().orElseThrow());
        assertFalse(options.validateSpec().orElseThrow());
        assertEquals(List.of("Case.Key", "zKey"), List.copyOf(options.options().keySet()));
        assertEquals("line one\nline two", options.options().get("Case.Key"));
        assertEquals("", options.options().get("zKey"));
        assertEquals("true", options.additionalProperties().get("hideGenerationTimestamp"));
        assertEquals("true", options.configOptions().get("useJakartaEe"));
        assertEquals("", options.globalProperties().get("models"));
        assertEquals("Instant", options.typeMappings().get("OffsetDateTime"));
        assertEquals("java.time.Instant", options.importMappings().get("Instant"));
        assertThrows(UnsupportedOperationException.class, () -> options.options().clear());
    }

    @ParameterizedTest
    @MethodSource("lanes")
    void decodesProtobufAndDeclaredInputsAsSortedImmutableValues(Lane lane) {
        ManifestGeneratedStepsDecoder.Decoded decoded = decode("""
                [generated.%s.z-proto]
                kind = "protobuf"
                language = "java"
                tool = "undeclared-protobuf"
                inputs = ["proto/z.proto", "proto/a.proto"]
                output = "missing/generated/protobuf"
                javaPackage = "org.example.proto"
                grpc = false
                required = false
                clean = false

                [generated.%s.a-root]
                kind = "declared-root"
                inputs = ["fixtures/z", "fixtures/a"]
                output = "missing/generated/fixtures"
                """.formatted(lane.segment(), lane.segment()));
        Map<LocalId, AuthoredGeneratedStep> steps = lane.steps(decoded).orElseThrow();

        AuthoredProtobufStep protobuf = assertInstanceOf(
                AuthoredProtobufStep.class, steps.get(id("z-proto")));
        assertEquals(Optional.of(GeneratedLanguage.JAVA), protobuf.settings().language());
        assertEquals(Optional.of(id("undeclared-protobuf")), protobuf.tool());
        assertEquals(List.of("proto/a.proto", "proto/z.proto"), values(protobuf.inputs()));
        assertEquals(Optional.of(path("missing/generated/protobuf")), protobuf.output());
        assertEquals(Optional.of("org.example.proto"), protobuf.javaPackage());
        assertEquals(Optional.of(false), protobuf.grpc());
        assertEquals(Optional.of(false), protobuf.settings().required());
        assertEquals(Optional.of(false), protobuf.settings().clean());
        assertThrows(UnsupportedOperationException.class, () -> protobuf.inputs().clear());

        AuthoredDeclaredRootStep declared = assertInstanceOf(
                AuthoredDeclaredRootStep.class, steps.get(id("a-root")));
        assertEquals(List.of("fixtures/a", "fixtures/z"), values(declared.inputs()));
        assertEquals(path("missing/generated/fixtures"), declared.output());
        assertTrue(declared.settings().language().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> declared.inputs().clear());
    }

    @ParameterizedTest
    @MethodSource("requiredFailures")
    void reportsTypedRequiredAndCollectionFailuresAtTheCausalPath(
            Lane lane,
            String body,
            String... details) {
        assertFailure(lane.source(body), details);
    }

    static Stream<Arguments> requiredFailures() {
        return lanes().flatMap(lane -> Stream.of(
                Arguments.of(
                        lane,
                        "input = \"api.yaml\"\n",
                        details(lane, "kind", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"openapi\"\n",
                        details(lane, "input", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"protobuf\"\n",
                        details(lane, "inputs", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"declared-root\"\n",
                        details(lane, "inputs", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"declared-root\"\ninputs = [\"fixtures\"]\n",
                        details(lane, "output", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"protobuf\"\ninputs = []\n",
                        details(lane, "inputs", "requires at least one input")),
                Arguments.of(
                        lane,
                        "kind = \"protobuf\"\ninputs = [\"same\", \"same\"]\n",
                        details(lane, "inputs[1]", "must not contain duplicate")),
                Arguments.of(
                        lane,
                        "kind = \"declared-root\"\ninputs = []\n"
                                + "output = \"target/generated\"\n",
                        details(lane, "inputs", "requires at least one input")),
                Arguments.of(
                        lane,
                        "kind = \"declared-root\"\ninputs = [\"same\", \"same\"]\n"
                                + "output = \"target/generated\"\n",
                        details(lane, "inputs[1]", "must not contain duplicate"))));
    }

    @ParameterizedTest
    @MethodSource("invalidTypedValues")
    void anchorsTypedModelAndMapFailuresToTheirOwningField(
            Lane lane,
            String body,
            String... details) {
        assertFailure(lane.source(body), details);
    }

    static Stream<Arguments> invalidTypedValues() {
        String nulEscape = "\\" + "u0000";
        return lanes().flatMap(lane -> Stream.of(
                Arguments.of(
                        lane,
                        "kind = \"openapi\"\ninput = \"api.yaml\"\n"
                                + "options = { count = 2 }\n",
                        details(lane, "options", "key `count`", "found integer")),
                Arguments.of(
                        lane,
                        "kind = \"openapi\"\ninput = \"api.yaml\"\n"
                                + "globalProperties = { models = \"" + nulEscape + "\" }\n",
                        details(lane, "globalProperties", "must not contain NUL")),
                Arguments.of(
                        lane,
                        "kind = \"protobuf\"\ninputs = [\"proto.proto\"]\njavaPackage = \" \"\n",
                        details(lane, "javaPackage", "must not be blank")),
                Arguments.of(
                        lane,
                        "kind = \"protobuf\"\ninputs = [\"../proto.proto\"]\n",
                        details(lane, "inputs", "Invalid resource glob"))));
    }

    static Stream<Lane> lanes() {
        return Stream.of(Lane.values());
    }

    private static AuthoredGeneratedStep step(Lane lane, String body) {
        return lane.steps(decode(lane.source(body))).orElseThrow().get(id("step"));
    }

    private static ManifestGeneratedStepsDecoder.Decoded decode(String source) {
        return new ManifestGeneratedStepsDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    private static List<String> values(List<ResourceGlob> values) {
        return values.stream().map(ResourceGlob::value).toList();
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static String[] details(Lane lane, String field, String... details) {
        String[] result = new String[details.length + 1];
        result[0] = lane.path(field);
        System.arraycopy(details, 0, result, 1, details.length);
        return result;
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }

    private enum Lane {
        MAIN("main") {
            @Override
            Optional<Map<LocalId, AuthoredGeneratedStep>> steps(
                    ManifestGeneratedStepsDecoder.Decoded decoded) {
                return decoded.main();
            }
        },
        TEST("test") {
            @Override
            Optional<Map<LocalId, AuthoredGeneratedStep>> steps(
                    ManifestGeneratedStepsDecoder.Decoded decoded) {
                return decoded.test();
            }
        };

        private final String name;

        Lane(String name) {
            this.name = name;
        }

        String segment() {
            return name;
        }

        String source(String body) {
            return "[generated." + name + ".step]\n" + body;
        }

        String path(String field) {
            return "generated." + name + ".step." + field;
        }

        abstract Optional<Map<LocalId, AuthoredGeneratedStep>> steps(
                ManifestGeneratedStepsDecoder.Decoded decoded);
    }

}
