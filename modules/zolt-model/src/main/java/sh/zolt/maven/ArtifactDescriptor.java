package sh.zolt.maven;

import java.util.Optional;

public record ArtifactDescriptor(
        Coordinate coordinate,
        Optional<String> classifier,
        String extension) {
    public ArtifactDescriptor {
        if (coordinate == null) {
            throw new CoordinateParseException("Artifact descriptor requires a coordinate.");
        }
        classifier = classifier == null ? Optional.empty() : classifier;
        if (classifier.isPresent() && classifier.orElseThrow().isBlank()) {
            throw new CoordinateParseException("Artifact classifier must not be blank.");
        }
        classifier = classifier.map(MavenRepositoryValue::classifier);
        if (extension == null || extension.isBlank()) {
            throw new CoordinateParseException("Artifact extension is required.");
        }
        extension = MavenRepositoryValue.extension(extension);
    }

    public static ArtifactDescriptor jar(Coordinate coordinate) {
        return new ArtifactDescriptor(coordinate, Optional.empty(), "jar");
    }

    public static ArtifactDescriptor jar(Coordinate coordinate, Optional<String> classifier) {
        return new ArtifactDescriptor(coordinate, classifier, "jar");
    }
}
