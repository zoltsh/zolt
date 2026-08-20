package sh.zolt.manifest.authored;

import java.util.Map;
import sh.zolt.manifest.ManifestModelValues;

/** One authored {@code [package.manifest]} collection, including an explicitly empty one. */
public record AuthoredPackageManifest(Map<String, String> attributes) {
    public AuthoredPackageManifest {
        attributes = ManifestModelValues.immutableSortedMap(
                attributes,
                ManifestModelValues.CODE_POINT_ORDER,
                "Package manifest attribute name",
                "Package manifest attribute value");
        for (String name : attributes.keySet()) {
            ManifestModelValues.requireNonBlank(name, "Package manifest attribute name");
        }
    }
}
