package sh.zolt.workspace.service;

import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The external half of a member's resolution inputs, taken from the lock's own member attribution
 * instead of from a projected classpath.
 *
 * <p>A lock package reaches a member's lanes by naming it, by naming nobody, or by being exported
 * across an API edge; the three buckets below cover all of them without walking the export graph.
 * Deliberately coarse in one direction only: a package that is merely <em>exportable</em> counts for
 * every member, which can rebuild a member whose classpath did not actually move but can never miss
 * one whose did. Records outside {@code [[package]]} — notably locked toolchains — are not read
 * here; a toolchain change already moves the member's toolchain identity.
 *
 * <p>The recorded artifact hashes are the identity used, not the bytes on disk: the lock is what
 * says which artifact belongs on the lane, and the command's verified-artifact index is what
 * confirms the file matches when the lane is actually built.
 */
final class WorkspaceMemberLockDigest {
    private final String sharedDigest;
    private final Map<String, String> attributedDigests;
    private final Map<String, String> workspaceDigests;

    WorkspaceMemberLockDigest(ZoltLockfile lockfile) {
        List<String> shared = new ArrayList<>();
        Map<String, List<String>> attributed = new LinkedHashMap<>();
        Map<String, List<String>> workspaces = new LinkedHashMap<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            String identity = identity(lockPackage);
            if (lockPackage.workspace().isPresent()) {
                workspaces
                        .computeIfAbsent(lockPackage.workspace().orElseThrow(), ignored -> new ArrayList<>())
                        .add(identity);
                continue;
            }
            if (lockPackage.members().isEmpty() || !lockPackage.exportedBy().isEmpty()) {
                shared.add(identity);
                continue;
            }
            for (String member : lockPackage.members()) {
                attributed.computeIfAbsent(member, ignored -> new ArrayList<>()).add(identity);
            }
        }
        this.sharedDigest = digest(shared);
        this.attributedDigests = digests(attributed);
        this.workspaceDigests = digests(workspaces);
    }

    /**
     * @param memberPath the member being observed
     * @param visibleMembers the workspace members whose lock entries the member can see, which is
     *     its own plus its dependency closure for the lane being observed
     */
    String forMember(String memberPath, Set<String> visibleMembers) {
        List<String> parts = new ArrayList<>();
        parts.add(sharedDigest);
        parts.add(attributedDigests.getOrDefault(memberPath, ""));
        Set<String> visible = new TreeSet<>(visibleMembers);
        visible.add(memberPath);
        for (String member : visible) {
            parts.add(member + "=" + workspaceDigests.getOrDefault(member, ""));
        }
        return WorkspaceHash.text(String.join("\n", parts));
    }

    private static Map<String, String> digests(Map<String, List<String>> identities) {
        Map<String, String> digests = new LinkedHashMap<>();
        identities.forEach((member, values) -> digests.put(member, digest(values)));
        return Map.copyOf(digests);
    }

    private static String digest(List<String> identities) {
        return WorkspaceHash.text(String.join("\n", new TreeSet<>(identities)));
    }

    private static String identity(LockPackage lockPackage) {
        return String.join(
                "|",
                lockPackage.packageId().toString(),
                lockPackage.version(),
                lockPackage.scope().name(),
                lockPackage.jar().orElse(""),
                lockPackage.jarSha256().orElse(""),
                lockPackage.artifact().orElse(""),
                lockPackage.artifactType().orElse(""),
                lockPackage.artifactSha256().orElse(""),
                lockPackage.workspace().orElse(""),
                lockPackage.workspaceOutput().orElse(""),
                String.join(",", lockPackage.dependencies()),
                String.join(",", lockPackage.policies()));
    }
}
