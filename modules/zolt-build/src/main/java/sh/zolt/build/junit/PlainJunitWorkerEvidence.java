package sh.zolt.build.junit;

import sh.zolt.project.ProjectConfig;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class PlainJunitWorkerEvidence {
    private PlainJunitWorkerEvidence() {
    }

    static Map<String, String> environment(
            Path projectDirectory,
            ProjectConfig config,
            Map<String, String> environment,
            TestJvmArguments jvmArguments,
            String workerId) {
        Map<String, String> values = new LinkedHashMap<>(environment);
        Path outputDirectory = projectDirectory
                .resolve(config.build().outputRoot())
                .resolve("test-workers")
                .resolve(workerId)
                .toAbsolutePath()
                .normalize();
        values.put("ZOLT_TEST_WORKER_ID", workerId);
        values.put(
                "ZOLT_TEST_WORKER_OUTPUT_DIR",
                outputDirectory.toString());
        jacocoWorkerExecFile(jvmArguments, workerId)
                .ifPresent(path -> values.put(
                        "ZOLT_COVERAGE_EXEC_FILE",
                        path.toString()));
        return Map.copyOf(values);
    }

    static Optional<Path> reports(
            Optional<Path> reportsDirectory,
            String workerId) {
        return reportsDirectory.map(
                directory -> directory.resolve("workers").resolve(workerId));
    }

    static Optional<Path> profile(
            Optional<Path> profileDirectory,
            String workerId) {
        return profileDirectory.map(
                directory -> directory.resolve("workers").resolve(workerId));
    }

    static TestJvmArguments jvmArguments(
            TestJvmArguments jvmArguments,
            String workerId) {
        return new TestJvmArguments(jvmArguments.values().stream()
                .map(argument -> rewriteJacocoDestfile(argument, workerId)
                        .orElse(argument))
                .toList());
    }

    static void writeManifests(
            Optional<Path> reportsDirectory,
            TestJvmArguments jvmArguments,
            List<String> workerIds) {
        reportsDirectory.ifPresent(directory -> writeManifest(
                directory.resolve("workers").resolve("zolt-workers.json"),
                workerIds));
        jacocoExecFile(jvmArguments)
                .map(Path::getParent)
                .ifPresent(directory -> writeManifest(
                        directory.resolve("workers").resolve(
                                "zolt-workers.json"),
                        workerIds));
    }

    private static void writeManifest(
            Path manifest,
            List<String> workerIds) {
        try {
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, json(workerIds));
        } catch (IOException exception) {
            throw new TestRunException(
                    "Could not write test worker evidence manifest to "
                            + manifest
                            + ".",
                    exception);
        }
    }

    private static String json(List<String> workerIds) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"version\": 1,\n");
        json.append("  \"workers\": [\n");
        for (int index = 0; index < workerIds.size(); index++) {
            json.append("    \"").append(workerIds.get(index)).append("\"");
            if (index + 1 < workerIds.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static Optional<String> rewriteJacocoDestfile(
            String argument,
            String workerId) {
        Optional<Path> execFile =
                jacocoWorkerExecFile(argument, workerId);
        if (execFile.isEmpty()) {
            return Optional.empty();
        }
        int start = argument.indexOf("destfile=") + "destfile=".length();
        int end = argument.indexOf(',', start);
        if (end < 0) {
            end = argument.length();
        }
        String rewritten =
                argument.substring(0, start)
                        + execFile.orElseThrow()
                        + argument.substring(end);
        int append = rewritten.indexOf(",append=");
        if (append < 0) {
            return Optional.of(rewritten + ",append=true");
        }
        int valueStart = append + ",append=".length();
        int valueEnd = rewritten.indexOf(',', valueStart);
        if (valueEnd < 0) {
            valueEnd = rewritten.length();
        }
        return Optional.of(
                rewritten.substring(0, valueStart)
                        + "true"
                        + rewritten.substring(valueEnd));
    }

    private static Optional<Path> jacocoWorkerExecFile(
            TestJvmArguments jvmArguments,
            String workerId) {
        return jvmArguments.values().stream()
                .map(argument -> jacocoWorkerExecFile(argument, workerId))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<Path> jacocoExecFile(
            TestJvmArguments jvmArguments) {
        return jvmArguments.values().stream()
                .map(PlainJunitWorkerEvidence::jacocoExecFile)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static Optional<Path> jacocoWorkerExecFile(
            String argument,
            String workerId) {
        Optional<Path> canonical = jacocoExecFile(argument);
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        Path execFile = canonical.orElseThrow();
        Path parent = execFile.getParent();
        if (parent == null) {
            return Optional.empty();
        }
        Path workerExecFile = parent
                .resolve("workers")
                .resolve(workerId)
                .resolve(execFile.getFileName())
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(workerExecFile.getParent());
        } catch (IOException exception) {
            throw new TestRunException(
                    "Could not create worker coverage directory "
                            + workerExecFile.getParent()
                            + ".",
                    exception);
        }
        return Optional.of(workerExecFile);
    }

    private static Optional<Path> jacocoExecFile(String argument) {
        if (argument == null
                || !argument.startsWith("-javaagent:")
                || !argument.toLowerCase(Locale.ROOT).contains("jacoco")) {
            return Optional.empty();
        }
        int start = argument.indexOf("destfile=");
        if (start < 0) {
            return Optional.empty();
        }
        start += "destfile=".length();
        int end = argument.indexOf(',', start);
        if (end < 0) {
            end = argument.length();
        }
        return Optional.of(Path.of(argument.substring(start, end)));
    }
}
