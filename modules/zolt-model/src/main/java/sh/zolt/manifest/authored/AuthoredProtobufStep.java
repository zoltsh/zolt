package sh.zolt.manifest.authored;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;

/** Authored {@code kind = "protobuf"} generated step. */
public record AuthoredProtobufStep(
        GeneratedStepSettings settings,
        Optional<LocalId> tool,
        List<ResourceGlob> inputs,
        Optional<ManifestRelativePath> output,
        Optional<String> javaPackage,
        Optional<Boolean> grpc) implements AuthoredGeneratedStep {
    public AuthoredProtobufStep {
        Objects.requireNonNull(settings, "Protobuf step settings must not be null.");
        tool = Objects.requireNonNull(tool, "Protobuf step tool reference must not be null.");
        inputs = ManifestModelValues.orderedDistinctList(inputs, "Protobuf step inputs");
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("A Protobuf step requires at least one input.");
        }
        output = Objects.requireNonNull(output, "Protobuf step output must not be null.");
        javaPackage = Objects.requireNonNull(
                javaPackage, "Protobuf Java package must not be null.");
        javaPackage.ifPresent(value -> {
            ManifestModelValues.requireNonBlank(value, "Protobuf Java package");
            ManifestModelValues.rejectControlCharacters(value, "Protobuf Java package");
        });
        grpc = Objects.requireNonNull(grpc, "Protobuf gRPC setting must not be null.");
    }
}
