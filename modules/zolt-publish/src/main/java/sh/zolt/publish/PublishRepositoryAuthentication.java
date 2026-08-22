package sh.zolt.publish;

import sh.zolt.maven.repository.RepositoryAuthentication;
import sh.zolt.project.RepositoryCredentialSettings;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves the {@link RepositoryAuthentication} for a publish repository from the project's
 * {@code [credentials]} table and the environment. Secrets are read from the named
 * environment variables only, never from config values. Shared by the single-project and workspace
 * publish uploaders so every request to a credentialed repository is authenticated identically.
 */
public final class PublishRepositoryAuthentication {
    private PublishRepositoryAuthentication() {
    }

    public static Optional<RepositoryAuthentication> resolve(
            PublishRepositorySettings repository,
            Map<String, RepositoryCredentialSettings> credentialSettings,
            Function<String, String> environment) {
        if (repository.credentials().isEmpty()) {
            return RepositoryAuthentication.none();
        }
        RepositoryCredentialSettings credential = credentialSettings.get(repository.credentials().orElseThrow());
        if (credential == null) {
            throw new PublishException("missing credential metadata for publish repository `" + repository.id() + "`");
        }
        if (credential.usesToken()) {
            String token = environment.apply(credential.tokenEnv().orElseThrow());
            if (token == null || token.isBlank()) {
                throw new PublishException(
                        "missing credential environment variables for publish repository `" + repository.id() + "`");
            }
            return Optional.of(RepositoryAuthentication.bearer(token));
        }
        String username = environment.apply(credential.usernameEnv().orElseThrow());
        String password = environment.apply(credential.passwordEnv().orElseThrow());
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new PublishException(
                    "missing credential environment variables for publish repository `" + repository.id() + "`");
        }
        return Optional.of(new RepositoryAuthentication(username, password));
    }
}
