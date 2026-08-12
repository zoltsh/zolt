package sh.zolt.project;

import java.util.Map;

/** Repository settings shared by standalone projects and workspace-root policy. */
public interface RepositoryConfiguration {
    Map<String, RepositorySettings> repositorySettings();

    Map<String, RepositoryCredentialSettings> repositoryCredentials();
}
