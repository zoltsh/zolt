package sh.zolt.resolve.materialization.session;

import sh.zolt.maven.repository.RepositoryAccess;
import sh.zolt.maven.repository.RepositoryArtifact;
import sh.zolt.maven.repository.RepositoryMissingArtifactException;
import sh.zolt.resolve.ResolveException;
import java.util.List;

final class RepositoryFetchCoordinator {
    RepositoryArtifact fetch(List<RepositoryAccess> repositories, RepositoryFetchAction action) {
        RepositoryMissingArtifactException lastMissing = null;
        for (RepositoryAccess repository : repositories) {
            try {
                return action.fetch(repository).withRepositoryId(repository.id());
            } catch (RepositoryMissingArtifactException exception) {
                lastMissing = exception;
            }
        }
        if (lastMissing != null) {
            throw ResolveException.actionable(
                    lastMissing.getMessage(),
                    "Check the group, artifact, version, and repository URL, then run `zolt resolve` again.",
                    lastMissing);
        }
        throw ResolveException.actionable(
                "No repositories are configured in zolt.toml.",
                "Add [repositories] with at least one Maven-compatible repository URL.");
    }
}
