package sh.zolt.doctor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record JdkStatus(
        Optional<Path> javaHome,
        Optional<Path> java,
        Optional<Path> javac,
        Optional<Path> jar,
        Optional<String> version,
        String requiredVersion) {
    public JdkStatus {
        javaHome = javaHome == null ? Optional.empty() : javaHome;
        java = java == null ? Optional.empty() : java;
        javac = javac == null ? Optional.empty() : javac;
        jar = jar == null ? Optional.empty() : jar;
        version = version == null ? Optional.empty() : version;
    }

    public boolean complete() {
        return java.isPresent() && javac.isPresent() && jar.isPresent();
    }

    public boolean versionMatches() {
        return versionSatisfies();
    }

    public boolean versionSatisfies() {
        if (version.isEmpty()) {
            return false;
        }
        Optional<Integer> detected = javaFeatureVersion(version.orElseThrow());
        Optional<Integer> required = javaFeatureVersion(requiredVersion);
        if (detected.isPresent() && required.isPresent()) {
            return detected.orElseThrow() >= required.orElseThrow();
        }
        return version.map(value -> value.equals(requiredVersion)).orElse(false);
    }

    public boolean ok() {
        return complete() && versionSatisfies();
    }

    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (java.isEmpty()) {
            problems.add("Missing `java`. Install a JDK and set JAVA_HOME or add java to PATH.");
        }
        if (javac.isEmpty()) {
            problems.add("Missing `javac`. Install a JDK and set JAVA_HOME or add javac to PATH.");
        }
        if (jar.isEmpty()) {
            problems.add("Missing `jar`. Install a JDK and set JAVA_HOME or add jar to PATH.");
        }
        if (complete() && version.isEmpty()) {
            problems.add("Could not determine Java version. Check that `java -version` runs successfully.");
        }
        if (version.isPresent() && !versionSatisfies()) {
            problems.add("Java version mismatch. zolt.toml requires "
                    + requiredVersion
                    + " or newer but detected "
                    + version.orElseThrow()
                    + ". Install Java "
                    + requiredVersion
                    + " or newer, set JAVA_HOME to a suitable JDK, or update [project].java.");
        }
        return List.copyOf(problems);
    }

    /** The detected JDK's Java feature version (e.g. 17, 21), if it could be parsed. */
    public Optional<Integer> featureVersion() {
        return version.flatMap(JdkStatus::javaFeatureVersion);
    }

    private static Optional<Integer> javaFeatureVersion(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip();
        if (normalized.startsWith("1.")) {
            int index = 2;
            while (index < normalized.length() && Character.isDigit(normalized.charAt(index))) {
                index++;
            }
            if (index > 2) {
                try {
                    return Optional.of(Integer.parseInt(normalized.substring(2, index)));
                } catch (NumberFormatException exception) {
                    return Optional.empty();
                }
            }
        }
        try {
            return Optional.of(Integer.parseInt(normalized));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
