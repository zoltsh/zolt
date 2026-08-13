package sh.zolt.resolve.materialization.session;

import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.maven.repository.RawPomParser;
import sh.zolt.maven.repository.RepositoryConfigurationIdentity;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.materialization.MaterializedArtifact;
import sh.zolt.resolve.ResolveOptions;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One resolution session shared by every project a single command resolves.
 *
 * <p>A workspace resolve runs the ordinary per-project resolution once per member, and every member
 * re-derives the same repository metadata: the same POMs are read out of the cache, parsed, walked up
 * their parent chains, expanded through the same imported BOMs, and digested again. The algorithm is
 * unchanged — each member still resolves and mediates exactly as it did — but the derivations that do
 * not depend on which member asked are now performed once.
 *
 * <p>What may be shared, and under which key:
 *
 * <ul>
 *   <li><b>Artifact materialization</b> — one {@link LocalArtifactCache}, so its download coordinator
 *       is the single flight for an artifact across the whole command rather than once per member.
 *       Repository fetches are keyed by repository-configuration identity plus Maven path. On disk,
 *       scoped indexes retain the selected repository source and point to content-addressed blobs.</li>
 *   <li><b>Raw POMs, effective POMs, parent chains, imported BOMs, POM artifacts</b> — keyed by the
 *       {@link RepositoryConfigurationIdentity} they were derived under, because workspace policy
 *       merging lets a member add a repository the root does not declare, and a different repository
 *       set can serve different bytes for the same coordinate.</li>
 *   <li><b>Artifact digests</b> — keyed by content-addressed cache path alone. Repository scopes that
 *       selected identical bytes may share that immutable blob while retaining separate source
 *       evidence in their indexes.</li>
 * </ul>
 *
 * <p>What is deliberately <em>not</em> shared: the resolution graph, version selection, managed
 * versions, policy enforcement, and metrics. Those depend on the requesting project's configuration,
 * so each project keeps its own {@link RepositorySession} over this session.
 *
 * <p>Everything reachable here is a function of the cache root and of the materialization-affecting
 * options, so both are fixed at construction and asserted on every session that joins; a caller that
 * varied them would be asking one cache to answer two different questions.
 */
public final class WorkspaceResolutionSession {
    private final Path cacheRoot;
    private final ResolveOptions options;
    private final RawPomParser rawPomParser;
    private final LocalArtifactCache cache;
    private final Map<String, String> artifactDigests = new ConcurrentHashMap<>();
    private final Map<String, SharedRepositoryScope> scopes = new ConcurrentHashMap<>();

    public WorkspaceResolutionSession(Path cacheRoot, ResolveOptions options, RawPomParser rawPomParser) {
        this.cacheRoot = cacheRoot.toAbsolutePath().normalize();
        this.options = options;
        this.rawPomParser = rawPomParser;
        this.cache = new LocalArtifactCache(cacheRoot);
    }

    LocalArtifactCache cache() {
        return cache;
    }

    ArtifactMaterializer artifactMaterializer(RepositoryCacheScope scope) {
        return new ArtifactMaterializer(cache, scope, options, new LocalOverlayMaterializer(cache));
    }

    SharedRepositoryScope scopeFor(ProjectConfig config) {
        return scopes.computeIfAbsent(
                RepositoryConfigurationIdentity.of(config),
                ignored -> new SharedRepositoryScope(rawPomParser));
    }

    /** What a lock records about {@code artifact}, digesting its bytes once per cache-relative path. */
    MaterializedArtifact describe(CachedArtifact artifact) {
        return new MaterializedArtifact(
                artifact.repositoryPath(),
                artifactDigests.computeIfAbsent(
                        artifact.repositoryPath(), ignored -> sha256(artifact.bytes())),
                artifact.source());
    }

    /**
     * Rejects a project whose materialization context differs from the one this session was built
     * for. Nothing here is keyed by cache root or by offline and overlay policy, so a mismatch would
     * silently answer with values fetched under a different one.
     */
    public void requireSameMaterializationContext(Path otherCacheRoot, ResolveOptions otherOptions) {
        Path normalized = otherCacheRoot.toAbsolutePath().normalize();
        if (!cacheRoot.equals(normalized)) {
            throw new ResolveException(
                    "Shared resolution session was created for cache root "
                            + cacheRoot
                            + " but a project asked to resolve against "
                            + normalized
                            + ". Resolve each cache root in its own session.");
        }
        if (options.offline() != otherOptions.offline()
                || options.rejectLocalOverlays() != otherOptions.rejectLocalOverlays()
                || !options.repositoryOverlays().equals(otherOptions.repositoryOverlays())) {
            throw new ResolveException(
                    "Shared resolution session was created for a different artifact materialization "
                            + "policy than the project now resolving. Resolve each offline and "
                            + "repository-overlay combination in its own session.");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new ResolveException(
                    "Could not compute artifact checksum because SHA-256 is unavailable.", exception);
        }
    }
}
