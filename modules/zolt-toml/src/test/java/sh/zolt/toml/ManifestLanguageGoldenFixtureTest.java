package sh.zolt.toml;

import static java.nio.charset.CodingErrorAction.REPORT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

final class ManifestLanguageGoldenFixtureTest {
    private static final String RESOURCE_ROOT = "/golden/manifest-language";
    private static final List<GoldenFixture> EXPECTED_FIXTURES = List.of(
            new GoldenFixture("root-project-workspace.toml", "## 4.4 Root-project workspace"),
            new GoldenFixture("standalone-application.toml", "## 16.1 Minimal application"),
            new GoldenFixture("library-api-boundary.toml", "## 16.2 Library with an API boundary"),
            new GoldenFixture("virtual-workspace.toml", "## 16.3 Zolt workspace root"),
            new GoldenFixture("spring-boot-service.toml", "## 16.4 Spring Boot service"),
            new GoldenFixture("workspace-member.toml", "## 16.5 Workspace member"),
            new GoldenFixture(
                    "enterprise-repository.toml", "## 16.6 Enterprise repository with Central fallback"),
            new GoldenFixture("central-ready-library.toml", "## 16.7 Central-ready library"),
            new GoldenFixture("workspace-bom-member.toml", "## 16.8 Workspace BOM member"));

    @Test
    void containsExactlyTheCanonicalManifestSet() throws IOException, URISyntaxException {
        try (var paths = Files.list(resourceDirectory())) {
            TreeSet<String> actual = paths
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

            TreeSet<String> expected = EXPECTED_FIXTURES.stream()
                    .map(GoldenFixture::resourceName)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            assertEquals(expected, actual);
        }
    }

    @Test
    void canonicalManifestsUseUtf8WithoutBomLfAndOneTerminalNewline() throws IOException {
        for (GoldenFixture fixture : EXPECTED_FIXTURES) {
            byte[] bytes = resourceBytes(fixture.resourceName());
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(REPORT)
                    .onUnmappableCharacter(REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

            assertFalse(
                    source.startsWith("\uFEFF"), fixture.resourceName() + " must not have a byte-order mark");
            assertFalse(source.contains("\r"), fixture.resourceName() + " must use LF line endings");
            assertTrue(source.endsWith("\n"), fixture.resourceName() + " must end with a newline");
            assertFalse(
                    source.endsWith("\n\n"), fixture.resourceName() + " must end with exactly one newline");
        }
    }

    @Test
    void canonicalManifestsMatchTheirDesignExamplesByteForByte() throws IOException {
        String design = Files.readString(designPath(), StandardCharsets.UTF_8);

        for (GoldenFixture fixture : EXPECTED_FIXTURES) {
            byte[] expected = fencedTomlSource(design, fixture.sectionHeading())
                    .getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expected, resourceBytes(fixture.resourceName()), fixture.resourceName());
        }
    }

    private static Path resourceDirectory() throws URISyntaxException {
        URL resource = ManifestLanguageGoldenFixtureTest.class.getResource(RESOURCE_ROOT);
        assertNotNull(resource, RESOURCE_ROOT);
        return Path.of(resource.toURI());
    }

    private static byte[] resourceBytes(String fixture) throws IOException {
        try (var input = ManifestLanguageGoldenFixtureTest.class.getResourceAsStream(RESOURCE_ROOT + "/" + fixture)) {
            assertNotNull(input, fixture);
            return input.readAllBytes();
        }
    }

    private static Path designPath() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (directory != null) {
            Path candidate = directory.resolve("docs/manifest-language-design.md");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new AssertionError("Could not find docs/manifest-language-design.md from user.dir");
    }

    private static String fencedTomlSource(String design, String sectionHeading) {
        String heading = sectionHeading + "\n";
        int sectionStart = design.indexOf(heading);
        assertTrue(sectionStart >= 0, "Missing design section " + sectionHeading);

        int sectionEnd = design.indexOf("\n## ", sectionStart + heading.length());
        if (sectionEnd < 0) {
            sectionEnd = design.length();
        }

        String fence = "```toml\n";
        int sourceStart = design.indexOf(fence, sectionStart + heading.length());
        assertTrue(sourceStart >= 0 && sourceStart < sectionEnd, "Missing TOML fence in " + sectionHeading);
        sourceStart += fence.length();

        int sourceEnd = design.indexOf("\n```", sourceStart);
        assertTrue(sourceEnd >= 0 && sourceEnd < sectionEnd, "Missing TOML fence end in " + sectionHeading);
        return design.substring(sourceStart, sourceEnd) + "\n";
    }

    private record GoldenFixture(String resourceName, String sectionHeading) {}
}
