package sh.zolt.resolve.fingerprint;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProtobufGenerationSettings;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Generated-source steps reach lock identity through an explicit field encoder, never a toString. */
final class GeneratedSourceFingerprintTest {
    private final ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();

    /**
     * A record {@code toString()} is a diagnostic surface that must stay free to improve. While lock
     * identity embedded it, renaming one component of any nested settings record, or adding a
     * component whose default rendering changed, silently restated every checked-in lock. The encoder
     * names each field instead, and this asserts both halves of that: no toString rendering survives,
     * and every record component is still covered so explicitness did not quietly drop an input.
     */
    @Test
    void generatedSourceFingerprintDoesNotDependOnToString() {
        GeneratedSourceStep step = maximalStep();
        List<String> generated = ProjectResolutionFingerprint.inputs(withGeneratedStep(step)).stream()
                .filter(line -> line.startsWith("generatedMain\t"))
                .toList();

        for (String rendering : List.of(
                "GeneratedSourceStep[",
                "OpenApiGenerationSettings[",
                "ProtobufGenerationSettings[",
                "ExecGenerationSettings[",
                "ExecToolSettings[",
                "ExecToolCoordinate[",
                "Optional[",
                "Optional.empty")) {
            assertTrue(
                    generated.stream().noneMatch(line -> line.contains(rendering)),
                    () -> "generated-source fingerprint lines still carry the " + rendering
                            + " toString rendering: " + generated);
        }

        assertTrue(
                generated.stream().allMatch(line -> line.split("\t", -1)[1].equals(step.id())),
                () -> "every encoded line is keyed by the step id: " + generated);

        // The step id is the key position every line carries, asserted directly above; every other
        // component of every settings record has to appear as a named field.
        Set<String> encoded = generated.stream()
                .map(line -> line.split("\t", -1))
                .filter(parts -> parts.length > 2)
                .map(parts -> parts[2])
                .collect(Collectors.toCollection(LinkedHashSet::new));
        encoded.add("id");
        for (Class<?> settings : List.of(
                GeneratedSourceStep.class,
                OpenApiGenerationSettings.class,
                ProtobufGenerationSettings.class,
                ExecGenerationSettings.class,
                ExecToolSettings.class,
                ExecToolCoordinate.class)) {
            for (RecordComponent component : settings.getRecordComponents()) {
                String name = component.getName();
                assertTrue(
                        encoded.stream().anyMatch(field -> field.equals(name)
                                || field.endsWith("." + name)
                                || field.startsWith(name + ".")
                                || field.contains("." + name + ".")),
                        () -> settings.getSimpleName() + "." + name
                                + " is not encoded into the generated-source fingerprint: " + encoded);
            }
        }
    }

    /** Explicit encoding must still separate two steps that differ only inside a nested setting. */
    @Test
    void nestedGeneratedSourceSettingsStillSeparateFingerprints() {
        GeneratedSourceStep step = maximalStep();
        GeneratedSourceStep other = new GeneratedSourceStep(
                step.id(),
                step.kind(),
                step.language(),
                step.output(),
                step.inputs(),
                step.required(),
                step.clean(),
                step.openApi(),
                step.protobuf(),
                new ExecGenerationSettings(
                        step.exec().toolName(),
                        step.exec().tool(),
                        step.exec().args(),
                        step.exec().produces(),
                        step.exec().into(),
                        step.exec().env(),
                        step.exec().cache(),
                        step.exec().cwd(),
                        step.exec().secretEnv(),
                        step.exec().inheritEnv(),
                        step.exec().timeoutSeconds(),
                        Optional.of("other-salt")));

        assertNotEquals(
                ProjectResolutionFingerprint.fingerprint(withGeneratedStep(step)),
                ProjectResolutionFingerprint.fingerprint(withGeneratedStep(other)));
    }

    private ProjectConfig withGeneratedStep(GeneratedSourceStep step) {
        ProjectConfig base = manifestLoader.load("""
                [project]
                name = "generated"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        return base.withBuildSettings(base.build().withGeneratedSources(List.of(step), List.of()));
    }

    /** One step with every encodable field of every nested settings record populated. */
    private static GeneratedSourceStep maximalStep() {
        return new GeneratedSourceStep(
                "maximal",
                GeneratedSourceKind.EXEC,
                "java",
                "target/generated/sources/maximal",
                List.of("src/main/maximal/one.spec", "src/main/maximal/two.spec"),
                true,
                false,
                new OpenApiGenerationSettings(
                        Optional.of("org.openapitools:openapi-generator-cli"),
                        Optional.of("7.11.0"),
                        Optional.of("openapi"),
                        Optional.of("service"),
                        Optional.of("spring"),
                        Optional.of("spring-boot"),
                        Optional.of("com.example.api"),
                        Optional.of("com.example.model"),
                        Optional.of("com.example.invoker"),
                        Optional.of("openapi-config.yaml"),
                        Optional.of("src/main/openapi/templates"),
                        Optional.of(true),
                        Map.of("optionKey", "optionValue"),
                        Map.of("additionalKey", "additionalValue"),
                        Map.of("configKey", "configValue"),
                        Map.of("globalKey", "globalValue"),
                        Map.of("typeKey", "typeValue"),
                        Map.of("importKey", "importValue")),
                new ProtobufGenerationSettings(
                        Optional.of("com.google.protobuf:protoc"),
                        Optional.of("4.29.3"),
                        Optional.of("protoc"),
                        Optional.of("io.grpc:protoc-gen-grpc-java"),
                        Optional.of("1.70.0"),
                        Optional.of("grpc"),
                        Optional.of("com.example.proto"),
                        true),
                new ExecGenerationSettings(
                        "codegen",
                        new ExecToolSettings(
                                "jvm",
                                List.of(new ExecToolCoordinate(
                                        "com.example:codegen",
                                        Optional.of("1.2.3"),
                                        Optional.of("codegen"))),
                                "com.example.codegen.Main",
                                "codegen",
                                List.of("codegen", "--version"),
                                Optional.of("[1.0,2.0)"),
                                true),
                        List.of("--out", "target/generated/sources/maximal"),
                        ProducesLane.JAVA_SOURCES,
                        Optional.of("generated"),
                        Map.of("CODEGEN_MODE", "strict"),
                        "content",
                        Optional.of("tools/codegen"),
                        Map.of("CODEGEN_TOKEN", "CODEGEN_TOKEN_ENV"),
                        List.of("PATH"),
                        900,
                        Optional.of("salt")));
    }
}
