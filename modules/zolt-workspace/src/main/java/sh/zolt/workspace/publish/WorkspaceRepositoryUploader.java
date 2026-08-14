package sh.zolt.workspace.publish;

import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.maven.repository.RemoteFileComparison;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 2 for a plain repository: a dependency-ordered sequential upload (provider before consumer,
 * BOM last) of a family already staged in Phase 1. Every byte — artifact, POM, checksum, and detached
 * signature — is pre-generated, so a member cannot fail mid-way on signing or digesting; only the
 * transfer itself can fail.
 *
 * <p>Nothing is trusted blindly. Every path is PROBED against the SHA-256 of the bytes Phase 1 staged:
 * absent → uploaded; present with matching bytes → skipped (so a resumed publish re-PUTs nothing an
 * immutable release repository would reject, and a path that was recorded complete but has since been
 * deleted is re-uploaded); present with DIFFERENT bytes → a hard, non-resumable conflict. The primary
 * object itself is always confirmed, so a surviving checksum sidecar cannot mask a deleted artifact.
 * Any other transfer failure fails fast, persists the target-bound exact-byte transaction manifest,
 * and reports an exact resume command naming the member that failed and those after it.
 */
public final class WorkspaceRepositoryUploader {
    private final MavenRepositoryClient repositoryClient;

    public WorkspaceRepositoryUploader() {
        this(new MavenRepositoryClient());
    }

    public WorkspaceRepositoryUploader(MavenRepositoryClient repositoryClient) {
        this.repositoryClient = repositoryClient;
    }

    /** Uploads with no durable resume state — the idempotency probe alone guards re-PUTs (unit-test seam). */
    WorkspacePublishReport upload(List<StagedMember> members, WorkspacePublishService.Options options) {
        return upload(members, options, Set.of(), null);
    }

    /**
     * Uploads the staged family. {@code completed} seeds the repository paths a prior attempt already
     * landed (providers included) — each is re-verified rather than skipped, and carried forward so an
     * absent already-published sibling stays provably satisfied; {@code statePath}, when non-null,
     * receives the durable transaction manifest on failure and is cleared on success.
     */
    WorkspacePublishReport upload(
            List<StagedMember> members,
            WorkspacePublishService.Options options,
            Set<String> completed,
            Path statePath) {
        List<WorkspacePublishReport.Member> reportMembers = new ArrayList<>();
        for (StagedMember member : members) {
            reportMembers.add(member.reportMember());
        }
        Set<String> landed = new LinkedHashSet<>(completed);
        for (int index = 0; index < members.size(); index++) {
            StagedMember member = members.get(index);
            try {
                uploadMember(member, landed);
            } catch (MismatchedArtifactException conflict) {
                // An occupied release path with different content: resuming cannot fix it, so no resume
                // command — the operator must resolve the conflict (bump the version or clear the path).
                return new WorkspacePublishReport(
                        reportMembers, List.of(conflict.getMessage()), false, Optional.empty(), Optional.empty());
            } catch (RuntimeException | IOException exception) {
                // Resume the failed member and everything after it, selected EXACTLY (never a
                // dependency-expanded, already-uploaded provider), carrying the original semantic options.
                List<StagedMember> resumeSet = members.subList(index, members.size());
                List<String> remaining = new ArrayList<>();
                for (StagedMember pending : resumeSet) {
                    remaining.add(pending.memberPath());
                }
                writeResumeState(statePath, members, resumeSet, remaining, landed, options);
                return new WorkspacePublishReport(
                        reportMembers,
                        List.of("upload failed for " + member.coordinate() + ": " + exception.getMessage()),
                        false,
                        Optional.empty(),
                        Optional.of(options.resumeCommand(remaining)));
            }
        }
        deleteQuietly(statePath);
        return new WorkspacePublishReport(reportMembers, List.of(), true, Optional.empty(), Optional.empty());
    }

    private void uploadMember(StagedMember member, Set<String> landed) throws IOException {
        RepositoryTarget target = member.target();
        for (StagedArtifact artifact : member.artifacts()) {
            if (!verifiedPresent(target, artifact)) {
                uploadArtifact(target, artifact);
            }
            landed.add(artifact.repositoryPath()); // present now, whether verified in place or just uploaded
        }
    }

    /**
     * Whether {@code artifact} already holds its staged bytes at the target. The object itself is
     * always fetched/hashed; its checksum sidecar is never accepted as proof that the primary exists.
     * Absent means upload, while different bytes are a hard conflict.
     */
    private boolean verifiedPresent(RepositoryTarget target, StagedArtifact artifact) throws IOException {
        String path = artifact.repositoryPath();
        if (target.local()) {
            Optional<String> remoteHash = localHash(target, path);
            if (remoteHash.isEmpty()) {
                return false;
            }
            requireSameHash(path, artifact.stagedSha256(), remoteHash.orElseThrow());
            return true;
        }
        RemoteFileComparison comparison = repositoryClient.compareFile(
                target.uri(),
                path,
                target.authentication(),
                Files.size(artifact.source()),
                artifact.stagedSha256());
        return switch (comparison) {
            case ABSENT -> false;
            case MATCHING -> true;
            case DIFFERENT -> throw new MismatchedArtifactException(path);
        };
    }

    /**
     * The SHA-256 of the bytes currently at {@code path}, or empty when the path is absent. A local
     * target is stream-hashed off disk; a remote one is fetched once and hashed. The staged side is a
     * recorded digest, never the original mutable publication source.
     */
    private Optional<String> localHash(RepositoryTarget target, String path) throws IOException {
        Path file = target.directory().resolve(path).normalize();
        return Files.exists(file) ? Optional.of(Sha256.hex(file)) : Optional.empty();
    }

    private void uploadArtifact(RepositoryTarget target, StagedArtifact artifact) throws IOException {
        if (target.local()) {
            Path destination = target.directory().resolve(artifact.repositoryPath()).normalize();
            Files.createDirectories(destination.getParent());
            Files.copy(artifact.source(), destination);
        } else {
            repositoryClient.uploadFile(
                    target.uri(), artifact.repositoryPath(), artifact.source(), target.authentication());
        }
    }

    private static void writeResumeState(
            Path statePath,
            List<StagedMember> allMembers,
            List<StagedMember> resumeSet,
            List<String> members,
            Set<String> landed,
            WorkspacePublishService.Options options) {
        if (statePath == null) {
            return;
        }
        ResumeState.of(allMembers, resumeSet, options, members, landed).write(statePath);
    }

    private static void deleteQuietly(Path statePath) {
        if (statePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(statePath);
        } catch (IOException ignored) {
            // A lingering resume-state file after a successful publish is harmless.
        }
    }

    private static void requireSameHash(String repositoryPath, String stagedSha256, String remoteSha256) {
        if (!stagedSha256.equalsIgnoreCase(remoteSha256.trim())) {
            throw new MismatchedArtifactException(repositoryPath);
        }
    }

    /** An existing release path already holds different bytes than this publish staged. */
    private static final class MismatchedArtifactException extends RuntimeException {
        MismatchedArtifactException(String repositoryPath) {
            super("release path `" + repositoryPath + "` already holds different content than this publish would "
                    + "upload; a release repository will not overwrite it. Next: bump the version or remove the "
                    + "stale artifact, then re-publish.");
        }
    }
}
