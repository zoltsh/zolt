package sh.zolt.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One parser-independent tool declaration from {@code [generated.tools.<id>]}. */
public sealed interface AuthoredGeneratedTool
        permits AuthoredGeneratedTool.OpenApi,
                AuthoredGeneratedTool.Protobuf,
                AuthoredGeneratedTool.Jvm,
                AuthoredGeneratedTool.Process {
    record OpenApi(
            Optional<DependencyCoordinate> coordinate,
            Optional<DependencySelector> version) implements AuthoredGeneratedTool {
        public OpenApi {
            coordinate = Objects.requireNonNull(
                    coordinate, "OpenAPI tool coordinate override must not be null.");
            version = fixedOrReference(version, "OpenAPI tool version override");
        }
    }

    record Protobuf(
            Optional<DependencyCoordinate> protocCoordinate,
            Optional<DependencySelector> protocVersion,
            Optional<DependencyCoordinate> grpcCoordinate,
            Optional<DependencySelector> grpcVersion) implements AuthoredGeneratedTool {
        public Protobuf {
            protocCoordinate = Objects.requireNonNull(
                    protocCoordinate, "Protoc coordinate override must not be null.");
            protocVersion = fixedOrReference(protocVersion, "Protoc version override");
            grpcCoordinate = Objects.requireNonNull(
                    grpcCoordinate, "gRPC coordinate override must not be null.");
            grpcVersion = fixedOrReference(grpcVersion, "gRPC version override");
        }
    }

    record Jvm(List<GeneratedArtifactRequest> coordinates, JavaBinaryClassName mainClass)
            implements AuthoredGeneratedTool {
        public Jvm {
            coordinates = ManifestModelValues.immutableList(
                    coordinates, "Generated JVM tool coordinates");
            if (coordinates.isEmpty()) {
                throw new IllegalArgumentException(
                        "A generated JVM tool requires at least one coordinate.");
            }
            Objects.requireNonNull(mainClass, "Generated JVM tool main class must not be null.");
            ManifestModelValues.rejectDuplicates(
                    coordinates.stream().map(GeneratedArtifactRequest::coordinate).toList(),
                    "Generated JVM tool coordinates");
        }
    }

    record Process(
            GeneratedProcessBinary binary,
            List<String> versionCommand,
            Optional<GeneratedVersionExpectation> versionExpect,
            boolean allowUnpinnedTool) implements AuthoredGeneratedTool {
        public Process {
            Objects.requireNonNull(binary, "Generated process binary must not be null.");
            versionCommand = ManifestModelValues.immutableList(
                    versionCommand, "Generated process version command");
            if (versionCommand.isEmpty()) {
                throw new IllegalArgumentException(
                        "A generated process tool requires a nonempty version command.");
            }
            for (String argument : versionCommand) {
                if (argument.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException(
                            "Generated process version-command arguments must not contain NUL.");
                }
            }
            if (!versionCommand.getFirst().equals(binary.value())) {
                throw new IllegalArgumentException(
                        "Generated process version command must probe its configured binary exactly.");
            }
            versionExpect = Objects.requireNonNull(
                    versionExpect, "Generated process version expectation must not be null.");
            if (!allowUnpinnedTool) {
                throw new IllegalArgumentException(
                        "A generated process tool requires allowUnpinnedTool = true.");
            }
        }
    }

    private static Optional<DependencySelector> fixedOrReference(
            Optional<DependencySelector> selector, String label) {
        Objects.requireNonNull(selector, label + " must not be null.");
        selector.ifPresent(value -> {
            if (!(value instanceof DependencySelector.FixedVersion)
                    && !(value instanceof DependencySelector.VersionReference)) {
                throw new IllegalArgumentException(
                        label + " must be a fixed version or version reference.");
            }
        });
        return selector;
    }
}
