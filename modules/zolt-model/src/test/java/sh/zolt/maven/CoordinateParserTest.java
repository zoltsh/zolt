package sh.zolt.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CoordinateParserTest {
    private final CoordinateParser parser = new CoordinateParser();

    @Test
    void parsesGroupAndArtifact() {
        Coordinate coordinate = parser.parse("org.slf4j:slf4j-api");

        assertEquals("org.slf4j", coordinate.groupId());
        assertEquals("slf4j-api", coordinate.artifactId());
        assertFalse(coordinate.version().isPresent());
        assertEquals("org.slf4j:slf4j-api", coordinate.packageId());
    }

    @Test
    void parsesGroupArtifactAndVersion() {
        Coordinate coordinate = parser.parse("com.google.guava:guava:33.4.0-jre");

        assertEquals("com.google.guava", coordinate.groupId());
        assertEquals("guava", coordinate.artifactId());
        assertEquals("33.4.0-jre", coordinate.version().orElseThrow());
        assertEquals("com.google.guava:guava:33.4.0-jre", coordinate.toString());
    }

    @Test
    void parsesCommonRealWorldCoordinates() {
        assertEquals("org.junit.jupiter:junit-jupiter:5.11.4", parser.parse("org.junit.jupiter:junit-jupiter:5.11.4").toString());
        assertEquals("ch.qos.logback:logback-classic:1.5.16", parser.parse("ch.qos.logback:logback-classic:1.5.16").toString());
        assertEquals("com.fasterxml.jackson.core:jackson-databind:2.18.2", parser.parse("com.fasterxml.jackson.core:jackson-databind:2.18.2").toString());
    }

    @Test
    void rejectsBlankCoordinate() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse(" "));

        assertEquals(
                "Dependency coordinate is required. Use `group:artifact` or `group:artifact:version`.",
                exception.getMessage());
    }

    @Test
    void rejectsExtraSegments() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse("com.example:demo:1.0.0:sources"));

        assertEquals(
                "Malformed dependency coordinate `com.example:demo:1.0.0:sources`. Use `group:artifact` or `group:artifact:version`.",
                exception.getMessage());
    }

    @Test
    void rejectsWhitespace() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse("com.example: demo:1.0.0"));

        assertEquals(
                "Dependency coordinate must not contain whitespace. Use `group:artifact:version`.",
                exception.getMessage());
    }

    @Test
    void rejectsEmptyGroup() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse(":demo:1.0.0"));

        assertEquals("Malformed dependency coordinate `:demo:1.0.0`. The group segment is empty.", exception.getMessage());
    }

    @Test
    void rejectsEmptyArtifact() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse("com.example::1.0.0"));

        assertEquals("Malformed dependency coordinate `com.example::1.0.0`. The artifact segment is empty.", exception.getMessage());
    }

    @Test
    void rejectsEmptyVersion() {
        CoordinateParseException exception = assertThrows(
                CoordinateParseException.class,
                () -> parser.parse("com.example:demo:"));

        assertEquals("Malformed dependency coordinate `com.example:demo:`. The version segment is empty.", exception.getMessage());
    }

    @Test
    void rejectsEveryRepositoryPathEscapeForm() {
        List<String> unsafe = List.of(
                "//metadata:probe",
                "/absolute/path:probe",
                "../../outside:probe",
                "com.example:../outside",
                "com.example:artifact/../../outside",
                "com.example:artifact?query",
                "com.example:artifact#fragment",
                "com.example:artifact\\path");

        for (String coordinate : unsafe) {
            assertThrows(CoordinateParseException.class, () -> parser.parse(coordinate), coordinate);
        }
    }

    @Test
    void directCoordinateConstructionEnforcesTheSameRepositorySafetyContract() {
        assertThrows(
                CoordinateParseException.class,
                () -> new Coordinate("com.example", "../outside", Optional.of("1.0.0")));
        assertThrows(
                CoordinateParseException.class,
                () -> new Coordinate("com.example", "demo", Optional.of("../../outside")));
        assertThrows(
                CoordinateParseException.class,
                () -> new Coordinate("com.example", "bad\ud800", Optional.of("1.0.0")));
    }

    @Test
    void acceptsDecomposedUnicodeArtifactIdentifiers() {
        String decomposed = "cafe\u0301";

        assertEquals(decomposed, parser.parse("com.example:" + decomposed).artifactId());
    }
}
