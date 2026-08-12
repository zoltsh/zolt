package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.CoordinateParser;

final class RepositoryCoordinateAndArtifactDescriptorTest {
    private final CoordinateParser parser = new CoordinateParser();
    private final MavenRepositoryPathBuilder paths = new MavenRepositoryPathBuilder();

    @Test
    void parsesCoordinateAndDescriptorForRepositoryArtifactPath() {
        Coordinate coordinate = parser.parse("com.fasterxml.jackson.core:jackson-databind:2.18.2");
        ArtifactDescriptor descriptor = new ArtifactDescriptor(coordinate, Optional.of("sources"), "jar");

        assertEquals("com.fasterxml.jackson.core", coordinate.groupId());
        assertEquals("jackson-databind", coordinate.artifactId());
        assertEquals("2.18.2", coordinate.version().orElseThrow());
        assertEquals(
                "com/fasterxml/jackson/core/jackson-databind/2.18.2/jackson-databind-2.18.2-sources.jar",
                paths.artifactPath(descriptor));
    }

    @Test
    void parsesUnversionedCoordinateForRepositoryPackageId() {
        Coordinate coordinate = parser.parse("org.slf4j:slf4j-api");

        assertEquals("org.slf4j", coordinate.groupId());
        assertEquals("slf4j-api", coordinate.artifactId());
        assertFalse(coordinate.version().isPresent());
        assertEquals("org.slf4j:slf4j-api", coordinate.packageId());
        assertEquals("org/slf4j", paths.groupPath(coordinate));
    }

    @Test
    void rejectsNullCoordinateInputWithActionableMessage() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse(null));

        assertEquals(
                "Dependency coordinate is required. Use `group:artifact` or `group:artifact:version`.",
                exception.getMessage());
    }

    @Test
    void rejectsCoordinateInputWithLeadingOrTrailingWhitespace() {
        CoordinateParseException leading = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse(" org.slf4j:slf4j-api:2.0.16"));
        CoordinateParseException trailing = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse("org.slf4j:slf4j-api:2.0.16 "));

        assertEquals(
                "Dependency coordinate must not contain whitespace. Use `group:artifact:version`.",
                leading.getMessage());
        assertEquals(
                "Dependency coordinate must not contain whitespace. Use `group:artifact:version`.",
                trailing.getMessage());
    }

    @Test
    void rejectsCoordinateInputWithInternalWhitespace() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse("org.slf4j:slf4j api:2.0.16"));

        assertEquals(
                "Dependency coordinate must not contain whitespace. Use `group:artifact:version`.",
                exception.getMessage());
    }

    @Test
    void rejectsMalformedCoordinateSegmentCounts() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse("org.example:demo:1.0.0:sources"));

        assertEquals(
                "Malformed dependency coordinate `org.example:demo:1.0.0:sources`. Use `group:artifact` or `group:artifact:version`.",
                exception.getMessage());
    }

    @Test
    void rejectsEmptyCoordinateSegmentsWithSpecificMessages() {
        assertEquals(
                "Malformed dependency coordinate `:demo:1.0.0`. The group segment is empty.",
                assertThrows(CoordinateParseException.class, () -> parser.parse(":demo:1.0.0"))
                        .getMessage());
        assertEquals(
                "Malformed dependency coordinate `org.example::1.0.0`. The artifact segment is empty.",
                assertThrows(CoordinateParseException.class, () -> parser.parse("org.example::1.0.0"))
                        .getMessage());
        assertEquals(
                "Malformed dependency coordinate `org.example:demo:`. The version segment is empty.",
                assertThrows(CoordinateParseException.class, () -> parser.parse("org.example:demo:"))
                        .getMessage());
    }

    @Test
    void normalizesNullClassifierWhenBuildingRepositoryArtifactPath() {
        Coordinate coordinate = parser.parse("org.example:metadata:1.0.0");
        ArtifactDescriptor descriptor = new ArtifactDescriptor(coordinate, null, "zip");

        assertFalse(descriptor.classifier().isPresent());
        assertEquals(
                "org/example/metadata/1.0.0/metadata-1.0.0.zip",
                paths.artifactPath(descriptor));
    }

    @Test
    void rejectsArtifactDescriptorsThatCannotMapToRepositoryArtifacts() {
        Coordinate coordinate = parser.parse("org.example:metadata:1.0.0");

        assertEquals(
                "Artifact descriptor requires a coordinate.",
                assertThrows(CoordinateParseException.class, () -> ArtifactDescriptor.jar(null))
                        .getMessage());
        assertEquals(
                "Artifact classifier must not be blank.",
                assertThrows(
                                CoordinateParseException.class,
                                () -> ArtifactDescriptor.jar(coordinate, Optional.of("\t")))
                        .getMessage());
        assertEquals(
                "Artifact extension is required.",
                assertThrows(
                                CoordinateParseException.class,
                                () -> new ArtifactDescriptor(coordinate, Optional.empty(), null))
                        .getMessage());
    }

    @Test
    void rejectsUnsafeClassifierAndExtensionPathMaterial() {
        Coordinate coordinate = parser.parse("org.example:metadata:1.0.0");

        assertThrows(
                CoordinateParseException.class,
                () -> new ArtifactDescriptor(coordinate, Optional.of("../sources"), "jar"));
        assertThrows(
                CoordinateParseException.class,
                () -> new ArtifactDescriptor(coordinate, Optional.empty(), "jar?download"));
    }
}
