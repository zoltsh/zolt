package sh.zolt.maven.repository;

import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.MavenRepositoryValue;
import java.util.Optional;

public final class MavenRepositoryPathBuilder {
    public String pomPath(Coordinate coordinate) {
        return artifactPath(coordinate, "pom");
    }

    public String jarPath(Coordinate coordinate) {
        return artifactPath(ArtifactDescriptor.jar(coordinate));
    }

    public String artifactPath(ArtifactDescriptor descriptor) {
        Coordinate coordinate = descriptor.coordinate();
        String version = coordinate.version().orElseThrow(() -> new CoordinateParseException(
                "Coordinate `" + coordinate.packageId() + "` needs a version to build a repository path."));
        String base = groupPath(coordinate)
                + "/"
                + coordinate.artifactId()
                + "/"
                + version
                + "/";
        String classifier = descriptor.classifier()
                .map(value -> "-" + value)
                .orElse("");
        return base + coordinate.artifactId() + "-" + version + classifier + "." + descriptor.extension();
    }

    public String groupPath(Coordinate coordinate) {
        return coordinate.groupId().replace('.', '/');
    }

    public String metadataPath(String groupId, String artifactId) {
        return MavenRepositoryValue.groupId(groupId).replace('.', '/')
                + "/"
                + MavenRepositoryValue.artifactId(artifactId)
                + "/maven-metadata.xml";
    }

    private String artifactPath(Coordinate coordinate, String extension) {
        return artifactPath(new ArtifactDescriptor(coordinate, Optional.empty(), extension));
    }
}
