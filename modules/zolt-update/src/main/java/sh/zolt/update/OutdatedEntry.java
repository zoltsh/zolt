package sh.zolt.update;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One reportable dependency-version surface and its advisory discovery result. */
public final class OutdatedEntry {
    private final UpdateTargetReference reference;
    private final String lockfilePath;
    private final String currentVersion;
    private final boolean updateable;
    private final Optional<String> updateBlocker;
    private final OutdatedStatus status;
    private final OutdatedCandidates candidates;
    private final Optional<String> sourceRepository;
    private final List<String> members;
    private final List<String> notes;

    public OutdatedEntry(
            UpdateTarget target,
            OutdatedStatus status,
            OutdatedCandidates candidates,
            Optional<String> sourceRepository,
            List<String> members,
            List<String> notes) {
        this(
                new UpdateTargetReference(target.key(), target.governs()),
                target.lockfilePath(),
                target.currentVersion(),
                target.updateable(),
                target.updateBlocker(),
                status,
                candidates,
                sourceRepository,
                members,
                notes);
    }

    OutdatedEntry(
            UpdateTargetCatalog.Entry entry,
            OutdatedStatus status,
            OutdatedCandidates candidates,
            Optional<String> sourceRepository,
            List<String> members,
            List<String> notes) {
        this(
                entry.reference(),
                entry.lockfilePath(),
                entry.request().currentVersion(),
                entry.updateable(),
                entry.updateBlocker(),
                status,
                candidates,
                sourceRepository,
                members,
                notes);
    }

    public OutdatedEntry(
            OutdatedSurface surface,
            String identifier,
            String section,
            String currentVersion,
            OutdatedStatus status,
            OutdatedCandidates candidates,
            Optional<String> sourceRepository,
            List<String> governs,
            List<String> members,
            List<String> notes) {
        this(
                defaultReference(surface, identifier, section, governs),
                "zolt.lock",
                currentVersion,
                UpdateApplicability.isApplicable(surface),
                UpdateApplicability.isApplicable(surface)
                        ? Optional.empty()
                        : Optional.of(UpdateApplicability.reason(surface)),
                status,
                candidates,
                sourceRepository,
                members,
                notes);
    }

    private OutdatedEntry(
            UpdateTargetReference reference,
            String lockfilePath,
            String currentVersion,
            boolean updateable,
            Optional<String> updateBlocker,
            OutdatedStatus status,
            OutdatedCandidates candidates,
            Optional<String> sourceRepository,
            List<String> members,
            List<String> notes) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.lockfilePath = UpdateTargetKey.requirePath(lockfilePath, "lockfile path");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.updateable = updateable;
        this.updateBlocker = updateBlocker == null ? Optional.empty() : updateBlocker;
        this.status = Objects.requireNonNull(status, "status");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.sourceRepository = sourceRepository == null ? Optional.empty() : sourceRepository;
        this.members = members == null ? List.of() : List.copyOf(members);
        this.notes = notes == null ? List.of() : List.copyOf(notes);
        if (updateable == this.updateBlocker.isPresent()) {
            throw new IllegalArgumentException(
                    "An updateable entry cannot have a blocker, and a non-updateable entry must have one.");
        }
    }

    /** Creates the public schema-v2 target lazily, keeping legacy paths and identifiers raw. */
    public UpdateTarget target() {
        UpdateTargetKey key = reference.key();
        return new UpdateTarget(
                UpdateTargetId.create(key.manifestPath(), key.surface(), key.section(), key.identifier()),
                key.manifestPath(),
                lockfilePath,
                key.surface(),
                key.identifier(),
                key.section(),
                currentVersion,
                updateable,
                updateBlocker,
                reference.governs());
    }

    public OutdatedSurface surface() {
        return reference.surface();
    }

    public String identifier() {
        return reference.identifier();
    }

    public String section() {
        return reference.key().section();
    }

    public String currentVersion() {
        return currentVersion;
    }

    public OutdatedStatus status() {
        return status;
    }

    public OutdatedCandidates candidates() {
        return candidates;
    }

    public Optional<String> sourceRepository() {
        return sourceRepository;
    }

    public List<String> governs() {
        return reference.governs();
    }

    public List<String> members() {
        return members;
    }

    public List<String> notes() {
        return notes;
    }

    OutdatedEntry withMembers(List<String> updatedMembers) {
        return new OutdatedEntry(
                reference,
                lockfilePath,
                currentVersion,
                updateable,
                updateBlocker,
                status,
                candidates,
                sourceRepository,
                updatedMembers,
                notes);
    }

    private static UpdateTargetReference defaultReference(
            OutdatedSurface surface,
            String identifier,
            String section,
            List<String> governs) {
        return new UpdateTargetReference(
                new UpdateTargetKey("zolt.toml", surface, section, identifier),
                governs);
    }
}
