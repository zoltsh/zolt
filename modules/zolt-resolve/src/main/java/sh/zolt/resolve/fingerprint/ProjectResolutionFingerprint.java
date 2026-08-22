package sh.zolt.resolve.fingerprint;

import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyExclusionSpec;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class ProjectResolutionFingerprint {
    /**
     * The fingerprint schema version, itself a fingerprint input so a bump restates every lock.
     *
     * <p>v2 (2026-08) closed two freshness false negatives and unfroze one diagnostic surface. It
     * added the dependency variant — classifier and type — which v1 omitted, letting a manifest
     * switch a coordinate to another published artifact while its lock stayed fresh; it put
     * repositories in effective lookup order rather than id order, so a first-match-wins reorder
     * stales the lock it changes the meaning of; and it replaced {@code GeneratedSourceStep.toString()}
     * with an explicit field encoder so a diagnostic rendering is no longer frozen into checked-in
     * locks. All three land in one restatement: every lock is rewritten once, not once per fix.
     */
    static final String SCHEMA = "v2";

    private ProjectResolutionFingerprint() {
    }

    public static String fingerprint(ProjectConfig config) {
        List<String> inputs = inputs(config);
        String input = String.join("\n", inputs) + "\n";
        return "sha256:" + sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> inputFingerprints(ProjectConfig config) {
        Map<String, List<String>> byCategory = inputs(config).stream()
                .collect(Collectors.groupingBy(
                        ProjectResolutionFingerprint::summaryCategory,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return byCategory.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=sha256:" + sha256((String.join("\n", entry.getValue()) + "\n")
                        .getBytes(StandardCharsets.UTF_8)))
                .toList();
    }

    static List<String> inputs(ProjectConfig config) {
        List<String> inputs = new ArrayList<>();
        line(inputs, "schema", SCHEMA);
        line(inputs, "java", "project", config.project().java());
        line(inputs, "java", "compilerRelease", config.compilerSettings().release());
        repositoryInputs(inputs, config.repositorySettings());
        credentialInputs(inputs, config.repositoryCredentials());
        mapInputs(inputs, "versions", config.versionAliases());
        mapInputs(inputs, "platforms", config.platforms());
        dependencyInputs(inputs, "api", config.apiDependencies(), config.managedApiDependencies());
        mapInputs(inputs, "workspaceApi", config.workspaceApiDependencies());
        dependencyInputs(inputs, "compile", config.dependencies(), config.managedDependencies());
        mapInputs(inputs, "workspaceCompile", config.workspaceDependencies());
        dependencyInputs(inputs, "runtime", config.runtimeDependencies(), config.managedRuntimeDependencies());
        dependencyInputs(inputs, "provided", config.providedDependencies(), config.managedProvidedDependencies());
        dependencyInputs(inputs, "dev", config.devDependencies(), config.managedDevDependencies());
        dependencyInputs(inputs, "test", config.testDependencies(), config.managedTestDependencies());
        mapInputs(inputs, "workspaceTest", config.workspaceTestDependencies());
        dependencyInputs(inputs, "processor", config.annotationProcessors(), config.managedAnnotationProcessors());
        mapInputs(inputs, "workspaceProcessor", config.workspaceAnnotationProcessors());
        dependencyInputs(inputs, "testProcessor", config.testAnnotationProcessors(), config.managedTestAnnotationProcessors());
        mapInputs(inputs, "workspaceTestProcessor", config.workspaceTestAnnotationProcessors());
        dependencyMetadataInputs(inputs, config.dependencyMetadata());
        dependencyPolicyInputs(
                inputs,
                config.dependencyPolicy().exclusions(),
                config.dependencyPolicy().constraints(),
                config.dependencyPolicy().failOnVersionConflict());
        generatedSourceEncodingInput(inputs, config);
        generatedSourceInputs(inputs, "generatedMain", config.build().generatedMainSources());
        generatedSourceInputs(inputs, "generatedTest", config.build().generatedTestSources());
        line(inputs, "package", "mode", resolutionPackageMode(config.packageSettings().mode()));
        inputs.addAll(config.frameworkSettings().resolutionFingerprintInputs());
        return List.copyOf(inputs);
    }

    /**
     * Only Spring Boot archive modes contribute package tooling to dependency resolution. The two
     * tokens are frozen lock identity, not display symbols, so renaming a package mode never
     * invalidates a checked-in lock.
     */
    private static String resolutionPackageMode(PackageMode mode) {
        return mode == PackageMode.SPRING_BOOT || mode == PackageMode.SPRING_BOOT_WAR
                ? "spring-boot"
                : "thin";
    }

    /**
     * Repositories in effective lookup order, each carrying its ordinal.
     *
     * <p>Order is a resolution input, not presentation: fetching is first-match-wins (design §8.5), so
     * a manifest that only reorders {@code [repositories].order} changes which repository serves a
     * coordinate available from more than one. While these lines were sorted by id, such an edit left
     * the fingerprint identical and a checked-in lock stayed "fresh" over artifacts selected under a
     * precedence the manifest no longer declares — a freshness false negative, which no later command
     * is positioned to catch. Credentials below stay sorted by id: they are reached by reference from
     * a repository, never by position.
     *
     * <p>The ordinal mirrors {@code RepositoryConfigurationIdentity}, which keys cache scopes by the
     * same order. They stay separate values — this one is lock identity, that one is a cache key.
     */
    private static void repositoryInputs(List<String> inputs, Map<String, RepositorySettings> repositories) {
        int ordinal = 0;
        for (RepositorySettings repository : repositories.values()) {
            line(inputs,
                    "repository",
                    Integer.toString(ordinal++),
                    repository.id(),
                    repository.url(),
                    repository.credentials().orElse(""));
        }
    }

    private static void credentialInputs(List<String> inputs, Map<String, RepositoryCredentialSettings> credentials) {
        credentials.values().stream()
                .sorted(Comparator.comparing(RepositoryCredentialSettings::id))
                .forEach(credential -> {
                    line(
                            inputs,
                            "repositoryCredential",
                            credential.id(),
                            credential.usernameEnv().orElse(""),
                            credential.passwordEnv().orElse(""));
                    credential.tokenEnv().ifPresent(tokenEnv ->
                            line(inputs, "repositoryCredentialToken", credential.id(), tokenEnv));
                });
    }

    private static void dependencyInputs(
            List<String> inputs,
            String section,
            Map<String, String> dependencies,
            Set<String> managedDependencies) {
        mapInputs(inputs, "dependencies." + section, dependencies);
        managedDependencies.stream()
                .sorted()
                .forEach(coordinate -> line(inputs, "managedDependency", section, coordinate));
    }

    private static void dependencyMetadataInputs(
            List<String> inputs,
            Map<String, DependencyMetadata> metadata) {
        metadata.values().stream()
                .sorted(Comparator
                        .comparing(DependencyMetadata::section)
                        .thenComparing(DependencyMetadata::coordinate))
                .forEach(value -> {
                    line(inputs,
                            "dependencyMetadata",
                            value.section(),
                            value.coordinate(),
                            nullToEmpty(value.version()),
                            nullToEmpty(value.versionRef()),
                            Boolean.toString(value.managed()),
                            nullToEmpty(value.workspace()),
                            Boolean.toString(value.optional()),
                            Boolean.toString(value.publishOnly()));
                    if (!value.defaultVariant()) {
                        line(inputs,
                                "dependencyVariant",
                                value.section(),
                                value.coordinate(),
                                nullToEmpty(value.classifier()),
                                nullToEmpty(value.type()));
                    }
                    value.exclusions().stream()
                            .sorted(Comparator
                                    .comparing(DependencyExclusionSpec::group)
                                    .thenComparing(DependencyExclusionSpec::artifact))
                            .forEach(exclusion -> line(
                                    inputs,
                                    "dependencyMetadata.exclusion",
                                    value.section(),
                                    value.coordinate(),
                                    exclusion.group(),
                                    exclusion.artifact()));
                });
    }

    private static void dependencyPolicyInputs(
            List<String> inputs,
            List<DependencyPolicyExclusion> exclusions,
            Map<String, DependencyConstraint> constraints,
            boolean failOnVersionConflict) {
        if (failOnVersionConflict) {
            line(inputs, "dependencyPolicy.failOnVersionConflict", "true");
        }
        exclusions.stream()
                .sorted(Comparator
                        .comparing(DependencyPolicyExclusion::group)
                        .thenComparing(DependencyPolicyExclusion::artifact)
                        .thenComparing(exclusion -> exclusion.reason().orElse("")))
                .forEach(exclusion -> line(
                        inputs,
                        "dependencyPolicy.exclusion",
                        exclusion.group(),
                        exclusion.artifact(),
                        exclusion.reason().orElse("")));
        constraints.values().stream()
                .sorted(Comparator.comparing(DependencyConstraint::coordinate))
                .forEach(constraint -> line(
                        inputs,
                        "dependencyPolicy.constraint",
                        constraint.coordinate(),
                        constraint.version(),
                        constraint.versionRef().orElse(""),
                        constraint.kind().configValue(),
                        constraint.reason().orElse("")));
    }

    /**
     * Emits the generated-source encoder version once, when the project declares any step at all, so
     * every lock that depends on the encoding records which encoding produced it while projects with
     * no generated sources gain no category.
     */
    private static void generatedSourceEncodingInput(List<String> inputs, ProjectConfig config) {
        if (!config.build().generatedMainSources().isEmpty()
                || !config.build().generatedTestSources().isEmpty()) {
            line(inputs, "generatedSourceEncoding", GeneratedSourceFingerprint.ENCODING);
        }
    }

    private static void generatedSourceInputs(
            List<String> inputs,
            String section,
            List<GeneratedSourceStep> steps) {
        steps.stream()
                .sorted(Comparator.comparing(GeneratedSourceStep::id))
                .forEach(step -> GeneratedSourceFingerprint.encode(inputs, section, step));
    }

    private static void mapInputs(List<String> inputs, String category, Map<String, String> values) {
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> line(inputs, category, entry.getKey(), entry.getValue()));
    }

    private static void line(List<String> inputs, String category, String... values) {
        inputs.add(category + "\t" + String.join("\t", values));
    }

    private static String summaryCategory(String line) {
        String[] parts = line.split("\t", -1);
        String category = parts[0];
        return switch (category) {
            case "repository", "repositoryCredential" -> "repositories";
            case "workspaceApi" -> "dependencies.api.workspace";
            case "workspaceCompile" -> "dependencies.compile.workspace";
            case "workspaceTest" -> "dependencies.test.workspace";
            case "managedDependency" -> parts.length > 1 ? "dependencies." + parts[1] : "dependencies";
            case "dependencyMetadata", "dependencyMetadata.exclusion", "dependencyVariant" ->
                    "dependencyMetadata";
            case "dependencyPolicy.failOnVersionConflict", "dependencyPolicy.exclusion", "dependencyPolicy.constraint" ->
                    "dependencyPolicy";
            case "generatedSourceEncoding", "generatedMain", "generatedTest" -> "generatedSources";
            default -> category;
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
