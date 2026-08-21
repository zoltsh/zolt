package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ManifestCanonicalWriterTest {
    private static final String ROOT = "/golden/manifest-language/";

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void reproducesFrozenManifestFixtureByteForByte(String fixture) throws IOException {
        String source = resource(fixture);
        ManifestCanonicalWriter writer = new ManifestCanonicalWriter();

        String canonical = writer.write(decodeAuthoredManifest(source));

        assertAll(
                () -> assertArrayEquals(
                        source.getBytes(StandardCharsets.UTF_8),
                        canonical.getBytes(StandardCharsets.UTF_8),
                        fixture),
                () -> assertTrue(canonical.endsWith("\n"), fixture),
                () -> assertFalse(canonical.endsWith("\n\n"), fixture),
                () -> assertEquals(
                        canonical,
                        writer.write(decodeAuthoredManifest(canonical)),
                        fixture + " must be idempotent"));
    }

    @Test
    void rejectsANullAuthoredManifest() {
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> new ManifestCanonicalWriter().write(null));

        assertEquals("Authored manifest is required.", failure.getMessage());
    }

    static Stream<String> fixtures() {
        return Stream.of(
                "root-project-workspace.toml",
                "standalone-application.toml",
                "library-api-boundary.toml",
                "virtual-workspace.toml",
                "spring-boot-service.toml",
                "workspace-member.toml",
                "enterprise-repository.toml",
                "central-ready-library.toml",
                "workspace-bom-member.toml");
    }

    private static String resource(String fixture) throws IOException {
        try (var input = ManifestCanonicalWriterTest.class.getResourceAsStream(ROOT + fixture)) {
            if (input == null) {
                throw new AssertionError("Missing canonical manifest fixture " + fixture);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
