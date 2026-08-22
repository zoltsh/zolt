package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestTestFields;

final class ManifestTestRuntimeDecoderTest {
    private final ManifestTestRuntimeDecoder decoder = new ManifestTestRuntimeDecoder();

    @Test
    void preservesOmissionAndRecognizesDottedFieldPresence() {
        assertTrue(decode("").isEmpty());

        AuthoredTestRuntime runtime = decode(
                "test.runtime.events = [\"failed\"]\n").orElseThrow();
        assertTrue(runtime.jvmArgs().isEmpty());
        assertTrue(runtime.properties().isEmpty());
        assertTrue(runtime.env().isEmpty());
        assertEquals(List.of(AuthoredTestRuntime.Event.FAILED), runtime.events());
    }

    @Test
    void decodesEveryFieldAsSortedImmutableAuthoredLiterals() {
        AuthoredTestRuntime runtime = decode("""
                [test.runtime]
                jvmArgs = ["${literal}", "-ea"]
                properties = { zeta = "z", alpha = "${literal}" }
                env = { Z_ENV = "z", A_ENV = "${literal}" }
                events = ["failed", "passed", "skipped"]
                """).orElseThrow();

        assertEquals(List.of("${literal}", "-ea"), runtime.jvmArgs());
        assertEquals(List.of("alpha", "zeta"), List.copyOf(runtime.properties().keySet()));
        assertEquals(Map.of("alpha", "${literal}", "zeta", "z"), runtime.properties());
        assertEquals(List.of("A_ENV", "Z_ENV"), names(runtime.env()));
        assertEquals(
                Map.of(name("A_ENV"), "${literal}", name("Z_ENV"), "z"),
                runtime.env());
        assertEquals(
                List.of(
                        AuthoredTestRuntime.Event.PASSED,
                        AuthoredTestRuntime.Event.SKIPPED,
                        AuthoredTestRuntime.Event.FAILED),
                runtime.events());
        assertThrows(UnsupportedOperationException.class, () -> runtime.jvmArgs().add("-ea"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> runtime.properties().put("extra", "value"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> runtime.env().put(name("EXTRA"), "value"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> runtime.events().add(AuthoredTestRuntime.Event.PASSED));
    }

    @Test
    void progressesJvmArgumentsByIndexAndUsesCanonicalFieldPrecedence() {
        assertFailure("""
                [test.runtime]
                jvmArgs = ["${literal}", "-Droot=${project.root}"]
                """, "`test.runtime.jvmArgs[1]`", "removed `${project.root}`");

        ZoltConfigException failure = assertFailure("""
                [test.runtime]
                properties = { root = "${project.root}" }
                jvmArgs = ["${project.root}"]
                """, "`test.runtime.jvmArgs[0]`");
        assertFalse(failure.getMessage().contains("`test.runtime.properties`"), failure.getMessage());
    }

    @Test
    void givesPropertyFailuresTheirOwnerAndCausalKey() {
        ZoltConfigException semantic = assertFailure("""
                [test.runtime]
                properties = { first = "${literal}", root = "${project.root}" }
                """, "`test.runtime.properties`", "Property entry `root`", "removed");
        assertInstanceOf(IllegalArgumentException.class, semantic.getCause());

        ZoltConfigException nonString = assertFailure("""
                [test.runtime]
                properties = { count = 2 }
                """, "`test.runtime.properties`", "key `count`", "found integer");
        assertInstanceOf(IllegalArgumentException.class, nonString.getCause());

        assertFailure("""
                [test.runtime]
                properties = { "${project.root}" = "value" }
                """,
                "`test.runtime.properties`",
                "Property entry `${project.root}`",
                "property name",
                "removed");

        ZoltConfigException sourceOrder = assertFailure("""
                [test.runtime]
                properties = { zeta = "${project.root}", alpha = "${project.root}" }
                """, "`test.runtime.properties`", "Property entry `zeta`");
        assertFalse(sourceOrder.getMessage().contains("Property entry `alpha`"),
                sourceOrder.getMessage());
    }

    @Test
    void givesEnvironmentFailuresTheirOwnerAndSourceContext() {
        ZoltConfigException semantic = assertFailure("""
                [test.runtime]
                env = { FIRST = "${literal}", ROOT = "${project.root}" }
                """, "`test.runtime.env`", "Environment entry `ROOT`", "removed");
        assertInstanceOf(IllegalArgumentException.class, semantic.getCause());

        assertFailure("""
                [test.runtime]
                env = { COUNT = 2 }
                """, "`test.runtime.env`", "expected a string", "found integer");
        assertFailure("""
                [test.runtime]
                env = { APP_ENV = "one", app_env = "two" }
                """, "`test.runtime.env`", "`APP_ENV`", "`app_env`", "ASCII case");
    }

    @Test
    void sortsEventsRejectsTheLaterDuplicateAndKeepsSchemaParity() {
        assertFailure("""
                [test.runtime]
                events = ["failed", "failed"]
                """, "`test.runtime.events[1]`", "duplicate");

        List<String> modelValues = Arrays.stream(AuthoredTestRuntime.Event.values())
                .map(AuthoredTestRuntime.Event::configValue)
                .toList();
        String family = FinalManifestTestFields.TEST_RUNTIME_EVENTS
                .symbolFamily()
                .orElseThrow();
        assertEquals(
                modelValues,
                FinalManifestSchema.registry()
                        .symbols()
                        .family(family)
                        .orElseThrow()
                        .values());
    }

    @Test
    void failsClosedWhenValidatedEventEvidenceDriftsPastTheSchema() {
        ValidatedManifestShape shape = new ManifestShapeValidator().validate(
                new TomlSyntaxParser().parse("[test.runtime]\nevents = [\"passed\"]\n"));
        Object future = Toml.parse("value = [\"future\"]").getArray("value");
        List<ValidatedManifestField> fields = shape.fields().stream()
                .map(field -> field.schema().descriptor()
                                == FinalManifestTestFields.TEST_RUNTIME_EVENTS
                        ? new ValidatedManifestField(
                                field.path(), field.schema(), future, field.source())
                        : field)
                .toList();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(new ManifestDecodeIndex(
                        new ValidatedManifestShape(shape.sections(), fields))));
        assertTrue(failure.getMessage().contains("future"), failure.getMessage());
        assertTrue(failure.getMessage().contains("does not recognize"), failure.getMessage());
    }

    @Test
    void anchorsLoneAndAllEmptyAggregatesToTheFirstPresentCanonicalField() {
        assertFailure("[test.runtime]\njvmArgs = []\n", "`test.runtime.jvmArgs`");
        assertFailure("[test.runtime]\nproperties = {}\n", "`test.runtime.properties`");
        assertFailure("[test.runtime]\nenv = {}\n", "`test.runtime.env`");
        assertFailure("[test.runtime]\nevents = []\n", "`test.runtime.events`");
        assertFailure("""
                [test.runtime]
                events = []
                env = {}
                properties = {}
                jvmArgs = []
                """, "`test.runtime.jvmArgs`");
    }

    @Test
    void acceptsAnEmptyEarlierCollectionWhenALaterFieldIsMeaningful() {
        AuthoredTestRuntime properties = decode("""
                [test.runtime]
                jvmArgs = []
                properties = { enabled = "true" }
                """).orElseThrow();
        assertTrue(properties.jvmArgs().isEmpty());
        assertEquals(Map.of("enabled", "true"), properties.properties());

        AuthoredTestRuntime events = decode("""
                [test.runtime]
                properties = {}
                events = ["passed"]
                """).orElseThrow();
        assertTrue(events.properties().isEmpty());
        assertEquals(List.of(AuthoredTestRuntime.Event.PASSED), events.events());
    }

    private java.util.Optional<AuthoredTestRuntime> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private static ZoltConfigException assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> new ManifestTestRuntimeDecoder().decode(
                        ManifestSemanticTestSupport.index(source)));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
        return failure;
    }

    private static List<String> names(Map<EnvironmentVariableName, String> environment) {
        return environment.keySet().stream().map(EnvironmentVariableName::value).toList();
    }

    private static EnvironmentVariableName name(String value) {
        return new EnvironmentVariableName(value);
    }
}
