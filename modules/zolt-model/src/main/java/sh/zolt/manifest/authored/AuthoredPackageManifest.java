package sh.zolt.manifest.authored;

import java.util.Map;
import sh.zolt.manifest.ManifestModelValues;

/**
 * One authored {@code [package.manifest]} collection, including an explicitly empty one.
 *
 * <p>Design §12.2 gives attributes JAR manifest spelling and says nothing that would make a blank
 * entry meaningful: a blank name has no JAR spelling, and a blank value stamps a header that carries
 * no information. Both are authoring mistakes, so the entry is omitted rather than blanked.
 */
public record AuthoredPackageManifest(Map<String, String> attributes) {
    public AuthoredPackageManifest {
        attributes = ManifestModelValues.immutableSortedMap(
                attributes,
                ManifestModelValues.CODE_POINT_ORDER,
                "Package manifest attribute name",
                "Package manifest attribute value");
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            ManifestModelValues.requireNonBlank(attribute.getKey(), "Package manifest attribute name");
            ManifestModelValues.requireNonBlank(
                    attribute.getValue(),
                    "Package manifest attribute `" + attribute.getKey() + "` value");
        }
    }
}
