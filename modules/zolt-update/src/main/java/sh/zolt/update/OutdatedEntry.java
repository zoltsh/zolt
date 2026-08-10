package sh.zolt.update;

import java.util.List;
import java.util.Optional;

/**
 * One reportable row: a single zolt.toml surface, its current version, the discovered update
 * targets, and advisory context. {@code identifier} is the alias name for {@link
 * OutdatedSurface#VERSION_ALIAS} and {@code group:artifact} otherwise. {@code governs} lists the
 * coordinates a version alias governs (empty for other surfaces). {@code members} lists the
 * workspace members that share this surface (empty outside a workspace).
 */
public record OutdatedEntry(
        UpdateTarget target,
        OutdatedStatus status,
        OutdatedCandidates candidates,
        Optional<String> sourceRepository,
        List<String> members,
        List<String> notes) {
    public OutdatedEntry {
        target = java.util.Objects.requireNonNull(target, "target");
        status = java.util.Objects.requireNonNull(status, "status");
        candidates = java.util.Objects.requireNonNull(candidates, "candidates");
        sourceRepository = sourceRepository == null ? Optional.empty() : sourceRepository;
        members = members == null ? List.of() : List.copyOf(members);
        notes = notes == null ? List.of() : List.copyOf(notes);
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
                target(surface, identifier, section, currentVersion, governs),
                status,
                candidates,
                sourceRepository,
                members,
                notes);
    }

    public OutdatedSurface surface() {
        return target.surface();
    }

    public String identifier() {
        return target.identifier();
    }

    public String section() {
        return target.section();
    }

    public String currentVersion() {
        return target.currentVersion();
    }

    public List<String> governs() {
        return target.governs();
    }

    OutdatedEntry withMembers(List<String> updatedMembers) {
        return new OutdatedEntry(
                target,
                status,
                candidates,
                sourceRepository,
                updatedMembers,
                notes);
    }

    private static UpdateTarget target(
            OutdatedSurface surface,
            String identifier,
            String section,
            String currentVersion,
            List<String> governs) {
        boolean updateable = UpdateApplicability.isApplicable(surface);
        Optional<String> blocker = updateable
                ? Optional.empty()
                : Optional.of(UpdateApplicability.reason(surface));
        return new UpdateTarget(
                UpdateTargetId.create("zolt.toml", surface, section, identifier),
                "zolt.toml",
                "zolt.lock",
                surface,
                identifier,
                section,
                currentVersion,
                updateable,
                blocker,
                governs);
    }
}
