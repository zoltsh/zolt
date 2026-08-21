package sh.zolt.update;

import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.project.RepositoryConfiguration;
import java.util.Objects;

/** The authored manifest a policy update mutates plus the repositories it discovers through. */
public record UpdatePlanningScope(
        AuthoredManifest manifest,
        RepositoryConfiguration discovery,
        String manifestPath,
        String lockfilePath) {
    public UpdatePlanningScope {
        manifest = Objects.requireNonNull(manifest, "manifest");
        discovery = Objects.requireNonNull(discovery, "discovery");
        manifestPath = UpdateTargetKey.requirePath(manifestPath, "manifest path");
        lockfilePath = UpdateTargetKey.requirePath(lockfilePath, "lockfile path");
    }

    public static UpdatePlanningScope standalone(
            AuthoredManifest manifest, RepositoryConfiguration discovery) {
        return new UpdatePlanningScope(manifest, discovery, "zolt.toml", "zolt.lock");
    }
}
