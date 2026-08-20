package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import sh.zolt.manifest.GeneratedCachePolicy;
import sh.zolt.manifest.GeneratedLanguage;
import sh.zolt.manifest.GeneratedOutputKind;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedExecStepDecoderTest {
    @ParameterizedTest
    @MethodSource("lanes")
    void decodesAllExecFieldsWithoutResolvingToolsOrPaths(Lane lane) {
        AuthoredExecStep step = exec(lane, """
                kind = "exec"
                language = "java"
                tool = "undeclared-tool"
                args = ["--z-last", "", "--a-first"]
                inputs = ["schema/z.sql", "schema/a.sql"]
                output = "missing/generated/sql"
                produces = "resources"
                into = "META-INF/generated"
                cache = "none"
                cwd = "missing/tool/work"
                timeoutSeconds = 2147483647
                required = false
                clean = false
                """);

        assertEquals(Optional.of(GeneratedLanguage.JAVA), step.settings().language());
        assertEquals(Optional.of(false), step.settings().required());
        assertEquals(Optional.of(false), step.settings().clean());
        assertEquals(id("undeclared-tool"), step.tool());
        assertTrue(step.mainClass().isEmpty());
        assertEquals(List.of("--z-last", "", "--a-first"), step.args());
        assertEquals(List.of("schema/a.sql", "schema/z.sql"), values(step.inputs()));
        assertEquals(path("missing/generated/sql"), step.output());
        assertEquals(GeneratedOutputKind.RESOURCES, step.produces());
        assertEquals(Optional.of(path("META-INF/generated")), step.into());
        assertEquals(Optional.of(GeneratedCachePolicy.NONE), step.cache());
        assertEquals(Optional.of(path("missing/tool/work")), step.cwd());
        assertEquals(Optional.of(Integer.MAX_VALUE), step.timeoutSeconds());
        assertThrows(UnsupportedOperationException.class, () -> step.args().clear());
        assertThrows(UnsupportedOperationException.class, () -> step.inputs().clear());
    }

    @ParameterizedTest(name = "{0} accepts {1}")
    @MethodSource("outputKinds")
    void acceptsEveryOutputKindInEitherLane(
            Lane lane,
            GeneratedOutputKind outputKind) {
        String into = outputKind.producesResources()
                ? "into = \"META-INF/generated\"\n"
                : "";
        AuthoredExecStep step = exec(
                lane,
                validBase(outputKind.configValue()) + into);

        assertEquals(outputKind, step.produces());
        assertEquals(outputKind.producesResources(), step.into().isPresent());
    }

    static Stream<Arguments> outputKinds() {
        return lanes().flatMap(lane -> Stream.of(GeneratedOutputKind.values())
                .map(kind -> Arguments.of(lane, kind)));
    }

    @ParameterizedTest
    @MethodSource("lanes")
    void preservesExplicitEmptyArgumentsAndProjectMainClassRules(Lane lane) {
        AuthoredExecStep project = exec(
                lane,
                "kind = \"exec\"\ntool = \"project\"\n"
                        + "mainClass = \"org.example.codegen.Main\"\nargs = []\n"
                        + requiredTail("java-sources"));
        assertEquals("org.example.codegen.Main", project.mainClass().orElseThrow().value());
        assertTrue(project.args().isEmpty());

        assertFailure(
                lane.source("kind = \"exec\"\ntool = \"project\"\n"
                        + requiredTail("java-sources")),
                lane.path("mainClass"),
                "Missing required manifest field");
        assertFailure(
                lane.source("kind = \"exec\"\ntool = \"external\"\n"
                        + "mainClass = \"org.example.codegen.Main\"\n"
                        + requiredTail("java-sources")),
                lane.path("mainClass"),
                "valid only with tool `project`");
    }

    @ParameterizedTest
    @MethodSource("requiredFailures")
    void reportsRequiredAndProgressiveListFailuresAtExactFieldsOrIndices(
            Lane lane,
            String body,
            String... details) {
        assertFailure(lane.source(body), details);
    }

    static Stream<Arguments> requiredFailures() {
        String nulEscape = "\\" + "u0000";
        return lanes().flatMap(lane -> Stream.of(
                Arguments.of(
                        lane,
                        "kind = \"exec\"\n",
                        details(lane, "tool", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"exec\"\ntool = \"tool\"\n",
                        details(lane, "inputs", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"exec\"\ntool = \"tool\"\ninputs = [\"input\"]\n",
                        details(lane, "output", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"exec\"\ntool = \"tool\"\ninputs = [\"input\"]\n"
                                + "output = \"target/generated\"\n",
                        details(lane, "produces", "Missing required manifest field")),
                Arguments.of(
                        lane,
                        "kind = \"exec\"\ntool = \"tool\"\ninputs = []\n"
                                + "output = \"target/generated\"\nproduces = \"java-sources\"\n",
                        details(lane, "inputs", "requires at least one input")),
                Arguments.of(
                        lane,
                        "kind = \"exec\"\ntool = \"tool\"\n"
                                + "inputs = [\"same\", \"same\"]\n"
                                + "output = \"target/generated\"\nproduces = \"java-sources\"\n",
                        details(lane, "inputs[1]", "must not contain duplicate")),
                Arguments.of(
                        lane,
                        "kind = \"exec\"\ntool = \"tool\"\n"
                                + "args = [\"valid\", \"" + nulEscape + "\"]\n"
                                + requiredTail("java-sources"),
                        details(lane, "args[1]", "must not contain NUL")),
                Arguments.of(
                        lane,
                        "kind = \"exec\"\ntool = \"tool\"\ninputs = [\"../bad\"]\n"
                                + "output = \"target/generated\"\nproduces = \"java-sources\"\n",
                        details(lane, "inputs", "Invalid resource glob"))));
    }

    @ParameterizedTest
    @MethodSource("lanes")
    void validatesResourceDestinationsAndTimeoutAtTheirCausalFields(Lane lane) {
        for (String produces : List.of("java-sources", "test-sources", "intermediate")) {
            assertFailure(
                    lane.source(validBase(produces) + "into = \"META-INF/generated\"\n"),
                    lane.path("into"),
                    "valid only for resource-producing output kinds");
        }

        for (String timeout : List.of("0", "-1")) {
            assertFailure(
                    lane.source(validBase("java-sources")
                            + "timeoutSeconds = " + timeout + "\n"),
                    lane.path("timeoutSeconds"),
                    "must be positive");
        }
        assertFailure(
                lane.source(validBase("java-sources")
                        + "timeoutSeconds = 2147483648\n"),
                lane.path("timeoutSeconds"),
                "32-bit");
    }

    @Test
    void allowsTheSameStepIdInBothIndependentLanes() {
        ManifestGeneratedStepsDecoder.Decoded decoded = decode("""
                [generated.main.same]
                kind = "exec"
                tool = "main-tool"
                inputs = ["main-input"]
                output = "target/main"
                produces = "java-sources"

                [generated.test.same]
                kind = "exec"
                tool = "test-tool"
                inputs = ["test-input"]
                output = "target/test"
                produces = "test-sources"
                """);

        assertEquals(id("main-tool"), exec(decoded.main().orElseThrow()).tool());
        assertEquals(id("test-tool"), exec(decoded.test().orElseThrow()).tool());
    }

    static Stream<Lane> lanes() {
        return Stream.of(Lane.values());
    }

    private static String validBase(String produces) {
        return "kind = \"exec\"\ntool = \"tool\"\n" + requiredTail(produces);
    }

    private static String requiredTail(String produces) {
        return "inputs = [\"input\"]\noutput = \"target/generated\"\n"
                + "produces = \"" + produces + "\"\n";
    }

    private static AuthoredExecStep exec(Lane lane, String body) {
        return exec(lane.steps(decode(lane.source(body))).orElseThrow());
    }

    private static AuthoredExecStep exec(Map<LocalId, AuthoredGeneratedStep> steps) {
        return (AuthoredExecStep) steps.values().iterator().next();
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
