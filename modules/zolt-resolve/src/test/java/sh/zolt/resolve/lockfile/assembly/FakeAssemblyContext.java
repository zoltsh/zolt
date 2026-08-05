package sh.zolt.resolve.lockfile.assembly;

import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.materialization.MaterializedArtifact;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared in-memory {@link LockfileAssemblyContext} for assembler tests: fabricates poms and jars on demand. */
final class FakeAssemblyContext implements LockfileAssemblyContext {
    final Map<PackageId, ManagedVersion> managedVersions = new LinkedHashMap<>();
    final List<String> digestedPaths = new ArrayList<>();
    private final ProjectConfig config;
    long lockfileAssemblyNanos;

    FakeAssemblyContext(ProjectConfig config) {
        this.config = config;
    }

    @Override
    public ProjectConfig config() {
        return config;
    }

    @Override
    public Map<ArtifactDescriptor, MaterializedArtifact> getArtifacts(List<ArtifactDescriptor> descriptors) {
        Map<ArtifactDescriptor, MaterializedArtifact> artifacts = new LinkedHashMap<>();
        for (ArtifactDescriptor descriptor : descriptors) {
            artifacts.put(descriptor, artifact(descriptor));
        }
        return artifacts;
    }

    @Override
    public MaterializedArtifact getPomArtifact(Coordinate coordinate) {
        return describe(
                repositoryPath(coordinate, Optional.empty(), "pom"),
                bytes("pom:" + coordinate));
    }

    @Override
    public String sourceFor(MaterializedArtifact artifact) {
        return "repo";
    }

    @Override
    public Map<PackageId, ManagedVersion> projectManagedVersionDetails() {
        return managedVersions;
    }

    @Override
    public void addLockfileAssemblyNanos(long nanos) {
        lockfileAssemblyNanos += nanos;
    }

    private MaterializedArtifact artifact(ArtifactDescriptor descriptor) {
        return describe(
                repositoryPath(descriptor.coordinate(), descriptor.classifier(), descriptor.extension()),
                bytes("artifact:" + descriptor));
    }

    private MaterializedArtifact describe(String repositoryPath, byte[] content) {
        digestedPaths.add(repositoryPath);
        return new MaterializedArtifact(repositoryPath, HexFormat.of().formatHex(sha256().digest(content)));
    }

    private static String repositoryPath(Coordinate coordinate, Optional<String> classifier, String extension) {
        String base = coordinate.groupId().replace('.', '/')
                + "/"
                + coordinate.artifactId()
                + "/"
                + coordinate.version().orElseThrow()
                + "/"
                + coordinate.artifactId()
                + "-"
                + coordinate.version().orElseThrow();
        return classifier.map(value -> base + "-" + value).orElse(base) + "." + extension;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
