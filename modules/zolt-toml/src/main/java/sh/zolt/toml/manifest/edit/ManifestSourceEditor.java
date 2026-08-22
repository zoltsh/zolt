package sh.zolt.toml.manifest.edit;

import java.util.Objects;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.ZoltManifestParser;
import sh.zolt.toml.manifest.write.ManifestCanonicalWriter;

/** Applies one final-language authored delta without regenerating unrelated source. */
public final class ManifestSourceEditor {
    private final ZoltManifestParser parser = new ZoltManifestParser();
    private final ManifestCanonicalWriter writer = new ManifestCanonicalWriter();

    /**
     * Edits only schema-declared mutable entries and returns reparsed source evidence.
     *
     * <p>The returned document is guaranteed to be semantically equal to {@code requested}. A
     * request that cannot be represented by the source-preserving editor fails before any caller
     * can commit bytes.
     */
    public ZoltManifestDocument edit(
            ZoltManifestDocument original,
            AuthoredManifest requested) {
        Objects.requireNonNull(original, "Original manifest document is required.");
        Objects.requireNonNull(requested, "Requested authored manifest is required.");

        ZoltManifestDocument verified = parser.parse(original.source());
        if (!verified.authored().equals(original.authored())) {
            throw unsafe("the retained authored model does not match the captured source");
        }
        if (verified.authored().equals(requested)) {
            return original;
        }

        ZoltManifestDocument canonicalBefore = canonicalDocument(verified.authored());
        ZoltManifestDocument canonicalAfter = canonicalDocument(requested);
        String patched = new ManifestSourcePatch(
                        verified, canonicalBefore, canonicalAfter)
                .apply();
        ZoltManifestDocument edited = parser.parse(patched);
        if (!edited.authored().equals(requested)) {
            throw unsafe("the source patch does not equal the requested manifest");
        }
        return edited;
    }

    private ZoltManifestDocument canonicalDocument(AuthoredManifest authored) {
        return parser.parse(writer.write(authored));
    }

    private static IllegalStateException unsafe(String reason) {
        return new IllegalStateException(
                "Could not safely edit zolt.toml because " + reason + ". No changes were written.");
    }
}
