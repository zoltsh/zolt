package sh.zolt.release.update;

import sh.zolt.release.ReleaseTarget;
import java.net.URI;
import java.nio.file.Path;

/**
 * The legacy workDirectory hint is retained for source compatibility; installation attempts always
 * use unique destination-local directories so caller scratch directories are never modified.
 */
public record NativeVersionInstallRequest(
        Path installRoot,
        Path currentExecutable,
        URI releaseIndexUri,
        String version,
        ReleaseTarget target,
        Path workDirectory) {
}
