package sh.zolt.build.cache;

import sh.zolt.doctor.JdkStatus;

/**
 * The resolved JDK identity folded into a build-cache key.
 *
 * <p>javac can emit different bytecode across JDK majors even for the same {@code --release} target, so
 * the compiling JDK must be part of the key. The same effective identity is used by the no-op
 * fingerprint and incremental state. Managed toolchains identify their locked artifact; ambient
 * toolchains include full runtime/vendor facts and a path-sensitive fallback.
 */
public final class BuildCacheJdkIdentity {
    private BuildCacheJdkIdentity() {
    }

    public static String of(JdkStatus jdkStatus) {
        return jdkStatus.effectiveCompilerIdentity();
    }
}
