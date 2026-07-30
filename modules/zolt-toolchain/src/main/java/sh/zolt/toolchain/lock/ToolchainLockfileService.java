package sh.zolt.toolchain.lock;

import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.platform.Architecture;
import sh.zolt.toolchain.platform.OperatingSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class ToolchainLockfileService {
    public List<LockedJavaToolchain> readJava(Path lockfile) {
        if (!Files.isRegularFile(lockfile)) {
            return List.of();
        }
        try {
            TomlParseResult result = Toml.parse(lockfile);
            if (result.hasErrors()) {
                TomlParseError error = result.errors().getFirst();
                throw new ActionableException(
                        "Could not parse zolt.lock near " + error.position() + ": " + error.getMessage(),
                        "Fix zolt.lock or run `zolt resolve --workspace` to regenerate dependency metadata.");
            }
            TomlArray array = result.getArray(List.of("toolchain", "java"));
            if (array == null) {
                return List.of();
            }
            java.util.ArrayList<LockedJavaToolchain> locked = new java.util.ArrayList<>();
            for (int index = 0; index < array.size(); index++) {
                TomlTable table = array.getTable(index);
                if (table == null) {
                    throw new ActionableException(
                            "Invalid Java toolchain lock entry at index " + index + ".",
                            "Run `zolt toolchain sync` to rewrite Java toolchain lock metadata.");
                }
                locked.add(readJavaToolchain(table));
            }
            return List.copyOf(locked);
        } catch (IOException exception) {
            throw new ActionableException(
                    "Could not read zolt.lock at " + lockfile + ".",
                    "Check that the file exists and is readable.");
        } catch (TomlInvalidTypeException exception) {
            throw new ActionableException(
                    "Invalid Java toolchain metadata in zolt.lock.",
                    "Run `zolt toolchain sync` to rewrite Java toolchain lock metadata.");
        }
    }

    public Optional<LockedJavaToolchain> findJava(Path lockfile, JavaToolchainRequest request, HostPlatform platform) {
        return readJava(lockfile).stream()
                .filter(locked -> locked.platform().equals(platform))
                .filter(locked -> locked.request().version().equals(request.version()))
                .filter(locked -> locked.request().distribution().equals(request.distribution()))
                .filter(locked -> locked.request().features().equals(request.features()))
                .findFirst();
    }

    public void writeJava(Path lockfile, LockedJavaToolchain locked) {
        writeJava(lockfile, List.of(locked));
    }

    public void writeJava(Path lockfile, List<LockedJavaToolchain> locked) {
        try {
            Path parent = lockfile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String base = Files.isRegularFile(lockfile)
                    ? Files.readString(lockfile)
                    : "version = " + ZoltLockfile.CURRENT_VERSION + "\n\n";
            String content = removeJavaLocks(base);
            for (LockedJavaToolchain java : ordered(locked)) {
                content = appendJavaLock(content, java);
            }
            AtomicLockfileWriter.write(lockfile, content);
        } catch (IOException exception) {
            throw new ActionableException(
                    "Could not write Java toolchain metadata to " + lockfile + ".",
                    "Check that the project directory is writable and try again.");
        }
    }

    private static LockedJavaToolchain readJavaToolchain(TomlTable table) {
        String id = requiredString(table, "id");
        JavaToolchainRequest request = new JavaToolchainRequest(
                requiredString(requiredTable(table, "request"), "version"),
                JavaDistribution.fromId(requiredString(requiredTable(table, "request"), "distribution")).orElseThrow(),
                features(requiredTable(table, "request")),
                ToolchainPolicy.fromId(optionalString(requiredTable(table, "request"), "policy")
                        .orElse(ToolchainPolicy.PREFER_MANAGED.id())).orElse(ToolchainPolicy.PREFER_MANAGED));
        HostPlatform platform = HostPlatform.parse(
                requiredString(requiredTable(table, "platform"), "os")
                        + "-"
                        + requiredString(requiredTable(table, "platform"), "arch"));
        TomlTable resolved = requiredTable(table, "resolved");
        TomlTable artifact = requiredTable(table, "artifact");
        TomlTable layout = requiredTable(table, "layout");
        TomlTable executables = requiredTable(layout, "executables");
        return new LockedJavaToolchain(
                id,
                request,
                platform,
                requiredString(resolved, "version"),
                JavaDistribution.fromId(requiredString(resolved, "distribution")).orElseThrow(),
                requiredString(artifact, "catalog"),
                optionalString(artifact, "uri").orElse(""),
                optionalString(artifact, "sha256").orElse(""),
                new JavaToolchainLayout(
                        requiredString(layout, "javaHome"),
                        requiredString(executables, "java"),
                        requiredString(executables, "javac"),
                        requiredString(executables, "jar"),
                        optionalString(executables, "nativeImage").orElse("")));
    }

    private static Set<JavaFeature> features(TomlTable request) {
        TomlArray array = request.getArray("features");
        if (array == null) {
            return Set.of();
        }
        LinkedHashSet<JavaFeature> features = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            features.add(JavaFeature.fromId(array.getString(index)).orElseThrow());
        }
        return java.util.Collections.unmodifiableSet(features);
    }

    private static TomlTable requiredTable(TomlTable table, String key) {
        TomlTable nested = table.getTable(key);
        if (nested == null) {
            throw new ActionableException(
                    "Missing Java toolchain lock table `" + key + "`.",
                    "Run `zolt toolchain sync` to rewrite Java toolchain lock metadata.");
        }
        return nested;
    }

    private static String requiredString(TomlTable table, String key) {
        String value = table.getString(key);
        if (value == null || value.isBlank()) {
            throw new ActionableException(
                    "Missing Java toolchain lock field `" + key + "`.",
                    "Run `zolt toolchain sync` to rewrite Java toolchain lock metadata.");
        }
        return value.strip();
    }

    private static Optional<String> optionalString(TomlTable table, String key) {
        String value = table.getString(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }

    private static String removeJavaLocks(String content) {
        StringBuilder output = new StringBuilder();
        boolean skipping = false;
        for (String line : content.lines().toList()) {
            String trimmed = line.strip();
            if ("[[toolchain.java]]".equals(trimmed)) {
                skipping = true;
                continue;
            }
            if (skipping && trimmed.startsWith("[[")) {
                skipping = false;
            }
            if (!skipping) {
                output.append(line).append('\n');
            }
        }
        return output.toString().stripTrailing() + "\n\n";
    }

    private static List<LockedJavaToolchain> ordered(List<LockedJavaToolchain> locked) {
        return locked.stream()
                .sorted(Comparator
                        .comparing((LockedJavaToolchain java) -> java.request().version())
                        .thenComparing(java -> java.request().distribution().orElseThrow().id())
                        .thenComparing(java -> java.request().features().stream()
                                .map(JavaFeature::id)
                                .sorted()
                                .toList()
                                .toString())
                        .thenComparing(java -> java.request().policy().id())
                        .thenComparingInt(java -> osOrder(java.platform().os()))
                        .thenComparingInt(java -> archOrder(java.platform().arch())))
                .toList();
    }

    private static int osOrder(OperatingSystem os) {
        return switch (os) {
            case LINUX -> 0;
            case MACOS -> 1;
            case WINDOWS -> 2;
        };
    }

    private static int archOrder(Architecture arch) {
        return switch (arch) {
            case X64 -> 0;
            case AARCH64 -> 1;
        };
    }

    private static String appendJavaLock(String content, LockedJavaToolchain locked) {
        StringBuilder output = new StringBuilder(content);
        output.append("[[toolchain.java]]\n");
        assignment(output, "id", locked.id());
        assignment(output, "request.version", locked.request().version());
        assignment(output, "request.distribution", locked.request().distribution().orElseThrow().id());
        output.append("request.features = ");
        stringArray(output, locked.request().features().stream().map(JavaFeature::id).sorted().toList());
        output.append('\n');
        assignment(output, "request.policy", locked.request().policy().id());
        assignment(output, "platform.os", locked.platform().os().id());
        assignment(output, "platform.arch", locked.platform().arch().id());
        assignment(output, "resolved.version", locked.resolvedVersion());
        assignment(output, "resolved.distribution", locked.resolvedDistribution().id());
        assignment(output, "artifact.catalog", locked.catalog());
        if (!locked.artifactUri().isBlank()) {
            assignment(output, "artifact.uri", locked.artifactUri());
        }
        if (!locked.artifactSha256().isBlank()) {
            assignment(output, "artifact.sha256", locked.artifactSha256());
        }
        assignment(output, "layout.javaHome", locked.layout().javaHome());
        assignment(output, "layout.executables.java", locked.layout().java());
        assignment(output, "layout.executables.javac", locked.layout().javac());
        assignment(output, "layout.executables.jar", locked.layout().jar());
        if (!locked.layout().nativeImage().isBlank()) {
            assignment(output, "layout.executables.nativeImage", locked.layout().nativeImage());
        }
        output.append('\n');
        return output.toString();
    }

    private static void assignment(StringBuilder output, String key, String value) {
        output.append(key).append(" = ").append(quote(value)).append('\n');
    }

    private static void stringArray(StringBuilder output, List<String> values) {
        output.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                output.append(", ");
            }
            output.append(quote(values.get(index)));
        }
        output.append(']');
    }

    private static String quote(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
