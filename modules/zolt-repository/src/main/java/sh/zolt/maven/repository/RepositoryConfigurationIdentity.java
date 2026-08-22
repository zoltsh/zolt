package sh.zolt.maven.repository;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import java.util.ArrayList;
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
 * <p>Repositories are emitted in the effective lookup order {@link RepositoryAccessPlanner} queries,
 * each carrying its ordinal, because that order is a resolution input rather than presentation.
 * Fetching is first-match-wins (design §8.5), so two configurations that declare the same
 * repositories in opposite order can select different bytes for the same coordinate. Identifying them
 * as one repository <em>set</em> would let the first project's choice answer for the second out of a
 * shared persistent cache scope — a supply-chain correctness failure, not a cache-efficiency one.
 *
 * <p>This value keys caches only. It is deliberately absent from lock bytes: the lock's own
 * {@code repositories} fingerprint category is a separate, id-sorted projection, so re-keying a cache
 * directory here never restates a checked-in lock.
 */
public final class RepositoryConfigurationIdentity {
    private RepositoryConfigurationIdentity() {
    }

    /** The identity of {@code config}'s repositories, as a value safe to use as a cache key. */
    public static String of(ProjectConfig config) {
        List<String> lines = new ArrayList<>();
        int ordinal = 0;
        for (RepositorySettings repository : config.repositorySettings().values()) {
            lines.add(String.join(
                    "\t",
                    "repository",
                    Integer.toString(ordinal++),
                    repository.id(),
                    repository.url(),
                    repository.credentials().orElse("")));
        }
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
