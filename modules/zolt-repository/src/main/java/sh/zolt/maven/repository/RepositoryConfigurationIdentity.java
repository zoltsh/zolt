package sh.zolt.maven.repository;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Stable identity of the repository configuration a resolved value was derived under.
 *
 * <p>Two configurations with the same identity query the same repositories with the same credential
 * references, so a POM, a parent chain, an imported BOM, or a materialized artifact derived under one
 * is the value the other would derive. Anything cached across projects — workspace members, most
 * obviously — must be keyed by this.
 *
 * <p>Only repository ids, URLs, and the <em>names</em> of the credential environment variables enter
 * this in-process derivation identity, never a secret value. Persistent artifact caching combines it
 * with keyed resolved-credential context through {@code RepositoryCacheScopeResolver}; this value
 * alone is deliberately insufficient for authenticated cross-process cache reuse.
 *
 * <p>The lines are sorted by id rather than emitted in the lookup order {@link RepositoryAccessPlanner}
 * queries, because this is the canonical identity of a repository <em>set</em>: design §8.7 gives one
 * workspace exactly one root-owned universe and order, so no two configurations sharing this identity
 * within a resolution can differ in the precedence they apply.
 */
public final class RepositoryConfigurationIdentity {
    private RepositoryConfigurationIdentity() {
    }

    /** The identity of {@code config}'s repositories, as a value safe to use as a cache key. */
    public static String of(ProjectConfig config) {
        List<String> lines = new ArrayList<>();
        config.repositorySettings().values().stream()
                .sorted(Comparator.comparing(RepositorySettings::id))
                .forEach(repository -> lines.add(String.join(
                        "\t",
                        "repository",
                        repository.id(),
                        repository.url(),
                        repository.credentials().orElse(""))));
        config.repositoryCredentials().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lines.add(String.join(
                        "\t",
                        "credential",
                        entry.getKey(),
                        environmentReferences(entry.getValue()))));
        return String.join("\n", lines);
    }

    private static String environmentReferences(RepositoryCredentialSettings credential) {
        if (credential.usesToken()) {
            return "token\t" + credential.tokenEnv().orElse("");
        }
        return "basic\t"
                + credential.usernameEnv().orElse("")
                + "\t"
                + credential.passwordEnv().orElse("");
    }
}
