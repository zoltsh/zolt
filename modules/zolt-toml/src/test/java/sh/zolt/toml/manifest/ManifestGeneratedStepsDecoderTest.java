package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredDeclaredRootStep;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredOpenApiStep;
import sh.zolt.manifest.authored.AuthoredProtobufStep;
import sh.zolt.toml.schema.FinalManifestGeneratedMainFields;
import sh.zolt.toml.schema.FinalManifestGeneratedTestFields;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;

final class ManifestGeneratedStepsDecoderTest {
    @Test
    void observesExactLanesAndEntriesInCanonicalSourceOrder() {
        ArrayList<String> observed = new ArrayList<>();

        new ManifestGeneratedStepsDecoder().decode(
                ManifestSemanticTestSupport.index(interleavedDeclaredRoots()),
                (fields, entry, id, step) -> {
                    String lane;
                    if (fields == ManifestGeneratedStepFields.MAIN) {
                        lane = "main";
                    } else if (fields == ManifestGeneratedStepFields.TEST) {
                        lane = "test";
                    } else {
                        throw new AssertionError("Unexpected generated-step lane.");
                    }
                    assertEquals(entry.key(), id.value());
                    assertInstanceOf(AuthoredDeclaredRootStep.class, step);
                    observed.add(lane + ":" + entry.key() + ":" + entry.section().path());
                });

        assertEquals(
                List.of(
                        "main:z-main:generated.main.z-main",
                        "main:a-main:generated.main.a-main",
                        "test:z-test:generated.test.z-test",
                        "test:a-test:generated.test.a-test"),
                observed);
    }

    @Test
    void preservesIndependentLaneOmissionAndExplicitEmptyPresence() {
        ManifestGeneratedStepsDecoder.Decoded omitted = decode("");
        assertTrue(omitted.main().isEmpty());
        assertTrue(omitted.test().isEmpty());

        for (String source : List.of(
                "[generated.main]\n[generated.test]\n",
                "generated = { main = {}, test = {} }\n")) {
            ManifestGeneratedStepsDecoder.Decoded explicit = decode(source);
            assertTrue(explicit.main().orElseThrow().isEmpty());
            assertTrue(explicit.test().orElseThrow().isEmpty());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> explicit.main().orElseThrow().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> explicit.test().orElseThrow().clear());
        }

