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
 * <p>Two configurations with the same identity query the same repositories, in the same order, with
 * the same credential references, so a POM, a parent chain, an imported BOM, or a materialized
 * artifact derived under one is the value the other would derive. Anything cached across projects —
 * workspace members, most obviously — must be keyed by this, because workspace policy merging leaves
 * a member free to add a repository the workspace root does not declare.
 *
 * <p>Only repository ids, URLs, and the <em>names</em> of the credential environment variables enter
 * the identity, never a secret value: {@link RepositoryCredentialSettings} stores names alone, and
 * the identity is derived from configuration rather than from the resolved authentication.
 *
 * <p>The ordering mirrors {@link RepositoryAccessPlanner}: repositories sorted by id, because that is
 * the order the planner queries them in and therefore the order that decides which repository serves
 * an artifact available from more than one.
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
