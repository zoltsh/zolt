package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.maven.repository.RepositoryArtifactTestSupport.artifactBytes;

import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class MavenRepositoryClientTest extends MavenRepositoryClientTestSupport {
    @Test
    void fetchesKnownPom() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        put("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom", "<project/>");

        RepositoryArtifact artifact = client.fetchPom(baseUri, coordinate);

        assertEquals(coordinate, artifact.coordinate());
        assertEquals("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom", artifact.path());
        assertEquals(
                baseUri.resolve("com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.pom"),
                artifact.source());
        assertArrayEquals("<project/>".getBytes(java.nio.charset.StandardCharsets.UTF_8), artifactBytes(artifact));
    }

    @Test
    void fetchesKnownJar() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        byte[] jarBytes = new byte[] {0x50, 0x4b, 0x03, 0x04};
        responses.put("/maven2/com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar", jarBytes);

        RepositoryArtifact artifact = client.fetchJar(baseUri, coordinate);

        assertArrayEquals(jarBytes, artifactBytes(artifact));
    }

    @Test
    void fetchesKnownJarWithByteProgressWhenContentLengthIsKnown() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");
        ArtifactDescriptor descriptor = ArtifactDescriptor.jar(coordinate);
        byte[] jarBytes = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x14};
        List<ByteEvent> events = new ArrayList<>();
        responses.put("/maven2/com/google/guava/guava/33.4.0-jre/guava-33.4.0-jre.jar", jarBytes);

        RepositoryArtifact artifact = client.fetchJar(
                baseUri,
                coordinate,
                RepositoryAuthentication.none(),
                (artifactDescriptor, received, total) ->
                        events.add(new ByteEvent(artifactDescriptor, received, total)));

        assertArrayEquals(jarBytes, artifactBytes(artifact));
        assertFalse(events.isEmpty(), "known Content-Length should emit byte progress");
        assertEquals(new ByteEvent(descriptor, jarBytes.length, jarBytes.length), events.get(events.size() - 1));
    }

    @Test
    void fetchesClassifierArtifact() {
        Coordinate coordinate = parser.parse("io.quarkus:quarkus-custom-deployment:1.0.0");
        byte[] jarBytes = new byte[] {0x50, 0x4b, 0x03, 0x04};
        responses.put(
                "/maven2/io/quarkus/quarkus-custom-deployment/1.0.0/quarkus-custom-deployment-1.0.0-deployment.jar",
                jarBytes);

        RepositoryArtifact artifact = client.fetchArtifact(
                baseUri,
                ArtifactDescriptor.jar(coordinate, Optional.of("deployment")));

        assertEquals(
                "io/quarkus/quarkus-custom-deployment/1.0.0/quarkus-custom-deployment-1.0.0-deployment.jar",
                artifact.path());
        assertArrayEquals(jarBytes, artifactBytes(artifact));
    }

    private record ByteEvent(ArtifactDescriptor descriptor, long received, long total) {
    }
}
