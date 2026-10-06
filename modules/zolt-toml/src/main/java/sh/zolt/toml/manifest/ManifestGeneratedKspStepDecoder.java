package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredKspStep;

/** Decodes the main-only KSP generated-step surface. */
final class ManifestGeneratedKspStepDecoder {
    private ManifestGeneratedKspStepDecoder() {
    }

    static AuthoredKspStep decode(ManifestGeneratedStepsDecoder.Row row) {
        if (row.fields() != ManifestGeneratedStepFields.MAIN) {
            return ManifestGeneratedStepsDecoder.invalid(
                    row.required(ManifestGeneratedStepFields.Slot.KIND),
                    "KSP generated steps are currently supported only in [generated.main].");
        }
        row.reject(ManifestGeneratedStepFields.Slot.LANGUAGE);
        Optional<LocalId> tool = row.optionalId(ManifestGeneratedStepFields.Slot.TOOL);
        row.reject(
                ManifestGeneratedStepFields.Slot.MAIN_CLASS,
                ManifestGeneratedStepFields.Slot.ARGS,
                ManifestGeneratedStepFields.Slot.INPUT,
                ManifestGeneratedStepFields.Slot.INPUTS);
        Optional<ManifestRelativePath> output =
                row.optionalPath(ManifestGeneratedStepFields.Slot.OUTPUT);
        row.rejectRange(
                ManifestGeneratedStepFields.Slot.PRODUCES,
                ManifestGeneratedStepFields.Slot.VALIDATE_SPEC);
        Map<String, String> options = options(row, tool, output);
        row.rejectRange(
                ManifestGeneratedStepFields.Slot.ADDITIONAL_PROPERTIES,
                ManifestGeneratedStepFields.Slot.TIMEOUT_SECONDS);
        requireTrue(row, ManifestGeneratedStepFields.Slot.REQUIRED, "must be required");
        requireTrue(
                row,
                ManifestGeneratedStepFields.Slot.CLEAN,
                "must clean its owned output before each non-incremental run");
        GeneratedStepSettings settings = row.settings(Optional.empty());
        return ManifestSemanticDiagnostics.construct(
                row.entry().section(),
                () -> new AuthoredKspStep(settings, tool, output, options));
    }

    private static Map<String, String> options(
            ManifestGeneratedStepsDecoder.Row row,
            Optional<LocalId> tool,
            Optional<ManifestRelativePath> output) {
        return row.field(ManifestGeneratedStepFields.Slot.OPTIONS)
                .map(field -> ManifestSemanticDiagnostics.construct(field, () -> {
                    Map<String, String> values = ManifestTomlValues.stringMap(field);
                    return new AuthoredKspStep(
                                    GeneratedStepSettings.defaultsOmitted(),
                                    tool,
                                    output,
                                    values)
                            .options();
                }))
                .orElseGet(Map::of);
    }

    private static void requireTrue(
            ManifestGeneratedStepsDecoder.Row row,
            ManifestGeneratedStepFields.Slot slot,
            String requirement) {
        row.field(slot).ifPresent(field -> {
            if (!ManifestTomlValues.booleanValue(field)) {
                ManifestGeneratedStepsDecoder.invalid(
                        field, "A KSP generated step " + requirement + ".");
            }
        });
    }
}
