package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.GeneratedSourceHashes.directoryHash;
import static sh.zolt.build.generatedsource.GeneratedSourceHashes.fileHash;
import static sh.zolt.build.generatedsource.GeneratedSourceHashes.relative;
import static sh.zolt.build.generatedsource.GeneratedSourceHashes.sha256;

import sh.zolt.build.BuildException;
import sh.zolt.project.GeneratedSourceStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class OpenApiGeneratedSourceCache {
    private static final String FINGERPRINT_VERSION = "1";
    private static final String STATE_VERSION = "1";

    GenerationCacheState state(
            Path projectRoot,
            Path output,
            List<Path> toolClasspath,
            String scope,
            GeneratedSourceStep step) {
        return new GenerationCacheState(
                output.resolve(".zolt-openapi-" + scope + "-" + step.id() + ".fingerprint"),
                output.resolve(".zolt-openapi-" + scope + "-" + step.id() + ".log"),
                producerFingerprint(projectRoot, toolClasspath, scope, step));
    }

    boolean isCurrent(Path output, GenerationCacheState state) {
        return Files.isDirectory(output)
                && Files.isRegularFile(state.fingerprint())
                && readFingerprint(state.fingerprint()).equals(stateContent(output, state));
    }

    void writeFingerprint(Path output, GenerationCacheState state) {
        try {
            Files.writeString(state.fingerprint(), stateContent(output, state), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write OpenAPI generation fingerprint at "
                            + state.fingerprint()
                            + ". Check filesystem permissions.",
                    exception);
        }
    }

    void writeLog(GenerationCacheState state, String output) {
        try {
            Files.writeString(state.log(), output, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write OpenAPI generation log at "
                            + state.log()
                            + ". Check filesystem permissions.",
                    exception);
        }
    }

    static String producerFingerprint(
            Path projectRoot,
            List<Path> toolClasspath,
            String scope,
            GeneratedSourceStep step) {
        StringBuilder content = new StringBuilder();
        content.append("version=").append(FINGERPRINT_VERSION).append('\n');
        content.append("scope=").append(scope).append('\n');
        content.append("step=").append(step).append('\n');
        content.append("[toolClasspath]\n");
        toolClasspath.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .forEach(path -> content
                        .append(relative(projectRoot, path))
                        .append('|')
                        .append(fileHash(path))
                        .append('\n'));
        content.append("[inputs]\n");
        step.inputs().stream()
                .map(input -> projectRoot.resolve(input).normalize())
                .sorted()
                .forEach(path -> content
                        .append(relative(projectRoot, path))
                        .append('|')
                        .append(fileHash(path))
                        .append('\n'));
        step.openApi().config().ifPresent(value -> content
                .append("config=")
                .append(value)
                .append('|')
                .append(fileHash(projectRoot.resolve(value).normalize()))
                .append('\n'));
        step.openApi().templateDir().ifPresent(value -> content
                .append("templateDir=")
                .append(value)
                .append('|')
                .append(fileHash(projectRoot.resolve(value).normalize()))
                .append('\n'));
        return sha256(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String readFingerprint(Path fingerprint) {
        try {
            return Files.readString(fingerprint);
        } catch (IOException exception) {
            return "";
        }
    }

    private static String stateContent(Path output, GenerationCacheState state) {
        String outputHash = directoryHash(output, Set.of(state.fingerprint(), state.log()));
        return "version=" + STATE_VERSION
                + "\nproducer=" + state.fingerprintSha256()
                + "\noutput=" + outputHash
                + "\n";
    }

    record GenerationCacheState(Path fingerprint, Path log, String fingerprintSha256) {
    }
}
