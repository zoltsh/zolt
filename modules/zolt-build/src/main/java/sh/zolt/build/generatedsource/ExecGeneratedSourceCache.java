package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.GeneratedSourceHashes.fileHash;
import static sh.zolt.build.generatedsource.GeneratedSourceHashes.classpathHash;
import static sh.zolt.build.generatedsource.GeneratedSourceHashes.relative;
import static sh.zolt.build.generatedsource.GeneratedSourceHashes.sha256;

import sh.zolt.build.BuildException;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Content-fingerprint producer skip-gate for exec steps. The fingerprint hashes the runner-specific
 * tool identity (jvm/project: classpath jar/class bytes + mainClass; process: binary name + probed
 * version), argv, the expanded input content, env names + configured literal values, secretEnv target/
 * source names (never secret values), a digest of each inheritEnv variable's actual runtime value, the
 * non-secret cacheSalt, cwd, and produces/into/cache. Sidecar files live OUTSIDE the
 * declared output directory so the consumer fence hashes only tool-produced bytes and the resources
 * lane never copies build metadata into a package. For {@code cache = "none"} the fingerprint is
 * written as provenance only; the service never consults it as a skip gate.
 */
final class ExecGeneratedSourceCache {
    private static final String FINGERPRINT_VERSION = "3";

    private final Path metadataDirectory;

    ExecGeneratedSourceCache(Path metadataDirectory) {
        this.metadataDirectory = metadataDirectory;
    }

    GenerationCacheState state(
            Path projectRoot,
            Path cwd,
            List<Path> classpath,
            ExecToolIdentity toolIdentity,
            String scope,
            GeneratedSourceStep step,
            Map<String, String> inheritEnvDigests) {
        String base = "exec-" + scope + "-" + step.id();
        return new GenerationCacheState(
                metadataDirectory.resolve(base + ".fingerprint"),
                metadataDirectory.resolve(base + ".log"),
                producerFingerprint(
                        projectRoot,
                        cwd,
                        classpath,
                        toolIdentity,
                        scope,
                        step,
                        inheritEnvDigests));
    }

    boolean isCurrent(Path output, GenerationCacheState state) {
        return Files.isDirectory(output)
                && Files.isRegularFile(state.fingerprint())
                && readFingerprint(state.fingerprint()).equals(state.fingerprintSha256());
    }

    void writeFingerprint(GenerationCacheState state) {
        try {
            Files.createDirectories(state.fingerprint().getParent());
            Files.writeString(state.fingerprint(), state.fingerprintSha256(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write exec generation fingerprint at " + state.fingerprint()
                            + ". Check filesystem permissions.",
                    exception);
        }
    }

    void writeLog(GenerationCacheState state, String log) {
        try {
            Files.createDirectories(state.log().getParent());
            Files.writeString(state.log(), log, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not write exec generation log at " + state.log() + ". Check filesystem permissions.",
                    exception);
        }
    }

    static String producerFingerprint(
            Path projectRoot,
            Path cwd,
            List<Path> classpath,
            ExecToolIdentity toolIdentity,
            String scope,
            GeneratedSourceStep step,
            Map<String, String> inheritEnvDigests) {
        ExecGenerationSettings exec = step.exec();
        StringBuilder content = new StringBuilder();
        content.append("version=").append(FINGERPRINT_VERSION).append('\n');
        content.append("scope=").append(scope).append('\n');
        content.append("id=").append(step.id()).append('\n');
        content.append("tool=").append(exec.toolName()).append('\n');
        content.append("runner=").append(exec.tool().runner()).append('\n');
        content.append("mainClass=").append(exec.tool().mainClass()).append('\n');
        content.append("binary=").append(toolIdentity.binary()).append('\n');
        content.append("probedVersion=").append(toolIdentity.probedVersion()).append('\n');
        content.append("versionExpect=").append(exec.tool().versionExpect().orElse("")).append('\n');
        content.append("produces=").append(exec.produces() == null ? "" : exec.produces().configValue()).append('\n');
        content.append("into=").append(exec.into().orElse("")).append('\n');
        content.append("cache=").append(exec.cache()).append('\n');
        // The user's non-secret assertion that secret-derived output changed; Zolt never digests the
        // secret value itself, so bumping the salt is the only honest content-cache invalidation for it.
        content.append("cacheSalt=").append(exec.cacheSalt().orElse("")).append('\n');
        content.append("cwd=").append(relative(projectRoot, cwd)).append('\n');
        content.append("[args]\n");
        exec.args().forEach(argument -> content.append(argument).append('\n'));
        content.append("[env]\n");
        new TreeMap<>(exec.env()).forEach((name, value) -> content.append(name).append('=').append(value).append('\n'));
        content.append("[secretEnv]\n");
        new TreeMap<>(exec.secretEnv()).forEach((target, source) ->
                content.append(target).append('=').append(source).append('\n'));
        // Each inheritEnv name folds in a digest of its ACTUAL runtime value (never the raw value), so a
        // changed inherited value / DB endpoint / token invalidates the cache. Absent variables digest to
        // a distinct "absent" marker so "unset" and "set to empty" stay distinguishable.
        content.append("[inheritEnv]\n");
        new TreeMap<>(inheritEnvDigests).forEach((name, digest) ->
                content.append(name).append('=').append(digest).append('\n'));
        content.append("[coordinates]\n");
        exec.tool().coordinates().stream()
                .sorted(java.util.Comparator.comparing(coordinate ->
                        coordinate.coordinate()
                                + ":"
                                + coordinate.version().orElse("")))
                .forEach(coordinate -> content
                .append(coordinate.coordinate()).append(':').append(coordinate.version().orElse("")).append('\n'));
        content.append("[classpath]\n");
        classpath.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .forEach(path -> content.append(relative(projectRoot, path)).append('|').append(classpathHash(path)).append('\n'));
        content.append("[inputs]\n");
        step.inputs().stream()
                .flatMap(input -> ExecInputExpander.expand(projectRoot, input).stream())
                .distinct()
                .sorted()
                .forEach(path -> content.append(relative(projectRoot, path)).append('|').append(fileHash(path)).append('\n'));
        return sha256(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String readFingerprint(Path fingerprint) {
        try {
            return Files.readString(fingerprint);
        } catch (IOException exception) {
            return "";
        }
    }

    /** The non-classpath tool identity that also enters the fingerprint (process runner only). */
    record ExecToolIdentity(String binary, String probedVersion) {
        static ExecToolIdentity none() {
            return new ExecToolIdentity("", "");
        }
    }

    record GenerationCacheState(Path fingerprint, Path log, String fingerprintSha256) {
    }
}
