package sh.zolt.toml.manifest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.ZoltManifestParser;
import sh.zolt.toml.manifest.write.ManifestCanonicalWriter;

final class ZoltManifestApiTest {
    @Test
    void exposesOneExactAuthoredParseAndCanonicalWriteBoundary() {
        String source = "[project]\nname = \"demo\"\n";

        ZoltManifestDocument document = new ZoltManifestParser().parse(source);

        assertEquals(source, document.source());
        assertEquals("demo", document.authored().project().orElseThrow().identity().name().value());
        assertEquals(source, new ManifestCanonicalWriter().write(document.authored()));
    }

    @Test
    void rejectsSyntaxEvidenceFromAnotherSource() {
        ZoltManifestParser parser = new ZoltManifestParser();
        ZoltManifestDocument first = parser.parse("[project]\nname = \"first\"\n");
        ZoltManifestDocument second = parser.parse("[project]\nname = \"second\"\n");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ZoltManifestDocument(
                        first.source(), second.syntax(), first.authored()));

        assertEquals("Manifest syntax must match the retained source.", failure.getMessage());
    }

    @Test
    void rejectsMissingDocumentComponents() {
        ZoltManifestDocument document = new ZoltManifestParser().parse(
                "[project]\nname = \"demo\"\n");

        assertEquals(
                "Manifest source is required.",
                assertThrows(NullPointerException.class, () -> new ZoltManifestDocument(
                        null, document.syntax(), document.authored())).getMessage());
        assertEquals(
                "Manifest syntax is required.",
                assertThrows(NullPointerException.class, () -> new ZoltManifestDocument(
                        document.source(), null, document.authored())).getMessage());
        assertEquals(
                "Authored manifest is required.",
                assertThrows(NullPointerException.class, () -> new ZoltManifestDocument(
                        document.source(), document.syntax(), null)).getMessage());
    }
}
