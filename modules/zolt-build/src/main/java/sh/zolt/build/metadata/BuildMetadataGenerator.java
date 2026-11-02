package sh.zolt.build.metadata;

import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.provenance.BuildProvenance;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.provenance.GitProvenance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class BuildMetadataGenerator {
    private static final String SOURCE_DATE_EPOCH = "SOURCE_DATE_EPOCH";
    private static final Path BUILD_INFO_PATH = Path.of("META-INF/build-info.properties");
    private static final Path GIT_PROPERTIES_PATH = Path.of("git.properties");

    private final Clock clock;
    private final BuildProvenanceSource provenanceSource;
    private final Map<String, String> environment;

    public BuildMetadataGenerator() {
        this(Clock.systemUTC(), BuildProvenanceSource.empty(), System.getenv());
    }

    public BuildMetadataGenerator(BuildProvenanceSource provenanceSource) {
        this(Clock.systemUTC(), provenanceSource, System.getenv());
    }

    BuildMetadataGenerator(Clock clock, Map<String, String> environment) {
        this(clock, BuildProvenanceSource.empty(), environment);
    }

    BuildMetadataGenerator(
            Clock clock,
            BuildProvenanceSource provenanceSource,
            Map<String, String> environment) {
        this.clock = clock;
        this.provenanceSource = provenanceSource == null ? BuildProvenanceSource.empty() : provenanceSource;
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    /** Destinations that this generator will actually write, including conditional Git provenance. */
    public java.util.Set<Path> plannedOutputPaths(Path projectDirectory, ProjectConfig config) {
        BuildMetadataSettings settings = config.build().metadata();
        java.util.Set<Path> outputs = new java.util.HashSet<>();
        if (settings.buildInfo()) {
            outputs.add(BUILD_INFO_PATH);
        }
        if (settings.git()) {
            BuildProvenance provenance = provenanceSource.read(
                    projectDirectory, effectiveEnvironment(settings.reproducible()), clock);
            if (gitProperties(provenance.git()).isPresent()) {
                outputs.add(GIT_PROPERTIES_PATH);
            }
        }
        return java.util.Set.copyOf(outputs);
    }

    public BuildMetadataResult generate(Path projectDirectory, ProjectConfig config, Path outputDirectory) {
        BuildMetadataSettings settings = config.build().metadata();
        if (!settings.buildInfo() && !settings.git()) {
            return new BuildMetadataResult(List.of());
        }

        BuildProvenance provenance = provenanceSource.read(
                projectDirectory,
                effectiveEnvironment(settings.reproducible()),
                clock);
        List<Path> generated = new ArrayList<>();
        if (settings.buildInfo()) {
            generated.add(writeProperties(
                    outputDirectory.resolve(BUILD_INFO_PATH),
                    buildInfoProperties(config, provenance)));
        }
        if (settings.git()) {
            gitProperties(provenance.git()).ifPresent(properties -> generated.add(writeProperties(
                    outputDirectory.resolve(GIT_PROPERTIES_PATH),
                    properties)));
        }
        return new BuildMetadataResult(generated);
    }

    private Map<String, String> buildInfoProperties(ProjectConfig config, BuildProvenance provenance) {
        Map<String, String> values = new TreeMap<>();
        values.put("build.artifact", config.project().name());
        values.put("build.group", config.project().group());
        values.put("build.name", config.project().name());
        values.put("build.time", DateTimeFormatter.ISO_INSTANT.format(provenance.buildTimestamp()));
        values.put("build.version", config.project().version());
        if (!provenance.zoltVersion().isBlank()) {
            values.put("build.tool.name", "zolt");
            values.put("build.tool.version", provenance.zoltVersion());
        }
        provenance.resolutionFingerprint()
                .ifPresent(fingerprint -> values.put("build.resolution.fingerprint", fingerprint));
        return values;
    }

    private static Optional<Map<String, String>> gitProperties(GitProvenance git) {
        if (git.commitSha().isEmpty() || git.shortSha().isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> values = new TreeMap<>();
        values.put("git.branch", git.branch().orElse("HEAD"));
        values.put("git.commit.id", git.commitSha().orElseThrow());
        values.put("git.commit.id.abbrev", git.shortSha().orElseThrow());
        git.dirty().ifPresent(dirty -> values.put("git.dirty", Boolean.toString(dirty)));
        return Optional.of(values);
    }

    private Map<String, String> effectiveEnvironment(boolean reproducible) {
        if (!reproducible) {
            Map<String, String> effective = new TreeMap<>(environment);
            effective.remove(SOURCE_DATE_EPOCH);
            return effective;
        }
        if (hasValidSourceDateEpoch(environment)) {
            return environment;
        }
        Map<String, String> effective = new TreeMap<>(environment);
        effective.put(SOURCE_DATE_EPOCH, "0");
        return effective;
    }

    private static boolean hasValidSourceDateEpoch(Map<String, String> environment) {
        String value = environment.get(SOURCE_DATE_EPOCH);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            Long.parseLong(value.trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static Path writeProperties(Path path, Map<String, String> values) {
        try {
            Files.createDirectories(path.getParent());
            StringBuilder content = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                content.append(entry.getKey())
                        .append('=')
                        .append(escape(entry.getValue()))
                        .append('\n');
            }
            Files.writeString(path, content.toString(), StandardCharsets.UTF_8);
            return path;
        } catch (IOException exception) {
            throw new BuildMetadataException(
                    "Could not write build metadata at "
                            + path
                            + ". Check that the build output directory is writable.",
                    exception);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
