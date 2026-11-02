package sh.zolt.toml.manifest.write;

import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredKspStep;
import sh.zolt.toml.schema.ManifestField;

/** Emits KSP step fields in canonical schema order. */
final class ManifestGeneratedKspStepWriter {
    private static final LocalId KSP = new LocalId("ksp");

    private ManifestGeneratedKspStepWriter() {
    }

    static void write(
            ManifestTomlEmitter emitter,
            LocalId id,
            AuthoredKspStep step,
            ManifestRelativePath outputRoot,
            boolean mainScope,
            ManifestField toolField,
            ManifestField outputField,
            ManifestField optionsField) {
        step.tool().filter(value -> !value.equals(KSP)).ifPresent(value ->
                emitter.field(toolField, ManifestGeneratedWriterValues.string(value.value())));
        step.output()
                .filter(value -> !isDerivedOutput(id, outputRoot, mainScope, value))
                .ifPresent(value -> emitter.field(
                        outputField, ManifestGeneratedWriterValues.string(value.value())));
        if (!step.options().isEmpty()) {
            emitter.field(optionsField, ManifestGeneratedWriterValues.stringMap(step.options()));
        }
    }

    private static boolean isDerivedOutput(
            LocalId id,
            ManifestRelativePath outputRoot,
            boolean mainScope,
            ManifestRelativePath output) {
        return output.value().equals(
                outputRoot.value() + "/generated/ksp/"
                        + (mainScope ? "main/" : "test/") + id.value());
    }
}
