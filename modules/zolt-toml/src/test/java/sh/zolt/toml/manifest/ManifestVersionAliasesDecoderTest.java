package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.toml.ZoltConfigException;

final class ManifestVersionAliasesDecoderTest {
    @Test
    void decodesSourceOrderedEntriesIntoTheModelSortedMap() {
        AuthoredVersionAliases aliases = decode("""
                [versions]
                zeta = "2.0.0"
                alpha = "1.0-SNAPSHOT"
                """);

        assertEquals(
                List.of(new LocalId("alpha"), new LocalId("zeta")),
                List.copyOf(aliases.entries().keySet()));
        assertEquals("1.0-SNAPSHOT", aliases.entries().get(new LocalId("alpha")).value());
    }

    @Test
    void anchorsInvalidLiteralVersionsToTheConcreteDynamicField() {
        assertFailure("""
                [versions]
                release = "LATEST"
                """, "Invalid value for `versions.release`: Invalid version alias value `LATEST`");
    }

    @Test
    void leavesAliasIdGrammarAndOneLineShapeToTheSchema() {
        assertFailure("""
                [versions]
                Bad_Id = "1.0.0"
                """, "Invalid dynamic key `Bad_Id` at `versions.Bad_Id`");
        assertFailure(
                "[versions]\nrelease = \"\"\"\n1.0.0\n\"\"\"\n",
                "one physical assignment line");
    }

    private static AuthoredVersionAliases decode(String source) {
        return new ManifestVersionAliasesDecoder()
                .decode(ManifestSemanticTestSupport.index(source))
                .orElseThrow();
    }

    private static void assertFailure(String source, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
