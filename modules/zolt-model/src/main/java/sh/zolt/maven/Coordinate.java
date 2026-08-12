package sh.zolt.maven;

import java.util.Optional;

public record Coordinate(String groupId, String artifactId, Optional<String> version) {
    public Coordinate {
        groupId = MavenRepositoryValue.groupId(groupId);
        artifactId = MavenRepositoryValue.artifactId(artifactId);
        version = version == null ? Optional.empty() : version;
        version = version.map(MavenRepositoryValue::version);
    }

    public String packageId() {
        return groupId + ":" + artifactId;
    }

    @Override
    public String toString() {
        return version.map(value -> packageId() + ":" + value).orElseGet(this::packageId);
    }
}
