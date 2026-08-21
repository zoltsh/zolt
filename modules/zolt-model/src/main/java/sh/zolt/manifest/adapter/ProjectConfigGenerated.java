package sh.zolt.manifest.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.GeneratedCachePolicy;
import sh.zolt.manifest.GeneratedOutputKind;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredDeclaredRootStep;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedStep;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;
import sh.zolt.manifest.authored.AuthoredOpenApiStep;
import sh.zolt.manifest.authored.AuthoredProtobufStep;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProtobufGenerationSettings;

/**
 * Projects the final {@code [generated.tools]}, {@code [generated.presets]}, {@code [generated.main]},
 * and {@code [generated.test]} domains onto the legacy {@link GeneratedSourceStep} list.
 *
 * <p>The final language replaced the single global {@code [generated.openapiTool]},
 * {@code [generated.protobufTool]}, and {@code [generated.execTools]} registries with one named
 * {@code [generated.tools.<id>]} namespace (design §13.2), so the adapter resolves each step's tool
 * reference and folds the declaration into the per-step legacy settings the engine expects.
 */
final class ProjectConfigGenerated {
    private static final LocalId OPENAPI = new LocalId("openapi");
    private static final LocalId PROTOBUF = new LocalId("protobuf");
    private static final LocalId PROJECT = new LocalId("project");
    private static final String JAVA = "java";
    private static final String MAIN_OUTPUT_PREFIX = "generated/sources/";
    private static final String TEST_OUTPUT_PREFIX = "generated/test-sources/";

    private ProjectConfigGenerated() {
    }

    static List<GeneratedSourceStep> main(
            Optional<AuthoredGeneratedSources> generated,
            String outputRoot,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        return steps(generated, outputRoot, versions, true);
    }

    static List<GeneratedSourceStep> test(
            Optional<AuthoredGeneratedSources> generated,
            String outputRoot,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        return steps(generated, outputRoot, versions, false);
    }

    private static List<GeneratedSourceStep> steps(
            Optional<AuthoredGeneratedSources> generated,
            String outputRoot,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            boolean mainScope) {
        if (generated.isEmpty()) {
            return List.of();
        }
        AuthoredGeneratedSources sources = generated.orElseThrow();
        Map<LocalId, AuthoredGeneratedStep> scope = mainScope ? sources.main() : sources.test();
        List<GeneratedSourceStep> steps = new ArrayList<>(scope.size());
        scope.forEach((id, step) ->
                steps.add(step(id, step, sources, outputRoot, versions, mainScope)));
        return List.copyOf(steps);
    }

    private static GeneratedSourceStep step(
            LocalId id,
            AuthoredGeneratedStep step,
            AuthoredGeneratedSources sources,
            String outputRoot,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            boolean mainScope) {
        return switch (step) {
            case AuthoredOpenApiStep openApi ->
                    openApi(id, openApi, sources, outputRoot, versions, mainScope);
            case AuthoredProtobufStep protobuf ->
                    protobuf(id, protobuf, sources, outputRoot, versions, mainScope);
            case AuthoredExecStep exec -> exec(id, exec, sources, versions);
            case AuthoredDeclaredRootStep declaredRoot -> declaredRoot(id, declaredRoot);
        };
    }

    private static GeneratedSourceStep openApi(
            LocalId id,
            AuthoredOpenApiStep step,
            AuthoredGeneratedSources sources,
            String outputRoot,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            boolean mainScope) {
        LocalId tool = step.tool().orElse(OPENAPI);
        AuthoredOpenApiOptions preset = step.preset()
                .map(sources.presets().openApi()::get)
                .orElseGet(AuthoredOpenApiOptions::empty);
        return new GeneratedSourceStep(
                id.value(),
                GeneratedSourceKind.OPENAPI,
                JAVA,
                derivedOutput(id, step.output(), outputRoot, mainScope),
                List.of(step.input().value()),
                step.settings().required().orElse(true),
                step.settings().clean().orElse(true),
                openApiSettings(
                        openApiTool(sources, tool),
                        step.preset().map(LocalId::value),
                        preset,
                        step.overrides(),
                        versions,
                        id));
    }

