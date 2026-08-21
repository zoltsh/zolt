package sh.zolt.update;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.project.RepositoryConfiguration;
import java.util.Objects;
import java.util.Optional;

/**
 * One manifest to report on: a display label, its canonical mutation-root-relative paths, the
 * authored manifest that owns its declarations, and the repository configurations its version
 * listings are discovered through.
 *
 * <p>Surfaces come from the authored manifest, never from a composed effective view, so a
 * workspace-root named value is reported once at the root and never again under every member
 * (design §4.5 named maps, §20.1). A standalone project is one scope; a workspace is one scope per
 * member plus the root when the root manifest declares surfaces of its own.
 *
 * <p>One workspace has exactly one dependency repository universe — a member may not declare
 * {@code [repositories]} (design §8.7) — so every scope in one report discovers candidates through
 * the same repositories.
 */
public record OutdatedScope(
        String label,
        String manifestPath,
        String lockfilePath,
        AuthoredManifest manifest,
        RepositoryConfiguration discovery,
        Optional<ZoltLockfile> lockfile) {
    public OutdatedScope {
        label = Objects.requireNonNull(label, "label");
        manifestPath = UpdateTargetKey.requirePath(manifestPath, "manifest path");
        lockfilePath = UpdateTargetKey.requirePath(lockfilePath, "lockfile path");
        manifest = Objects.requireNonNull(manifest, "manifest");
        discovery = Objects.requireNonNull(discovery, "discovery");
        lockfile = lockfile == null ? Optional.empty() : lockfile;
    }

    public OutdatedScope(
            String label,
            AuthoredManifest manifest,
            RepositoryConfiguration discovery,
            Optional<ZoltLockfile> lockfile) {
        this(label, "zolt.toml", "zolt.lock", manifest, discovery, lockfile);
    }
}
