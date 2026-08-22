package sh.zolt.explain.emit;

import sh.zolt.manifest.authored.AuthoredManifest;

/**
 * Renders one authored manifest into canonical zolt.toml text.
 *
 * <p>zolt-explain does not depend on zolt-toml. The CLI injects the real canonical writer
 * ({@code ManifestCanonicalWriter.write(AuthoredManifest)}) through this indirection so the
 * dependency direction stays explicit, mirroring the writer seam {@code ProjectInitializer} uses.
 *
 * <p>One interface covers both drafted document kinds: in the final language a workspace root and a
 * project are the same {@link AuthoredManifest} shape, distinguished only by which domains are
 * present.
 */
@FunctionalInterface
public interface AuthoredManifestRenderer {
    String render(AuthoredManifest manifest);
}
