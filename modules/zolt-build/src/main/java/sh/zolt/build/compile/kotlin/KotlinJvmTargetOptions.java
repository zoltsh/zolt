package sh.zolt.build.compile.kotlin;

import sh.zolt.build.KotlinCompileException;

/** Stable JVM target and module identity shared by one Kotlin compiler invocation. */
public record KotlinJvmTargetOptions(
        String release,
        String moduleName,
        boolean hostPlatformApi,
        boolean useJdkRelease) {
    public KotlinJvmTargetOptions {
        release = require(release, "effective Java release");
        moduleName = require(moduleName, "module name");
        if (hostPlatformApi && useJdkRelease) {
            throw new KotlinCompileException(
                    "Kotlin host platform-API mode cannot use -Xjdk-release.");
        }
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new KotlinCompileException("Kotlin compilation requires a " + label + ".");
        }
        return value.strip();
    }
}
