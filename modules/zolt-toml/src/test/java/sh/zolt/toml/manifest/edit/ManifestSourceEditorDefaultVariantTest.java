package sh.zolt.toml.manifest.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.ZoltManifestParser;
import org.junit.jupiter.api.Test;

/**
 * The canonical writer omits an authored {@code type = "jar"} because design §9.7 makes it the
 * default variant. The source-preserving editor has to agree: while it demanded the request back
 * verbatim, every mutation that rewrote such a declaration failed closed on a difference in spelling
 * alone, so {@code zolt dependency add} and {@code zolt update} could not touch the dependency at all.
 */
final class ManifestSourceEditorDefaultVariantTest {
    @Test
    void rewritesADeclarationCarryingTheRedundantDefaultTypeInsteadOfWedging() {
        ZoltManifestDocument original = new ZoltManifestParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                # keep this comment
                [dependencies]
                "com.example:kept" = "9.9.9"
                "com.example:lib" = { version = "1.2.3", type = "jar" }
                """);

        ZoltManifestDocument edited = new ManifestSourceEditor().edit(original, updated(original, "1.3.0"));

        assertTrue(edited.source().contains("\"com.example:lib\" = \"1.3.0\""), edited::source);
        assertFalse(edited.source().contains("type = \"jar\""), edited::source);
        assertTrue(edited.source().contains("# keep this comment"), edited::source);
        assertTrue(edited.source().contains("\"com.example:kept\" = \"9.9.9\""), edited::source);
        assertEquals(
                new DependencySelector.FixedVersion("1.3.0"),
                declaration(edited.authored()).selector());
    }

    private static AuthoredManifest updated(ZoltManifestDocument original, String version) {
        return AuthoredManifestMutator.setDependency(
                original.authored(),
                new AuthoredDependency(
                        DependencyLane.IMPLEMENTATION,
                        new DependencyCoordinate("com.example:lib"),
                        new DependencySelector.FixedVersion(version),
                        declaration(original.authored()).metadata()));
    }

    private static AuthoredDependency declaration(AuthoredManifest manifest) {
        return manifest.dependencies().orElseThrow().inLane(DependencyLane.IMPLEMENTATION).stream()
                .filter(dependency -> dependency.coordinate().value().equals("com.example:lib"))
                .findFirst()
                .orElseThrow();
    }
}
