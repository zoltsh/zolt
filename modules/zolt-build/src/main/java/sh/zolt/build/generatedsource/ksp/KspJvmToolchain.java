package sh.zolt.build.generatedsource.ksp;

import java.nio.file.Path;
import java.util.List;

/** Checksum-derived identity and isolated launch classpaths for one KSP2 JVM step. */
record KspJvmToolchain(
        String version,
        List<Path> engineClasspath,
        List<Path> processorClasspath,
        String identity) {
    KspJvmToolchain {
        version = requireText(version, "KSP version");
        engineClasspath = requirePaths(engineClasspath, "KSP engine classpath");
        processorClasspath = requirePaths(processorClasspath, "KSP processor classpath");
        identity = requireText(identity, "KSP toolchain identity");
    }

    private static List<Path> requirePaths(List<Path> paths, String label) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty.");
        }
        return paths.stream().map(path -> path.toAbsolutePath().normalize()).toList();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must be a non-empty string.");
        }
        return value.strip();
    }
}