    private static GeneratedSourceStep protobuf(
            LocalId id,
            AuthoredProtobufStep step,
            AuthoredGeneratedSources sources,
            String outputRoot,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            boolean mainScope) {
        LocalId tool = step.tool().orElse(PROTOBUF);
        Optional<AuthoredGeneratedTool.Protobuf> declaration =
                declaration(sources, tool, AuthoredGeneratedTool.Protobuf.class);
        String subject = "[generated] tool `" + tool + "`";
        return new GeneratedSourceStep(
                id.value(),
                GeneratedSourceKind.PROTOBUF,
                JAVA,
                derivedOutput(id, step.output(), outputRoot, mainScope),
                step.inputs().stream().map(ResourceGlob::value).toList(),
                step.settings().required().orElse(true),
                step.settings().clean().orElse(true),
                OpenApiGenerationSettings.empty(),
                new ProtobufGenerationSettings(
                        declaration.flatMap(AuthoredGeneratedTool.Protobuf::protocCoordinate)
                                .map(coordinate -> coordinate.value()),
                        declaration.flatMap(AuthoredGeneratedTool.Protobuf::protocVersion)
                                .map(selector -> ProjectConfigVersions.resolve(selector, versions, subject)),
                        declaration.flatMap(AuthoredGeneratedTool.Protobuf::protocVersion)
                                .map(ProjectConfigVersions::reference),
                        declaration.flatMap(AuthoredGeneratedTool.Protobuf::grpcCoordinate)
                                .map(coordinate -> coordinate.value()),
                        declaration.flatMap(AuthoredGeneratedTool.Protobuf::grpcVersion)
                                .map(selector -> ProjectConfigVersions.resolve(selector, versions, subject)),
                        declaration.flatMap(AuthoredGeneratedTool.Protobuf::grpcVersion)
                                .map(ProjectConfigVersions::reference),
                        step.javaPackage(),
                        step.grpc().orElse(true)));
    }

    private static GeneratedSourceStep exec(
            LocalId id,
            AuthoredExecStep step,
            AuthoredGeneratedSources sources,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        Map<String, String> environment = new LinkedHashMap<>();
        step.env().forEach((name, value) -> environment.put(name.value(), value));
        Map<String, String> secretEnvironment = new LinkedHashMap<>();
        step.secretEnv().forEach((target, source) ->
                secretEnvironment.put(target.value(), source.value()));
        ExecGenerationSettings settings = new ExecGenerationSettings(
                step.tool().value(),
                execTool(step, sources, versions),
                step.args(),
                produces(step.produces()),
                step.into().map(ManifestRelativePath::value),
                environment,
                step.cache().orElse(GeneratedCachePolicy.CONTENT).configValue(),
                step.cwd().map(ManifestRelativePath::value),
                secretEnvironment,
                step.inheritEnv().stream().map(name -> name.value()).toList(),
                step.timeoutSeconds().orElse(ExecGenerationSettings.DEFAULT_TIMEOUT_SECONDS),
                Optional.empty());
        return new GeneratedSourceStep(
                id.value(),
                GeneratedSourceKind.EXEC,
                JAVA,
                step.output().value(),
                step.inputs().stream().map(ResourceGlob::value).toList(),
                step.settings().required().orElse(true),
                step.settings().clean().orElse(true),
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                settings);
    }

    private static GeneratedSourceStep declaredRoot(LocalId id, AuthoredDeclaredRootStep step) {
        GeneratedStepSettings settings = step.settings();
        return new GeneratedSourceStep(
                id.value(),
                GeneratedSourceKind.DECLARED_ROOT,
                JAVA,
                step.output().value(),
                step.inputs().stream().map(ResourceGlob::value).toList(),
                settings.required().orElse(true),
                settings.clean().orElse(false));
    }

    private static ExecToolSettings execTool(
            AuthoredExecStep step,
            AuthoredGeneratedSources sources,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        LocalId tool = step.tool();
        if (PROJECT.equals(tool)) {
            return ExecToolSettings.project(step.mainClass().orElseThrow().value());
        }
        AuthoredGeneratedTool declaration = sources.tools().declarations().get(tool);
        if (declaration == null) {
            throw new IllegalArgumentException("Generated exec step references undefined tool `" + tool + "`.");
        }
        return switch (declaration) {
            case AuthoredGeneratedTool.Jvm jvm -> new ExecToolSettings(
                    "jvm",
                    coordinates(jvm.coordinates(), versions, "[generated.tools." + tool + "]"),
                    jvm.mainClass().value());
            case AuthoredGeneratedTool.Process process -> ExecToolSettings.process(
                    process.binary().value(),
                    process.versionCommand(),
                    process.versionExpect().map(expectation -> expectation.value()),
                    process.allowUnpinnedTool());
            case AuthoredGeneratedTool.OpenApi ignored -> throw new IllegalArgumentException(
                    "Generated exec step cannot reference OpenAPI tool `" + tool + "`.");
            case AuthoredGeneratedTool.Protobuf ignored -> throw new IllegalArgumentException(
                    "Generated exec step cannot reference Protobuf tool `" + tool + "`.");
        };
    }