        ManifestGeneratedStepsDecoder.Decoded mainOnly = decode("[generated.main]\n");
        assertTrue(mainOnly.main().isPresent());
        assertTrue(mainOnly.test().isEmpty());
        ManifestGeneratedStepsDecoder.Decoded testOnly = decode("[generated.test]\n");
        assertTrue(testOnly.main().isEmpty());
        assertTrue(testOnly.test().isPresent());
    }

    @Test
    void decodesEveryKindInBothLanesAndSortsEachImmutableMap() {
        ManifestGeneratedStepsDecoder.Decoded decoded = decode(allKinds());

        for (Map<LocalId, AuthoredGeneratedStep> lane :
                List.of(decoded.main().orElseThrow(), decoded.test().orElseThrow())) {
            assertEquals(
                    List.of("declared", "exec", "open-api", "protobuf"),
                    lane.keySet().stream().map(LocalId::value).toList());
            assertInstanceOf(AuthoredDeclaredRootStep.class, lane.get(id("declared")));
            assertInstanceOf(AuthoredExecStep.class, lane.get(id("exec")));
            assertInstanceOf(AuthoredOpenApiStep.class, lane.get(id("open-api")));
            assertInstanceOf(AuthoredProtobufStep.class, lane.get(id("protobuf")));
            assertThrows(UnsupportedOperationException.class, lane::clear);
        }
    }

    @Test
    void leavesBehavioralDefaultsAndReferenceResolutionForLaterPhases() {
        ManifestGeneratedStepsDecoder.Decoded decoded = decode(allKinds());

        for (Map<LocalId, AuthoredGeneratedStep> lane :
                List.of(decoded.main().orElseThrow(), decoded.test().orElseThrow())) {
            AuthoredOpenApiStep openApi = assertInstanceOf(
                    AuthoredOpenApiStep.class, lane.get(id("open-api")));
            assertTrue(openApi.settings().language().isEmpty());
            assertTrue(openApi.settings().required().isEmpty());
            assertTrue(openApi.settings().clean().isEmpty());
            assertEquals(Optional.of(id("missing-openapi-tool")), openApi.tool());
            assertTrue(openApi.output().isEmpty());
            assertEquals(Optional.of(id("missing-preset")), openApi.preset());

            AuthoredProtobufStep protobuf = assertInstanceOf(
                    AuthoredProtobufStep.class, lane.get(id("protobuf")));
            assertTrue(protobuf.tool().isEmpty());
            assertTrue(protobuf.output().isEmpty());
            assertTrue(protobuf.grpc().isEmpty());

            AuthoredExecStep exec = assertInstanceOf(
                    AuthoredExecStep.class, lane.get(id("exec")));
            assertEquals(id("missing-exec-tool"), exec.tool());
            assertTrue(exec.cache().isEmpty());
            assertTrue(exec.cwd().isEmpty());
            assertTrue(exec.into().isEmpty());
            assertTrue(exec.timeoutSeconds().isEmpty());
            assertTrue(exec.settings().required().isEmpty());
            assertTrue(exec.settings().clean().isEmpty());

            AuthoredDeclaredRootStep declared = assertInstanceOf(
                    AuthoredDeclaredRootStep.class, lane.get(id("declared")));
            assertTrue(declared.settings().required().isEmpty());
            assertTrue(declared.settings().clean().isEmpty());
        }
    }

    @Test
    void freezesEveryGeneratedStepSymbolFamilyForBothLanes() {
        assertSymbolParity(
                FinalManifestGeneratedMainFields.GENERATED_MAIN_KIND,
                FinalManifestGeneratedTestFields.GENERATED_TEST_KIND,
                List.of("openapi", "protobuf", "exec", "declared-root"));
        assertSymbolParity(
                FinalManifestGeneratedMainFields.GENERATED_MAIN_LANGUAGE,
                FinalManifestGeneratedTestFields.GENERATED_TEST_LANGUAGE,
                List.of("java"));
        assertSymbolParity(
                FinalManifestGeneratedMainFields.GENERATED_MAIN_PRODUCES,
                FinalManifestGeneratedTestFields.GENERATED_TEST_PRODUCES,
                List.of(
                        "java-sources",
                        "test-sources",
                        "resources",
                        "test-resources",
                        "intermediate"));
        assertSymbolParity(
                FinalManifestGeneratedMainFields.GENERATED_MAIN_CACHE,
                FinalManifestGeneratedTestFields.GENERATED_TEST_CACHE,
                List.of("content", "none"));
    }

    @Test
    void keepsEveryLaneAdapterSlotExactlyParallel() {
        assertEquals(35, ManifestGeneratedStepFields.Slot.values().length);
        for (ManifestGeneratedStepFields.Slot slot : ManifestGeneratedStepFields.Slot.values()) {
            ManifestField mainField = ManifestGeneratedStepFields.MAIN.field(slot);
            ManifestField testField = ManifestGeneratedStepFields.TEST.field(slot);
            assertSame(
                    FinalManifestSchema.registry().field(mainField.path()).orElseThrow(),
                    mainField);
            assertSame(
                    FinalManifestSchema.registry().field(testField.path()).orElseThrow(),
                    testField);
            assertEquals(
                    ManifestGeneratedStepFields.MAIN.entry().child(slot.fieldName()),
                    mainField.path());
            assertEquals(
                    ManifestGeneratedStepFields.TEST.entry().child(slot.fieldName()),
                    testField.path());
            assertEquals(
                    mainField.path().segments().getLast(),
                    testField.path().segments().getLast());
            assertEquals(mainField.valueKind(), testField.valueKind());
            assertEquals(100, testField.canonicalOrder() - mainField.canonicalOrder());
        }
    }

    @Test
    void failsClosedWhenValidatedSymbolEvidenceDriftsPastTheSchema() {
        for (String lane : List.of("main", "test")) {
            for (Drift drift : List.of(
                    new Drift("kind", "kind = \"openapi\"\ninput = \"api.yaml\"\n"),
                    new Drift(
                            "language",
                            "kind = \"declared-root\"\nlanguage = \"java\"\n"
                                    + "inputs = [\"input\"]\noutput = \"target/generated\"\n"),
                    new Drift("produces", execDriftBody("")),
                    new Drift("cache", execDriftBody("cache = \"content\"\n")))) {
                assertDrift(lane, drift);
            }
        }
    }

    @Test
    void rejectsForgedKindBeforeLaterForgedLanguage() {
        String source = """
                [generated.main.step]
                kind = "declared-root"
                language = "java"
                inputs = ["input"]
                output = "target/generated"
                """;
        ValidatedManifestShape shape = new ManifestShapeValidator().validate(
                new TomlSyntaxParser().parse(source));
        List<ValidatedManifestField> fields = shape.fields().stream()
                .map(field -> {
                    String path = field.path().toString();
                    if (path.endsWith(".kind")) {
                        return new ValidatedManifestField(
                                field.path(), field.schema(), "future-kind", field.source());
                    }
                    if (path.endsWith(".language")) {
                        return new ValidatedManifestField(
                                field.path(), field.schema(), "future-language", field.source());
                    }
                    return field;
                })
                .toList();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new ManifestGeneratedStepsDecoder().decode(new ManifestDecodeIndex(
                        new ValidatedManifestShape(shape.sections(), fields))));

        assertEquals(
                "Final manifest schema accepted generated-step kind `future-kind` at "
                        + "`generated.main.step.kind` but the decoder does not recognize it.",
                failure.getMessage());
    }

    private static void assertSymbolParity(
            ManifestField main,
            ManifestField test,
            List<String> expected) {
        String mainFamily = main.symbolFamily().orElseThrow();
        assertEquals(mainFamily, test.symbolFamily().orElseThrow());
        assertEquals(
                expected,
                FinalManifestSchema.registry()
                        .symbols()
                        .family(mainFamily)
                        .orElseThrow()
                        .values());
    }

    private static void assertDrift(String lane, Drift drift) {
        String source = "[generated." + lane + ".step]\n" + drift.body();
        ValidatedManifestShape shape = new ManifestShapeValidator().validate(
                new TomlSyntaxParser().parse(source));
        String forgedValue = "future-" + drift.field();
        List<ValidatedManifestField> fields = shape.fields().stream()
                .map(field -> field.path().toString().endsWith("." + drift.field())
                        ? new ValidatedManifestField(
                                field.path(), field.schema(), forgedValue, field.source())
                        : field)
                .toList();
        ManifestDecodeIndex forged = new ManifestDecodeIndex(
                new ValidatedManifestShape(shape.sections(), fields));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new ManifestGeneratedStepsDecoder().decode(forged));
        assertTrue(failure.getMessage().contains(forgedValue), failure.getMessage());
        assertTrue(failure.getMessage().contains("does not recognize"), failure.getMessage());
    }

    private static String execDriftBody(String extra) {
        return "kind = \"exec\"\ntool = \"tool\"\ninputs = [\"input\"]\n"
                + "output = \"target/generated\"\nproduces = \"java-sources\"\n"
                + extra;
    }

    private static ManifestGeneratedStepsDecoder.Decoded decode(String source) {
        return new ManifestGeneratedStepsDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    private static String allKinds() {
        StringBuilder source = new StringBuilder();
        for (String lane : List.of("main", "test")) {
            source.append("[generated.").append(lane).append(".protobuf]\n")
                    .append("kind = \"protobuf\"\n")
                    .append("inputs = [\"proto/z.proto\", \"proto/a.proto\"]\n\n")
                    .append("[generated.").append(lane).append(".open-api]\n")
                    .append("kind = \"openapi\"\n")
                    .append("tool = \"missing-openapi-tool\"\n")
                    .append("input = \"openapi/api.yaml\"\n")
                    .append("preset = \"missing-preset\"\n\n")
                    .append("[generated.").append(lane).append(".exec]\n")
                    .append("kind = \"exec\"\n")
                    .append("tool = \"missing-exec-tool\"\n")
                    .append("inputs = [\"schema.sql\"]\n")
                    .append("output = \"target/generated/exec\"\n")
                    .append("produces = \"java-sources\"\n\n")
                    .append("[generated.").append(lane).append(".declared]\n")
                    .append("kind = \"declared-root\"\n")
                    .append("inputs = [\"fixtures\"]\n")
                    .append("output = \"target/generated/fixtures\"\n\n");
        }
        return source.toString();
    }

    private static String interleavedDeclaredRoots() {
        return """
                [generated.test.z-test]
                kind = "declared-root"
                inputs = ["test-z"]
                output = "target/test-z"

                [generated.main.z-main]
                kind = "declared-root"
                inputs = ["main-z"]
                output = "target/main-z"

                [generated.main.a-main]
                kind = "declared-root"
                inputs = ["main-a"]
                output = "target/main-a"

                [generated.test.a-test]
                kind = "declared-root"
                inputs = ["test-a"]
                output = "target/test-a"
                """;
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private record Drift(String field, String body) {
    }
}
