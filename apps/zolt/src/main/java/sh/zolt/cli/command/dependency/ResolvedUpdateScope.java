package sh.zolt.cli.command.dependency;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryConfiguration;
import sh.zolt.update.AliasReferenceScope;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One catalogable manifest plus its canonical paths under the selected mutation root.
 *
 * <p>{@code manifest} is the authored source of every target in this scope, so a workspace-root
 * named value belongs to the root scope and never also to a member (design §4.5). {@code discovery}
 * is the repository universe its candidate listings are read through, and {@code memberConfig} is
 * present only for a workspace member, whose effective view can change when the root changes.
 */
record ResolvedUpdateScope(
        Path mutationRoot,
        Path projectDirectory,
        String label,
        String manifestPath,
        String lockfilePath,
        AuthoredManifest manifest,
        RepositoryConfiguration discovery,
        Optional<ProjectConfig> memberConfig,
        Optional<ZoltLockfile> lockfile,
        List<AliasReferenceScope> aliasReferenceScopes,
        boolean workspaceRoot) {
    ResolvedUpdateScope {
        mutationRoot = normalize(mutationRoot, "mutationRoot");
        projectDirectory = normalize(projectDirectory, "projectDirectory");
        label = Objects.requireNonNull(label, "label");
        manifestPath = Objects.requireNonNull(manifestPath, "manifestPath");
        lockfilePath = Objects.requireNonNull(lockfilePath, "lockfilePath");
        manifest = Objects.requireNonNull(manifest, "manifest");
        discovery = Objects.requireNonNull(discovery, "discovery");
        memberConfig = memberConfig == null ? Optional.empty() : memberConfig;
        lockfile = lockfile == null ? Optional.empty() : lockfile;
        aliasReferenceScopes =
                aliasReferenceScopes == null ? List.of() : List.copyOf(aliasReferenceScopes);
    }

    Path absoluteManifestPath() {
        return mutationRoot.resolve(manifestPath).normalize();
    }

    Path absoluteLockfilePath() {
        return mutationRoot.resolve(lockfilePath).normalize();
    }

    ScopeExpectation expectation() {
        return new ScopeExpectation(absoluteManifestPath(), absoluteLockfilePath(), memberConfig);
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
