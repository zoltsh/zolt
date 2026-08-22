package sh.zolt.toml.manifest.write;

import java.util.Map;
import java.util.Objects;
import sh.zolt.manifest.GeneratedCachePolicy;
import sh.zolt.manifest.GeneratedLanguage;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredDeclaredRootStep;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredOpenApiStep;
import sh.zolt.manifest.authored.AuthoredProtobufStep;
import sh.zolt.toml.schema.FinalManifestGeneratedMainFields;
import sh.zolt.toml.schema.FinalManifestGeneratedTestFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits typed main and test generated-source steps in canonical schema order. */
final class ManifestGeneratedStepsWriter {
    private static final LocalId OPENAPI = new LocalId("openapi");
    private static final LocalId PROTOBUF = new LocalId("protobuf");
    private static final Lane MAIN = new Lane(
            section(FinalManifestPaths.GENERATED_MAIN), true, "generated/sources");
    private static final Lane TEST = new Lane(
            section(FinalManifestPaths.GENERATED_TEST), false, "generated/test-sources");

    void write(
            ManifestTomlEmitter emitter,
            Map<LocalId, AuthoredGeneratedStep> main,
            Map<LocalId, AuthoredGeneratedStep> test,
            ManifestRelativePath buildOutputRoot) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        ManifestRelativePath outputRoot = Objects.requireNonNull(
                buildOutputRoot, "Build output root is required.");
        writeLane(
                emitter,
                MAIN,
                Objects.requireNonNull(main, "Main generated steps are required."),
                outputRoot);
        writeLane(
                emitter,
                TEST,
                Objects.requireNonNull(test, "Test generated steps are required."),
                outputRoot);
    }

    private static void writeLane(
            ManifestTomlEmitter emitter,
            Lane lane,
            Map<LocalId, AuthoredGeneratedStep> steps,
            ManifestRelativePath outputRoot) {
        steps.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> writeStep(
                        emitter, lane, entry.getKey(), entry.getValue(), outputRoot));
    }

    private static void writeStep(
            ManifestTomlEmitter emitter,
            Lane lane,
            LocalId id,
            AuthoredGeneratedStep step,
            ManifestRelativePath outputRoot) {
        emitter.namedSection(lane.section(), id.value());
        emitter.field(field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_KIND,
                FinalManifestGeneratedTestFields.GENERATED_TEST_KIND), string(kind(step)));
        writeLanguage(emitter, lane, step.settings());
        switch (step) {
            case AuthoredOpenApiStep openApi ->
                writeOpenApi(emitter, lane, id, openApi, outputRoot);
            case AuthoredProtobufStep protobuf ->
                writeProtobuf(emitter, lane, id, protobuf, outputRoot);
            case AuthoredExecStep exec -> writeExec(emitter, lane, exec);
            case AuthoredDeclaredRootStep declared -> writeDeclared(emitter, lane, declared);
        }
        writeBehavior(emitter, lane, step);
    }

    private static String kind(AuthoredGeneratedStep step) {
        return switch (step) {
            case AuthoredOpenApiStep ignored -> "openapi";
            case AuthoredProtobufStep ignored -> "protobuf";
            case AuthoredExecStep ignored -> "exec";
            case AuthoredDeclaredRootStep ignored -> "declared-root";
        };
    }

    private static void writeLanguage(
            ManifestTomlEmitter emitter, Lane lane, GeneratedStepSettings settings) {
        settings.language()
                .filter(value -> value != GeneratedLanguage.JAVA)
                .ifPresent(value -> emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_LANGUAGE,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_LANGUAGE),
                        string(value.configValue())));
    }

    private static void writeOpenApi(
            ManifestTomlEmitter emitter,
            Lane lane,
            LocalId id,
            AuthoredOpenApiStep step,
            ManifestRelativePath outputRoot) {
        step.tool().filter(value -> !value.equals(OPENAPI)).ifPresent(value -> emitter.field(
                field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_TOOL,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_TOOL),
                string(value.value())));
        emitter.field(field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_INPUT,
                FinalManifestGeneratedTestFields.GENERATED_TEST_INPUT),
                string(step.input().value()));
        step.output()
                .filter(value -> !isDerivedOutput(lane, id, outputRoot, value))
                .ifPresent(value -> emitter.field(field(lane,
                                FinalManifestGeneratedMainFields.GENERATED_MAIN_OUTPUT,
                                FinalManifestGeneratedTestFields.GENERATED_TEST_OUTPUT),
                        string(value.value())));
        step.preset().ifPresent(value -> emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_PRESET,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_PRESET),
                string(value.value())));
        ManifestGeneratedOpenApiOptionsWriter.write(
                emitter, step.overrides(), openApiFields(lane));
    }

    private static void writeProtobuf(
            ManifestTomlEmitter emitter,
            Lane lane,
            LocalId id,
            AuthoredProtobufStep step,
            ManifestRelativePath outputRoot) {
        step.tool().filter(value -> !value.equals(PROTOBUF)).ifPresent(value -> emitter.field(
                field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_TOOL,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_TOOL),
                string(value.value())));
        ManifestField inputsField = field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_INPUTS,
                FinalManifestGeneratedTestFields.GENERATED_TEST_INPUTS);
        emitter.field(inputsField, ManifestGeneratedWriterValues.strings(
                inputsField, step.inputs(), value -> value.value()));
        step.output()
                .filter(value -> !isDerivedOutput(lane, id, outputRoot, value))
                .ifPresent(value -> emitter.field(field(lane,
                                FinalManifestGeneratedMainFields.GENERATED_MAIN_OUTPUT,
                                FinalManifestGeneratedTestFields.GENERATED_TEST_OUTPUT),
                        string(value.value())));
        step.javaPackage().ifPresent(value -> emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_JAVA_PACKAGE,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_JAVA_PACKAGE),
                string(value)));
        step.grpc().filter(value -> !value).ifPresent(value -> emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_GRPC,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_GRPC),
                ManifestTomlValueEncoder.booleanValue(value)));
    }

    private static void writeExec(
            ManifestTomlEmitter emitter, Lane lane, AuthoredExecStep step) {
        emitter.field(field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_TOOL,
                FinalManifestGeneratedTestFields.GENERATED_TEST_TOOL), string(step.tool().value()));
        step.mainClass().ifPresent(value -> emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_MAIN_CLASS,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_MAIN_CLASS),
                string(value.value())));
        if (!step.args().isEmpty()) {
            ManifestField argsField = field(lane,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_ARGS,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_ARGS);
            emitter.field(argsField, ManifestGeneratedWriterValues.strings(
                    argsField, step.args(), value -> value));
        }
        ManifestField inputsField = field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_INPUTS,
                FinalManifestGeneratedTestFields.GENERATED_TEST_INPUTS);
        emitter.field(inputsField, ManifestGeneratedWriterValues.strings(
                inputsField, step.inputs(), value -> value.value()));
        emitter.field(field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_OUTPUT,
                FinalManifestGeneratedTestFields.GENERATED_TEST_OUTPUT),
                string(step.output().value()));
        emitter.field(field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_PRODUCES,
                FinalManifestGeneratedTestFields.GENERATED_TEST_PRODUCES),
                string(step.produces().configValue()));
        step.into().ifPresent(value -> emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_INTO,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_INTO),
                string(value.value())));
        step.cache().filter(value -> value != GeneratedCachePolicy.CONTENT).ifPresent(value ->
                emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_CACHE,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_CACHE),
                        string(value.configValue())));
        step.cwd().ifPresent(value -> emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_CWD,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_CWD),
                string(value.value())));
        if (!step.env().isEmpty()) {
            emitter.field(field(lane,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_ENV,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_ENV),
                    ManifestGeneratedWriterValues.environmentMap(
                            step.env(), value -> value));
        }
        if (!step.secretEnv().isEmpty()) {
            emitter.field(field(lane,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_SECRET_ENV,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_SECRET_ENV),
                    ManifestGeneratedWriterValues.environmentMap(
                            step.secretEnv(), value -> value.value()));
        }
        if (!step.inheritEnv().isEmpty()) {
            ManifestField inheritField = field(lane,
                    FinalManifestGeneratedMainFields.GENERATED_MAIN_INHERIT_ENV,
                    FinalManifestGeneratedTestFields.GENERATED_TEST_INHERIT_ENV);
            emitter.field(inheritField, ManifestGeneratedWriterValues.strings(
                    inheritField, step.inheritEnv(), value -> value.value()));
        }
        step.timeoutSeconds().ifPresent(value -> emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_TIMEOUT_SECONDS,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_TIMEOUT_SECONDS),
                ManifestTomlValueEncoder.integer(value)));
    }

    private static void writeDeclared(
            ManifestTomlEmitter emitter, Lane lane, AuthoredDeclaredRootStep step) {
        ManifestField inputsField = field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_INPUTS,
                FinalManifestGeneratedTestFields.GENERATED_TEST_INPUTS);
        emitter.field(inputsField, ManifestGeneratedWriterValues.strings(
                inputsField, step.inputs(), value -> value.value()));
        emitter.field(field(lane,
                FinalManifestGeneratedMainFields.GENERATED_MAIN_OUTPUT,
                FinalManifestGeneratedTestFields.GENERATED_TEST_OUTPUT),
                string(step.output().value()));
    }

    private static void writeBehavior(
            ManifestTomlEmitter emitter, Lane lane, AuthoredGeneratedStep step) {
        step.settings().required().filter(value -> !value).ifPresent(value -> emitter.field(
                field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_REQUIRED,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_REQUIRED),
                ManifestTomlValueEncoder.booleanValue(value)));
        boolean cleanDefault = !(step instanceof AuthoredDeclaredRootStep);
        step.settings().clean().filter(value -> value != cleanDefault).ifPresent(value ->
                emitter.field(field(lane,
                        FinalManifestGeneratedMainFields.GENERATED_MAIN_CLEAN,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_CLEAN),
                        ManifestTomlValueEncoder.booleanValue(value)));
    }

    private static ManifestGeneratedOpenApiOptionsWriter.Fields openApiFields(Lane lane) {
        return new ManifestGeneratedOpenApiOptionsWriter.Fields(
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_GENERATOR,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_GENERATOR),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_LIBRARY,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_LIBRARY),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_API_PACKAGE,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_API_PACKAGE),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_MODEL_PACKAGE,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_MODEL_PACKAGE),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_INVOKER_PACKAGE,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_INVOKER_PACKAGE),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_CONFIG,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_CONFIG),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_TEMPLATE_DIR,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_TEMPLATE_DIR),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_VALIDATE_SPEC,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_VALIDATE_SPEC),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_OPTIONS,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_OPTIONS),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_ADDITIONAL_PROPERTIES,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_ADDITIONAL_PROPERTIES),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_CONFIG_OPTIONS,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_CONFIG_OPTIONS),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_GLOBAL_PROPERTIES,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_GLOBAL_PROPERTIES),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_TYPE_MAPPINGS,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_TYPE_MAPPINGS),
                field(lane, FinalManifestGeneratedMainFields.GENERATED_MAIN_IMPORT_MAPPINGS,
                        FinalManifestGeneratedTestFields.GENERATED_TEST_IMPORT_MAPPINGS));
    }

    private static ManifestField field(
            Lane lane, ManifestField main, ManifestField test) {
        return lane.main() ? main : test;
    }

    private static boolean isDerivedOutput(
            Lane lane,
            LocalId id,
            ManifestRelativePath outputRoot,
            ManifestRelativePath output) {
        String derived = outputRoot.value() + "/" + lane.outputDirectory() + "/" + id.value();
        return output.value().equals(derived);
    }

    private static String string(String value) {
        return ManifestGeneratedWriterValues.string(value);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }

    private record Lane(ManifestSection section, boolean main, String outputDirectory) {
    }
}
