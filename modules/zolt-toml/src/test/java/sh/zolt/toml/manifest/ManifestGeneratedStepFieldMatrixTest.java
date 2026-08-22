package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedStepFieldMatrixTest {
    @ParameterizedTest(name = "{0} {1} rejects {2}")
    @MethodSource("disallowedFields")
    void enforcesEveryKindFieldMatrixInBothLanes(
            Lane lane,
            String kind,
            String field,
            String source) {
        assertFailure(
                lane.source(validBase(kind) + source),
                "Invalid value for `" + lane.path(field) + "`",
                "selected generated-step kind does not allow this field");
    }

    static Stream<Arguments> disallowedFields() {
        return lanes().flatMap(lane -> Stream.of(Kind.values()).flatMap(kind ->
                assignments().stream()
                        .filter(assignment -> !kind.allowed().contains(assignment.slot()))
                        .map(assignment -> Arguments.of(
                                lane,
                                kind.symbol(),
                                assignment.field(),
                                assignment.source()))));
    }

    @ParameterizedTest
    @MethodSource("precedenceFailures")
    void reportsCanonicalDisallowedOrMissingFieldsBeforeLaterFailures(
            Lane lane,
            String body,
            String expectedField) {
        assertFailure(lane.source(body), "`" + lane.path(expectedField) + "`");
    }

    static Stream<Arguments> precedenceFailures() {
        return lanes().flatMap(lane -> Stream.of(
                Arguments.of(lane, "kind = \"openapi\"\nargs = [\"bad\"]\n", "args"),
                Arguments.of(lane, "kind = \"protobuf\"\ninput = \"bad\"\n", "input"),
                Arguments.of(
                        lane,
                        "kind = \"exec\"\ntool = \"tool\"\ninput = \"bad\"\n",
                        "input"),
                Arguments.of(lane, "kind = \"declared-root\"\ntool = \"bad\"\n", "tool"),
                Arguments.of(
                        lane,
                        "kind = \"openapi\"\njavaPackage = \"later\"\n",
                        "input"),
                Arguments.of(
                        lane,
                        "kind = \"declared-root\"\ninputs = [\"a\"]\npreset = \"later\"\n",
                        "output"),
                Arguments.of(
                        lane,
                        "kind = \"openapi\"\ninput = \"../bad\"\ngenerator = \" \"\n",
                        "input"),
                Arguments.of(
                        lane,
                        "kind = \"openapi\"\ninput = \"api.yaml\"\n"
                                + "preset = \"Bad_Id\"\ngenerator = \" \"\n",
                        "preset"),
                Arguments.of(
                        lane,
                        "kind = \"openapi\"\ninput = \"api.yaml\"\n"
                                + "cache = \"none\"\nargs = [\"later-authored\"]\n",
                        "args"),
                Arguments.of(
                        lane,
                        "kind = \"declared-root\"\ninputs = [\"input\"]\n"
                                + "output = \"target/generated\"\npreset = \"first-authored\"\n"
                                + "tool = \"later-authored\"\n",
                        "tool"),
                Arguments.of(
                        lane,
                        "kind = \"protobuf\"\ninputs = [\"proto.proto\"]\n"
                                + "javaPackage = \" \"\ncache = \"none\"\n",
                        "javaPackage")));
    }

    private static Stream<Lane> lanes() {
        return Stream.of(Lane.values());
    }

    private static List<Assignment> assignments() {
        return List.of(
                assignment(ManifestGeneratedStepFields.Slot.LANGUAGE, "\"java\""),
                assignment(ManifestGeneratedStepFields.Slot.TOOL, "\"tool\""),
                assignment(ManifestGeneratedStepFields.Slot.MAIN_CLASS, "\"org.example.Main\""),
                assignment(ManifestGeneratedStepFields.Slot.ARGS, "[\"arg\"]"),
                assignment(ManifestGeneratedStepFields.Slot.INPUT, "\"spec.yaml\""),
                assignment(ManifestGeneratedStepFields.Slot.INPUTS, "[\"input\"]"),
                assignment(ManifestGeneratedStepFields.Slot.OUTPUT, "\"target/generated\""),
                assignment(ManifestGeneratedStepFields.Slot.PRODUCES, "\"java-sources\""),
                assignment(ManifestGeneratedStepFields.Slot.INTO, "\"target/resources\""),
                assignment(ManifestGeneratedStepFields.Slot.PRESET, "\"preset\""),
                assignment(ManifestGeneratedStepFields.Slot.GENERATOR, "\"java\""),
                assignment(ManifestGeneratedStepFields.Slot.LIBRARY, "\"webclient\""),
                assignment(ManifestGeneratedStepFields.Slot.API_PACKAGE, "\"org.example.api\""),
                assignment(ManifestGeneratedStepFields.Slot.MODEL_PACKAGE, "\"org.example.model\""),
                assignment(ManifestGeneratedStepFields.Slot.INVOKER_PACKAGE, "\"org.example.invoker\""),
                assignment(ManifestGeneratedStepFields.Slot.CONFIG, "\"config.json\""),
                assignment(ManifestGeneratedStepFields.Slot.TEMPLATE_DIR, "\"templates\""),
                assignment(ManifestGeneratedStepFields.Slot.VALIDATE_SPEC, "true"),
                assignment(ManifestGeneratedStepFields.Slot.OPTIONS, "{ key = \"value\" }"),
                assignment(ManifestGeneratedStepFields.Slot.ADDITIONAL_PROPERTIES, "{ key = \"value\" }"),
                assignment(ManifestGeneratedStepFields.Slot.CONFIG_OPTIONS, "{ key = \"value\" }"),
                assignment(ManifestGeneratedStepFields.Slot.GLOBAL_PROPERTIES, "{ key = \"value\" }"),
                assignment(ManifestGeneratedStepFields.Slot.TYPE_MAPPINGS, "{ key = \"value\" }"),
                assignment(ManifestGeneratedStepFields.Slot.IMPORT_MAPPINGS, "{ key = \"value\" }"),
                assignment(ManifestGeneratedStepFields.Slot.JAVA_PACKAGE, "\"org.example\""),
                assignment(ManifestGeneratedStepFields.Slot.GRPC, "true"),
                assignment(ManifestGeneratedStepFields.Slot.CACHE, "\"none\""),
                assignment(ManifestGeneratedStepFields.Slot.CWD, "\"work\""),
                assignment(ManifestGeneratedStepFields.Slot.ENV, "{ KEY = \"value\" }"),
                assignment(ManifestGeneratedStepFields.Slot.SECRET_ENV, "{ KEY = \"SOURCE\" }"),
                assignment(ManifestGeneratedStepFields.Slot.INHERIT_ENV, "[\"KEY\"]"),
                assignment(ManifestGeneratedStepFields.Slot.TIMEOUT_SECONDS, "10"),
                assignment(ManifestGeneratedStepFields.Slot.REQUIRED, "true"),
                assignment(ManifestGeneratedStepFields.Slot.CLEAN, "true"));
    }

    private static Assignment assignment(ManifestGeneratedStepFields.Slot slot, String value) {
        String field = ManifestGeneratedStepFields.MAIN.field(slot).path().segments().getLast();
        return new Assignment(slot, field, field + " = " + value + "\n");
    }

    private static String validBase(String kind) {
        return switch (kind) {
            case "openapi" -> "kind = \"openapi\"\ninput = \"api.yaml\"\n";
            case "protobuf" -> "kind = \"protobuf\"\ninputs = [\"proto.proto\"]\n";
            case "exec" -> "kind = \"exec\"\ntool = \"tool\"\ninputs = [\"input\"]\n"
                    + "output = \"target/generated\"\nproduces = \"java-sources\"\n";
            case "declared-root" -> "kind = \"declared-root\"\ninputs = [\"input\"]\n"
                    + "output = \"target/generated\"\n";
            default -> throw new IllegalArgumentException("Unknown generated-step kind " + kind);
        };
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> new ManifestGeneratedStepsDecoder().decode(
                        ManifestSemanticTestSupport.index(source)));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }

    private enum Lane {
        MAIN("main"), TEST("test");

        private final String name;

        Lane(String name) {
            this.name = name;
        }

        String source(String body) {
            return "[generated." + name + ".step]\n" + body;
        }

        String path(String field) {
            return "generated." + name + ".step." + field;
        }
    }

    private enum Kind {
        OPEN_API("openapi", ManifestGeneratedStepFields.Slot.INPUT,
                ManifestGeneratedStepFields.Slot.TOOL,
                ManifestGeneratedStepFields.Slot.OUTPUT,
                ManifestGeneratedStepFields.Slot.PRESET,
                ManifestGeneratedStepFields.Slot.GENERATOR,
                ManifestGeneratedStepFields.Slot.LIBRARY,
                ManifestGeneratedStepFields.Slot.API_PACKAGE,
                ManifestGeneratedStepFields.Slot.MODEL_PACKAGE,
                ManifestGeneratedStepFields.Slot.INVOKER_PACKAGE,
                ManifestGeneratedStepFields.Slot.CONFIG,
                ManifestGeneratedStepFields.Slot.TEMPLATE_DIR,
                ManifestGeneratedStepFields.Slot.VALIDATE_SPEC,
                ManifestGeneratedStepFields.Slot.OPTIONS,
                ManifestGeneratedStepFields.Slot.ADDITIONAL_PROPERTIES,
                ManifestGeneratedStepFields.Slot.CONFIG_OPTIONS,
                ManifestGeneratedStepFields.Slot.GLOBAL_PROPERTIES,
                ManifestGeneratedStepFields.Slot.TYPE_MAPPINGS,
                ManifestGeneratedStepFields.Slot.IMPORT_MAPPINGS),
        PROTOBUF("protobuf", ManifestGeneratedStepFields.Slot.TOOL,
                ManifestGeneratedStepFields.Slot.INPUTS,
                ManifestGeneratedStepFields.Slot.OUTPUT,
                ManifestGeneratedStepFields.Slot.JAVA_PACKAGE,
                ManifestGeneratedStepFields.Slot.GRPC),
        EXEC("exec", ManifestGeneratedStepFields.Slot.TOOL,
                ManifestGeneratedStepFields.Slot.MAIN_CLASS,
                ManifestGeneratedStepFields.Slot.ARGS,
                ManifestGeneratedStepFields.Slot.INPUTS,
                ManifestGeneratedStepFields.Slot.OUTPUT,
                ManifestGeneratedStepFields.Slot.PRODUCES,
                ManifestGeneratedStepFields.Slot.INTO,
                ManifestGeneratedStepFields.Slot.CACHE,
                ManifestGeneratedStepFields.Slot.CWD,
                ManifestGeneratedStepFields.Slot.ENV,
                ManifestGeneratedStepFields.Slot.SECRET_ENV,
                ManifestGeneratedStepFields.Slot.INHERIT_ENV,
                ManifestGeneratedStepFields.Slot.TIMEOUT_SECONDS),
        DECLARED_ROOT("declared-root", ManifestGeneratedStepFields.Slot.INPUTS,
                ManifestGeneratedStepFields.Slot.OUTPUT);

        private final String symbol;
        private final Set<ManifestGeneratedStepFields.Slot> allowed;

        Kind(String symbol, ManifestGeneratedStepFields.Slot... specific) {
            allowed = EnumSet.of(
                    ManifestGeneratedStepFields.Slot.LANGUAGE,
                    ManifestGeneratedStepFields.Slot.REQUIRED,
                    ManifestGeneratedStepFields.Slot.CLEAN);
            allowed.addAll(List.of(specific));
            this.symbol = symbol;
        }

        String symbol() {
            return symbol;
        }

        Set<ManifestGeneratedStepFields.Slot> allowed() {
            return allowed;
        }
    }

    private record Assignment(
            ManifestGeneratedStepFields.Slot slot,
            String field,
            String source) {
    }
}
