package sh.zolt.lockfile;

import java.util.List;
import java.util.Optional;

public record ZoltLockfile(
        int version,
        Optional<String> aliasFingerprint,
        Optional<String> projectResolutionFingerprint,
        List<String> projectResolutionInputFingerprints,
        List<LockPackage> packages,
        List<LockConflict> conflicts,
        List<LockPolicyEffect> policyEffects,
        List<LockMemberGraph> memberGraphs) {
    /**
     * Version 5 adds optional-boundary and conflict-provenance facts to the member-qualified graph
     * introduced in version 4. Version 3 introduced scope-qualified dependency edges, and version 2
     * introduced variant-qualified edges and conflict identities.
     */
    public static final int CURRENT_VERSION = 5;

    public ZoltLockfile(
            int version,
            Optional<String> aliasFingerprint,
            Optional<String> projectResolutionFingerprint,
            List<String> projectResolutionInputFingerprints,
            List<LockPackage> packages,
            List<LockConflict> conflicts,
            List<LockPolicyEffect> policyEffects) {
        this(
                version,
                aliasFingerprint,
                projectResolutionFingerprint,
                projectResolutionInputFingerprints,
                packages,
                conflicts,
                policyEffects,
                List.of());
    }

    public ZoltLockfile(
            int version,
            List<LockPackage> packages,
            List<LockConflict> conflicts) {
        this(version, Optional.empty(), Optional.empty(), List.of(), packages, conflicts, List.of(), List.of());
    }

    public ZoltLockfile(
            int version,
            List<LockPackage> packages,
            List<LockConflict> conflicts,
            List<LockPolicyEffect> policyEffects) {
        this(version, Optional.empty(), Optional.empty(), List.of(), packages, conflicts, policyEffects, List.of());
    }

    public ZoltLockfile(
            int version,
            Optional<String> aliasFingerprint,
            List<LockPackage> packages,
            List<LockConflict> conflicts,
            List<LockPolicyEffect> policyEffects) {
        this(version, aliasFingerprint, Optional.empty(), List.of(), packages, conflicts, policyEffects, List.of());
    }

    public ZoltLockfile(
            int version,
            Optional<String> aliasFingerprint,
            Optional<String> projectResolutionFingerprint,
            List<LockPackage> packages,
            List<LockConflict> conflicts,
            List<LockPolicyEffect> policyEffects) {
        this(
                version,
                aliasFingerprint,
                projectResolutionFingerprint,
                List.of(),
                packages,
                conflicts,
                policyEffects,
                List.of());
    }

    public ZoltLockfile {
        aliasFingerprint = aliasFingerprint == null ? Optional.empty() : aliasFingerprint;
        projectResolutionFingerprint = projectResolutionFingerprint == null
                ? Optional.empty()
                : projectResolutionFingerprint;
        projectResolutionInputFingerprints = projectResolutionInputFingerprints == null
                ? List.of()
                : List.copyOf(projectResolutionInputFingerprints);
        packages = List.copyOf(packages);
        conflicts = List.copyOf(conflicts);
        policyEffects = policyEffects == null ? List.of() : List.copyOf(policyEffects);
        memberGraphs = memberGraphs == null ? List.of() : List.copyOf(memberGraphs);
        new LockMemberGraphIndex(memberGraphs, packages);
    }
}
