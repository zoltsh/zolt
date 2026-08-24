package sh.zolt.toml;

import static java.nio.charset.CodingErrorAction.REPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

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
    private static final List<String> EXPECTED_FIXTURES = List.of(
            "root-project-workspace.toml",
            "standalone-application.toml",
            "library-api-boundary.toml",
            "virtual-workspace.toml",
            "spring-boot-service.toml",
            "workspace-member.toml",
            "enterprise-repository.toml",
            "central-ready-library.toml",
            "workspace-bom-member.toml");

    @Test
    void containsExactlyTheCanonicalManifestSet() throws IOException, URISyntaxException {
        try (var paths = Files.list(resourceDirectory())) {
            TreeSet<String> actual = paths
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

            TreeSet<String> expected = EXPECTED_FIXTURES.stream()
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            assertEquals(expected, actual);
        }
    }

    @Test
    void canonicalManifestsUseUtf8WithoutBomLfAndOneTerminalNewline() throws IOException {
        for (String fixture : EXPECTED_FIXTURES) {
            byte[] bytes = resourceBytes(fixture);
            String source = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(REPORT)
                    .onUnmappableCharacter(REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();

            assertFalse(
                    source.startsWith("\uFEFF"), fixture + " must not have a byte-order mark");
            assertFalse(source.contains("\r"), fixture + " must use LF line endings");
            assertTrue(source.endsWith("\n"), fixture + " must end with a newline");
            assertFalse(
                    source.endsWith("\n\n"), fixture + " must end with exactly one newline");
        }
    }

    @Test
    void canonicalManifestsPassTheCompleteAuthoredPipeline() throws IOException {
        for (String fixture : EXPECTED_FIXTURES) {
            String source = new String(resourceBytes(fixture), StandardCharsets.UTF_8);
            var authored = decodeAuthoredManifest(source);
            assertTrue(
                    authored.workspace().isPresent() || authored.project().isPresent(),
                    fixture);
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

}
