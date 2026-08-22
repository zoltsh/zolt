package sh.zolt.resolve.fingerprint;

import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProtobufGenerationSettings;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The explicit, versioned encoding of a {@link GeneratedSourceStep} as lock identity.
 *
 * <p>Generated sources decide which tools resolve into a build, so they belong in the resolution
 * fingerprint. Schema v1 spelled them with {@code GeneratedSourceStep.toString()}, which froze a
 * diagnostic rendering into every checked-in lock: renaming one component of any nested settings
 * record, or adding one whose default rendering differed, silently restated every lock in every
 * repository. This encoder names each field instead, so a {@code toString} stays free to improve and
 * only a deliberate {@link #ENCODING} bump restates a lock.
 *
 * <p>Each line is {@code <category> <step id> <field> [index or key] <value>}. A nested settings
 * block is emitted only when it differs from its empty value, because settings for a kind the step
 * does not use contribute nothing to resolution. Inside a block, absent optionals and empty
 * collections are omitted while booleans and numbers always appear, so no populated field is
 * unrepresented and no unused one adds noise. Field names match their record component names exactly;
 * {@code GeneratedSourceFingerprintTest} holds that correspondence by reflection so a component added
 * later cannot be silently dropped.
 */
final class GeneratedSourceFingerprint {
    /** The encoder version, pinned in every lock so this encoding can evolve deliberately. */
    static final String ENCODING = "v1";

    private GeneratedSourceFingerprint() {
    }

    /** Appends the encoding of {@code step} under {@code category} to {@code inputs}. */
    static void encode(List<String> inputs, String category, GeneratedSourceStep step) {
        Encoder encoder = new Encoder(inputs, category, step.id());
        encoder.emit("kind", step.kind().configValue());
        encoder.emit("language", step.language());
        encoder.emit("output", step.output());
        encoder.list("inputs", step.inputs());
        encoder.flag("required", step.required());
        encoder.flag("clean", step.clean());
        openApi(encoder, step.openApi());
        protobuf(encoder, step.protobuf());
        exec(encoder, step.exec());
    }

    private static void openApi(Encoder encoder, OpenApiGenerationSettings openApi) {
        if (openApi.equals(OpenApiGenerationSettings.empty())) {
            return;
        }
        Encoder scoped = encoder.scope("openApi");
        scoped.optional("toolCoordinate", openApi.toolCoordinate());
        scoped.optional("toolVersion", openApi.toolVersion());
        scoped.optional("toolVersionRef", openApi.toolVersionRef());
        scoped.optional("preset", openApi.preset());
        scoped.optional("generator", openApi.generator());
        scoped.optional("library", openApi.library());
        scoped.optional("apiPackage", openApi.apiPackage());
        scoped.optional("modelPackage", openApi.modelPackage());
        scoped.optional("invokerPackage", openApi.invokerPackage());
        scoped.optional("config", openApi.config());
        scoped.optional("templateDir", openApi.templateDir());
        openApi.validateSpec().ifPresent(value -> scoped.flag("validateSpec", value));
        scoped.map("options", openApi.options());
        scoped.map("additionalProperties", openApi.additionalProperties());
        scoped.map("configOptions", openApi.configOptions());
        scoped.map("globalProperties", openApi.globalProperties());
        scoped.map("typeMappings", openApi.typeMappings());
        scoped.map("importMappings", openApi.importMappings());
    }

    private static void protobuf(Encoder encoder, ProtobufGenerationSettings protobuf) {
        if (protobuf.equals(ProtobufGenerationSettings.empty())) {
            return;
        }
        Encoder scoped = encoder.scope("protobuf");
        scoped.optional("protocCoordinate", protobuf.protocCoordinate());
        scoped.optional("protocVersion", protobuf.protocVersion());
        scoped.optional("protocVersionRef", protobuf.protocVersionRef());
        scoped.optional("grpcPluginCoordinate", protobuf.grpcPluginCoordinate());
        scoped.optional("grpcPluginVersion", protobuf.grpcPluginVersion());
        scoped.optional("grpcPluginVersionRef", protobuf.grpcPluginVersionRef());
        scoped.optional("javaPackage", protobuf.javaPackage());
        scoped.flag("grpc", protobuf.grpc());
    }

    private static void exec(Encoder encoder, ExecGenerationSettings exec) {
        if (exec.equals(ExecGenerationSettings.empty())) {
            return;
        }
        Encoder scoped = encoder.scope("exec");
        scoped.text("toolName", exec.toolName());
        tool(scoped, exec.tool());
        scoped.list("args", exec.args());
        ProducesLane produces = exec.produces();
        if (produces != null) {
            scoped.emit("produces", produces.configValue());
        }
        scoped.optional("into", exec.into());
        scoped.map("env", exec.env());
        scoped.text("cache", exec.cache());
        scoped.optional("cwd", exec.cwd());
        scoped.map("secretEnv", exec.secretEnv());
        scoped.list("inheritEnv", exec.inheritEnv());
        scoped.emit("timeoutSeconds", Integer.toString(exec.timeoutSeconds()));
        scoped.optional("cacheSalt", exec.cacheSalt());
    }

    private static void tool(Encoder encoder, ExecToolSettings tool) {
        if (tool.equals(ExecToolSettings.empty())) {
            return;
        }
        Encoder scoped = encoder.scope("tool");
        scoped.text("runner", tool.runner());
        List<ExecToolCoordinate> coordinates = tool.coordinates();
        for (int index = 0; index < coordinates.size(); index++) {
            ExecToolCoordinate coordinate = coordinates.get(index);
            String ordinal = Integer.toString(index);
            scoped.emit("coordinates.coordinate", ordinal, coordinate.coordinate());
            coordinate.version().ifPresent(value -> scoped.emit("coordinates.version", ordinal, value));
            coordinate.versionRef().ifPresent(value -> scoped.emit("coordinates.versionRef", ordinal, value));
        }
        scoped.text("mainClass", tool.mainClass());
        scoped.text("binary", tool.binary());
        scoped.list("versionCommand", tool.versionCommand());
        scoped.optional("versionExpect", tool.versionExpect());
        scoped.flag("allowUnpinnedTool", tool.allowUnpinnedTool());
    }

    /** Writes {@code <category> <id> <prefix><field> ...} lines into one shared input list. */
    private record Encoder(List<String> inputs, String category, String id, String prefix) {
        Encoder(List<String> inputs, String category, String id) {
            this(inputs, category, id, "");
        }

        Encoder scope(String name) {
            return new Encoder(inputs, category, id, prefix + name + ".");
        }

        void emit(String field, String... values) {
            inputs.add(category + "\t" + id + "\t" + prefix + field + "\t" + String.join("\t", values));
        }

        void text(String field, String value) {
            if (value != null && !value.isEmpty()) {
                emit(field, value);
            }
        }

        void optional(String field, Optional<String> value) {
            value.ifPresent(present -> emit(field, present));
        }

        void flag(String field, boolean value) {
            emit(field, Boolean.toString(value));
        }

        void list(String field, List<String> values) {
            for (int index = 0; index < values.size(); index++) {
                emit(field, Integer.toString(index), values.get(index));
            }
        }

        void map(String field, Map<String, String> values) {
            values.forEach((key, mapped) -> emit(field, key, mapped));
        }
    }
}
