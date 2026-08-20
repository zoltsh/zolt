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
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedExecEnvironmentDecoderTest {
    @ParameterizedTest
    @MethodSource("lanes")
    void decodesSortedImmutableEnvironmentValuesAndPreservesEmptyLiterals(Lane lane) {
        AuthoredExecStep step = exec(lane, """
                env = { ZED = "z", ALPHA = "" }
                secretEnv = { Z_TOKEN = "SOURCE_Z", A_TOKEN = "SOURCE_A" }
                inheritEnv = ["Z_INHERITED", "A_INHERITED"]
                cache = "none"
                """);

        assertEquals(List.of("ALPHA", "ZED"), names(step.env().keySet()));
        assertEquals("", step.env().get(name("ALPHA")));
        assertEquals("z", step.env().get(name("ZED")));
        assertEquals(List.of("A_TOKEN", "Z_TOKEN"), names(step.secretEnv().keySet()));
        assertEquals(name("SOURCE_A"), step.secretEnv().get(name("A_TOKEN")));
        assertEquals(
                List.of("A_INHERITED", "Z_INHERITED"),
                names(step.inheritEnv()));
        assertThrows(UnsupportedOperationException.class, () -> step.env().clear());
        assertThrows(UnsupportedOperationException.class, () -> step.secretEnv().clear());
        assertThrows(UnsupportedOperationException.class, () -> step.inheritEnv().clear());

        AuthoredExecStep empty = exec(
                lane,
                "env = {}\nsecretEnv = {}\ninheritEnv = []\n");
        assertTrue(empty.env().isEmpty());
        assertTrue(empty.secretEnv().isEmpty());
        assertTrue(empty.inheritEnv().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("mapFailures")
    void anchorsOpenMapFailuresToTheOwningFieldWithTheCausalKey(
            Lane lane,
            String additions,
            String field,
            String... details) {
        assertFailure(
                lane.source(base() + additions),
                combine(lane.path(field), details));
    }

    static Stream<Arguments> mapFailures() {
        String nulEscape = "\\" + "u0000";
        return lanes().flatMap(lane -> Stream.of(
                Arguments.of(
                        lane,
                        "env = { GOOD = \"ok\", BAD = \"" + nulEscape + "\" }\n",
                        "env",
                        new String[] {"BAD", "must not contain NUL"}),
                Arguments.of(
                        lane,
                        "env = { PATH = \"one\", Path = \"two\" }\n",
                        "env",
                        new String[] {"Path", "case"}),
                Arguments.of(
                        lane,
                        "env = { VALUE = 2 }\n",
                        "env",
                        new String[] {"found integer"}),
                Arguments.of(
                        lane,
                        "secretEnv = { TOKEN = false }\ncache = \"none\"\n",
                        "secretEnv",
                        new String[] {"found boolean"}),
                Arguments.of(
                        lane,
                        "secretEnv = { FIRST = \"SOURCE\", SECOND = \"source\" }\n"
                                + "cache = \"none\"\n",
                        "secretEnv",
                        new String[] {"SOURCE", "source", "case"}),
                Arguments.of(
                        lane,
                        "env = { TOKEN = \"literal\" }\n"
                                + "secretEnv = { TOKEN = \"SOURCE\" }\ncache = \"none\"\n",
                        "secretEnv",
                        new String[] {"TOKEN", "more than one source"}),
                Arguments.of(
                        lane,
                        "env = { TOKEN = \"literal\" }\n"
                                + "secretEnv = { OTHER = \"token\" }\ncache = \"none\"\n",
                        "secretEnv",
                        new String[] {"OTHER", "token", "case"})));
    }

    @ParameterizedTest
    @MethodSource("lanes")
    void requiresExplicitNoneCachingForEveryNonemptySecretMap(Lane lane) {
        String secret = "secretEnv = { TOKEN = \"SOURCE_TOKEN\" }\n";
        assertFailure(
                lane.source(base() + secret),
                lane.path("secretEnv"),
                "TOKEN",
                "cache = `none`");
        assertFailure(
                lane.source(base() + secret + "cache = \"content\"\n"),
                lane.path("secretEnv"),
                "TOKEN",
                "cache = `none`");

        AuthoredExecStep allowed = exec(lane, secret + "cache = \"none\"\n");
        assertEquals(name("SOURCE_TOKEN"), allowed.secretEnv().get(name("TOKEN")));
    }

    @ParameterizedTest
    @MethodSource("inheritFailures")
    void anchorsInheritedEnvironmentConflictsToTheLaterIndex(
            Lane lane,
            String additions,
            String index,
            String... details) {
        assertFailure(
                lane.source(base() + additions),
                combine(lane.path("inheritEnv") + index, details));
    }

    static Stream<Arguments> inheritFailures() {
        return lanes().flatMap(lane -> Stream.of(
                Arguments.of(
                        lane,
                        "inheritEnv = [\"PATH\", \"PATH\"]\n",
                        "[1]",
                        new String[] {"duplicate"}),
                Arguments.of(
                        lane,
                        "env = { PATH = \"literal\" }\ninheritEnv = [\"OTHER\", \"PATH\"]\n",
                        "[1]",
                        new String[] {"more than one source"}),
                Arguments.of(
                        lane,
                        "secretEnv = { TOKEN = \"SOURCE\" }\ncache = \"none\"\n"
                                + "inheritEnv = [\"OTHER\", \"TOKEN\"]\n",
                        "[1]",
                        new String[] {"more than one source"})));
    }

    @ParameterizedTest
    @MethodSource("grammarFailures")
    void leavesEnvironmentNameGrammarFailuresAtTheirOwningFields(
            Lane lane,
            String additions,
            String field,
            String badName,
            String reason) {
        assertFailure(
                lane.source(base() + additions),
                lane.path(field),
                badName,
                reason);
    }

    static Stream<Arguments> grammarFailures() {
        return lanes().flatMap(lane -> Stream.of(
                Arguments.of(
                        lane,
                        "env = { \"BAD-NAME\" = \"value\" }\n",
                        "env",
                        "BAD-NAME",
                        "Invalid environment-variable name"),
                Arguments.of(
                        lane,
                        "secretEnv = { TARGET = \"BAD-SOURCE\" }\ncache = \"none\"\n",
                        "secretEnv",
                        "BAD-SOURCE",
                        "Invalid environment-variable name"),
                Arguments.of(
                        lane,
                        "inheritEnv = [\"BAD-NAME\"]\n",
                        "inheritEnv",
                        "BAD-NAME",
                        "Invalid environment-variable name"),
                Arguments.of(
                        lane,
                        "inheritEnv = [\"PATH\", \"Path\"]\n",
                        "inheritEnv",
                        "Path",
                        "differ only by ASCII case")));
    }

    static Stream<Lane> lanes() {
        return Stream.of(Lane.values());
    }

    private static String base() {
        return "kind = \"exec\"\ntool = \"tool\"\ninputs = [\"input\"]\n"
                + "output = \"target/generated\"\nproduces = \"java-sources\"\n";
    }

    private static AuthoredExecStep exec(Lane lane, String additions) {
        Map<LocalId, AuthoredGeneratedStep> steps = lane.steps(
                decode(lane.source(base() + additions))).orElseThrow();
        return (AuthoredExecStep) steps.values().iterator().next();
    }

    private static ManifestGeneratedStepsDecoder.Decoded decode(String source) {
        return new ManifestGeneratedStepsDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    private static List<String> names(Iterable<EnvironmentVariableName> names) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        names.forEach(name -> values.add(name.value()));
        return List.copyOf(values);
    }

    private static EnvironmentVariableName name(String value) {
        return new EnvironmentVariableName(value);
    }

    private static String[] combine(String path, String... details) {
        String[] result = new String[details.length + 1];
        result[0] = path;
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
