package sh.zolt.release.update;

import sh.zolt.release.ReleaseTarget;
import java.net.URI;
import java.nio.file.Path;

/**
 * The legacy workDirectory hint is retained for source compatibility; installation attempts always
 * use unique destination-local directories so caller scratch directories are never modified.
 */
public record NativeUpdateRequest(
        Path installRoot,
        Path currentExecutable,
        URI channelUri,
        ReleaseTarget target,
        Path workDirectory) {
}
