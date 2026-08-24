package sh.zolt.update;

import sh.zolt.manifest.authored.AuthoredManifest;
import java.util.Objects;

/** One external authored manifest whose version references may be governed by a root alias. */
public record AliasReferenceScope(String manifestPath, AuthoredManifest manifest) {
    public AliasReferenceScope {
        manifestPath = UpdateTargetKey.requirePath(manifestPath, "alias reference manifest path");
        manifest = Objects.requireNonNull(manifest, "manifest");
    }
}
