package sh.zolt.workspace.service;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ZoltLockfile;

/**
 * One member's view of the workspace's single lock.
 *
 * <p>{@code classpaths} carries the member's lanes with workspace packages already resolved through
 * the workspace root and each provider's {@code workspaceOutput}; {@code lockfile} is the same
 * selection as a lock view, for the read-only reports ({@code zolt classpath audit}) that describe
 * packages rather than build a classpath. Both come from one closure, so a report and the classpath
 * it describes can never disagree about what the member can see.
 */
public record MemberLockProjection(
        String member,
        ClasspathSet classpaths,
        ZoltLockfile lockfile) {
}
