package sh.zolt.tree;

import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;

/**
 * Which recorded policy effects belong to one project's view of the lock.
 *
 * <p>Design §4.5: a member projects its own graph out of the one authoritative workspace lock, so it
 * shows the effects that shaped that graph. A global exclusion is workspace-wide policy and always
 * applies; an edge exclusion belongs to a member only when the package it was applied from is in that
 * member's graph, so one member's tree does not recite another member's excluded edges.
 */
final class PolicyEffectScope {
    private PolicyEffectScope() {
    }

    static Predicate<LockPolicyEffect> of(ZoltLockfile lockfile, String member) {
        if (".".equals(member)) {
            return effect -> true;
        }
        Set<String> coordinates = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.members().contains(member))
                .map(lockPackage -> lockPackage.packageId() + ":" + lockPackage.version())
                .collect(Collectors.toSet());
        return effect -> effect.source().map(coordinates::contains).orElse(true);
    }
}