    private static List<ExecToolCoordinate> coordinates(
            List<GeneratedArtifactRequest> requests,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            String subject) {
        List<ExecToolCoordinate> coordinates = new ArrayList<>(requests.size());
        for (GeneratedArtifactRequest request : requests) {
            DependencySelector selector = request.selector();
            coordinates.add(new ExecToolCoordinate(
                    request.coordinate().value(),
                    Optional.of(ProjectConfigVersions.resolve(selector, versions, subject)),
                    Optional.ofNullable(ProjectConfigVersions.reference(selector))));
        }
        return List.copyOf(coordinates);
    }

    private static OpenApiGenerationSettings openApiSettings(
            Optional<AuthoredGeneratedTool.OpenApi> tool,
            Optional<String> presetId,
            AuthoredOpenApiOptions preset,
            AuthoredOpenApiOptions overrides,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            LocalId step) {
        String subject = "[generated] OpenAPI tool for step `" + step + "`";
        return new OpenApiGenerationSettings(
                tool.flatMap(AuthoredGeneratedTool.OpenApi::coordinate)
                        .map(coordinate -> coordinate.value()),
                tool.flatMap(AuthoredGeneratedTool.OpenApi::version)
                        .map(selector -> ProjectConfigVersions.resolve(selector, versions, subject)),
                tool.flatMap(AuthoredGeneratedTool.OpenApi::version)
                        .map(ProjectConfigVersions::reference),
                presetId,
                overrides.generator().or(preset::generator),
                overrides.library().or(preset::library),
                overrides.apiPackage().or(preset::apiPackage),
                overrides.modelPackage().or(preset::modelPackage),
                overrides.invokerPackage().or(preset::invokerPackage),
                overrides.config().or(preset::config).map(ManifestRelativePath::value),
                overrides.templateDir().or(preset::templateDir).map(ManifestRelativePath::value),
                overrides.validateSpec().or(preset::validateSpec),
                merged(preset.options(), overrides.options()),
                merged(preset.additionalProperties(), overrides.additionalProperties()),
                merged(preset.configOptions(), overrides.configOptions()),
                merged(preset.globalProperties(), overrides.globalProperties()),
                merged(preset.typeMappings(), overrides.typeMappings()),
                merged(preset.importMappings(), overrides.importMappings()));
    }

    private static Optional<AuthoredGeneratedTool.OpenApi> openApiTool(
            AuthoredGeneratedSources sources,
            LocalId tool) {
        return declaration(sources, tool, AuthoredGeneratedTool.OpenApi.class);
    }

    private static <T extends AuthoredGeneratedTool> Optional<T> declaration(
            AuthoredGeneratedSources sources,
            LocalId tool,
            Class<T> kind) {
        AuthoredGeneratedTool declaration = sources.tools().declarations().get(tool);
        return kind.isInstance(declaration) ? Optional.of(kind.cast(declaration)) : Optional.empty();
    }

    private static Map<String, String> merged(Map<String, String> preset, Map<String, String> step) {
        Map<String, String> merged = new TreeMap<>(preset);
        merged.putAll(step);
        return Map.copyOf(merged);
    }

    private static ProducesLane produces(GeneratedOutputKind kind) {
        return switch (kind) {
            case JAVA_SOURCES -> ProducesLane.JAVA_SOURCES;
            case TEST_SOURCES -> ProducesLane.TEST_SOURCES;
            case RESOURCES -> ProducesLane.RESOURCES;
            case TEST_RESOURCES -> ProducesLane.TEST_RESOURCES;
            case INTERMEDIATE -> ProducesLane.INTERMEDIATE;
        };
    }

    private static String derivedOutput(
            LocalId id,
            Optional<ManifestRelativePath> output,
            String outputRoot,
            boolean mainScope) {
        return output.map(ManifestRelativePath::value)
                .orElseGet(() -> outputRoot
                        + "/"
                        + (mainScope ? MAIN_OUTPUT_PREFIX : TEST_OUTPUT_PREFIX)
                        + id.value());
    }
}
