package sh.zolt.build.packageevidence;

import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.booleanField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.displayPath;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.indent;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.nullablePathField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.nullableStringField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.stringArrayField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.stringField;

import sh.zolt.build.packaging.PackageArtifact;
import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packaging.PackageArchiveWriter;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.provenance.BuildProvenance;
import sh.zolt.provenance.BuildProvenanceSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PackageEvidenceManifestWriter {
    public static final String SCHEMA = "zolt.package-evidence.v3";
    private static final String SOURCE_DATE_EPOCH = "SOURCE_DATE_EPOCH";

    private final PackageEvidenceGeneratedSourceWriter generatedSourceWriter;
    private final PackageResourceEvidence packageResourceEvidence;
    private final BuildProvenanceSource provenanceSource;
    private final Clock clock;
    private final Map<String, String> environment;

    public PackageEvidenceManifestWriter() {
        this(BuildProvenanceSource.empty());
    }

    public PackageEvidenceManifestWriter(BuildProvenanceSource provenanceSource) {
        this(
                new PackageEvidenceGeneratedSourceWriter(),
                new PackageResourceEvidence(),
                provenanceSource,
                Clock.systemUTC(),
                System.getenv());
    }

    PackageEvidenceManifestWriter(
            PackageEvidenceGeneratedSourceWriter generatedSourceWriter,
            PackageResourceEvidence packageResourceEvidence) {
        this(
                generatedSourceWriter,
                packageResourceEvidence,
                BuildProvenanceSource.empty(),
                Clock.systemUTC(),
                System.getenv());
    }

    PackageEvidenceManifestWriter(
            PackageEvidenceGeneratedSourceWriter generatedSourceWriter,
            PackageResourceEvidence packageResourceEvidence,
            BuildProvenanceSource provenanceSource,
            Clock clock,
            Map<String, String> environment) {
        this.generatedSourceWriter = generatedSourceWriter;
        this.packageResourceEvidence = packageResourceEvidence;
        this.provenanceSource = provenanceSource == null ? BuildProvenanceSource.empty() : provenanceSource;
        this.clock = clock;
        this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public Path write(
            Path projectDirectory,
            ProjectConfig config,
            PackagePlan plan,
            PackageResult result,
            List<PackageArtifact> artifacts) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        Path manifestPath = evidenceManifestPath(result.jarPath());
        try {
            Files.createDirectories(manifestPath.getParent());
            PackageArchiveWriter.writeStringAtomically(
                    manifestPath,
                    json(projectRoot, config, plan, result, artifacts),
                    StandardCharsets.UTF_8);
            return manifestPath;
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not write package evidence manifest at "
                            + manifestPath
                            + ". Check that target/ is writable and retry.",
                    exception);
        }
    }

    public static Path evidenceManifestPath(Path artifactPath) {
        return artifactPath.resolveSibling(artifactPath.getFileName() + ".zolt-package.json");
    }

    private String json(
            Path projectRoot,
            ProjectConfig config,
            PackagePlan plan,
            PackageResult result,
            List<PackageArtifact> artifacts) throws IOException {
        StringBuilder json = new StringBuilder();
        BuildProvenance provenance = provenanceSource.read(
                projectRoot,
                effectiveEnvironment(config.build().metadata().reproducible()),
                clock);
        json.append("{\n");
        stringField(json, 1, "schema", SCHEMA, true);
        if (!provenance.zoltVersion().isBlank()) {
            builder(json, provenance);
            json.append(",\n");
        }
        project(json, config);
        json.append(",\n");
        packageInfo(json, projectRoot, config, plan, result);
        json.append(",\n");
        PackageEvidenceArtifactWriter.write(
                json,
                projectRoot,
                plan,
                result,
                artifacts);
        json.append(",\n");
        PackageEvidenceOutputWriter.write(
                json,
                projectRoot,
                plan,
                result,
                artifacts);
        json.append(",\n");
        PackageEvidenceInputWriter.writeMaterializedInputs(
                json,
                projectRoot,
                plan.evidence().materializedInputs(),
                result.materializedInputs());
        json.append(",\n");
        PackageEvidenceInputWriter.writeSupplementalInputs(
                json,
                plan.evidence().supplementalInputs());
        json.append(",\n");
        PackageEvidenceInputWriter.writeWorkspaceInputs(
                json,
                plan.evidence().workspaceInputs());
        json.append(",\n");
        PackageEvidenceInputWriter.writeDependencies(
                json,
                plan.dependencies());
        json.append(",\n");
        PackageMergeDecisionEvidenceWriter.write(json, result.mergeDecisions());
        json.append(",\n");
        generatedSourceWriter.write(json, projectRoot, config);
        json.append(",\n");
        resourceFiltering(json, projectRoot, packageResourceEvidence.collect(projectRoot, config.build()));
        json.append("\n}\n");
        return json.toString();
    }

    private static void builder(StringBuilder json, BuildProvenance provenance) {
        indent(json, 1).append("\"builder\": {\n");
        stringField(json, 2, "name", "zolt", true);
        stringField(json, 2, "version", provenance.zoltVersion(), true);
        stringField(json, 2, "jdkVersion", provenance.jdkVersion(), true);
        stringField(json, 2, "jdkVendor", provenance.jdkVendor(), true);
        stringField(json, 2, "builtAt", DateTimeFormatter.ISO_INSTANT.format(provenance.buildTimestamp()), true);
        nullableStringField(json, 2, "commit", provenance.git().commitSha(), true);
        nullableStringField(json, 2, "resolutionFingerprint", provenance.resolutionFingerprint(), false);
        indent(json, 1).append("}");
    }

    private static void project(StringBuilder json, ProjectConfig config) {
        indent(json, 1).append("\"project\": {\n");
        stringField(json, 2, "group", config.project().group(), true);
        stringField(json, 2, "name", config.project().name(), true);
        stringField(json, 2, "version", config.project().version(), true);
        nullableStringField(json, 2, "main", config.project().main(), false);
        indent(json, 1).append("}");
    }

    private static void packageInfo(
            StringBuilder json,
            Path projectRoot,
            ProjectConfig config,
            PackagePlan plan,
            PackageResult result) {
        indent(json, 1).append("\"package\": {\n");
        stringField(json, 2, "mode", result.mode().configValue(), true);
        stringField(json, 2, "archive", displayPath(projectRoot, result.jarPath()), true);
        stringField(json, 2, "applicationOutput", displayPath(projectRoot, plan.applicationOutput()), true);
        stringField(json, 2, "applicationLayout", plan.applicationLayout(), true);
        nullablePathField(json, 2, "runtimeClasspath", projectRoot, result.runtimeClasspathPath(), true);
        nullableStringField(json, 2, "startClass", config.project().main(), true);
        stringField(json, 2, "archiveSha256", PackageEvidenceChecksums.sha256(result.jarPath()), true);
        stringField(
                json,
                2,
                "inputFingerprint",
                plan.evidence().inputFingerprint(),
                true);
        stringField(
                json,
                2,
                "buildInputFingerprint",
                plan.evidence().buildInputFingerprint(),
                true);
        stringField(
                json,
                2,
                "applicationOutputFingerprint",
                plan.evidence().applicationOutputFingerprint(),
                true);
        stringField(
                json,
                2,
                "packageLockFingerprint",
                plan.evidence().packageLockFingerprint(),
                true);
        stringField(
                json,
                2,
                "resolutionFingerprint",
                plan.evidence().resolutionFingerprint(),
                true);
        stringField(
                json,
                2,
                "frameworkRulesIdentity",
                plan.evidence().frameworkRulesIdentity(),
                true);
        booleanField(json, 2, "hermetic", hermetic(config), false);
        indent(json, 1).append("}");
    }

    /**
     * An artifact is hermetic unless a {@code cache = "none"} main-scope exec step contributed to it:
     * such a step always runs against an oracle Zolt cannot hash, so the build is not provably
     * reproducible. Over-approximating taint is the safe direction for a reproducibility claim.
     */
    private static boolean hermetic(ProjectConfig config) {
        return config.build().generatedMainSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.EXEC)
                .noneMatch(step -> "none".equals(step.exec().cache()));
    }

    private static void resourceFiltering(
            StringBuilder json,
            Path projectRoot,
            PackageResourceEvidence.ResourceEvidence evidence) {
        ResourceFilteringSettings filtering = evidence.filtering();
        indent(json, 1).append("\"resourceFiltering\": {\n");
        booleanField(json, 2, "enabled", filtering.enabled(), true);
        booleanField(json, 2, "testEnabled", filtering.testEnabled(), true);
        stringField(json, 2, "missing", filtering.missing().configValue(), true);
        stringArrayField(json, 2, "includes", filtering.includes(), true);
        tokenSources(json, evidence.tokenSources());
        json.append(",\n");
        stringField(json, 2, "fingerprint", evidence.fingerprint(), true);
        PackageEvidencePathWriter.writeFingerprintedPaths(json, 2, "inputs", projectRoot, evidence.inputs(), false);
        indent(json, 1).append("}");
    }

    private static void tokenSources(
            StringBuilder json,
            List<PackageResourceEvidence.TokenSource> tokens) {
        indent(json, 2).append("\"tokenSources\": [");
        if (!tokens.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < tokens.size(); index++) {
                PackageResourceEvidence.TokenSource token = tokens.get(index);
                indent(json, 3).append("{\n");
                stringField(json, 4, "name", token.name(), true);
                stringField(json, 4, "source", token.source(), false);
                indent(json, 3).append("}");
                if (index + 1 < tokens.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 2);
        }
        json.append("]");
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
}
