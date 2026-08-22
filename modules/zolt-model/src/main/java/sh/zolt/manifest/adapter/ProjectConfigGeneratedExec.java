package sh.zolt.manifest.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.GeneratedCachePolicy;
import sh.zolt.manifest.GeneratedOutputKind;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
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
 * Projects one {@code [generated.<scope>.<id>]} exec step onto the legacy
 * {@link ExecGenerationSettings} the engine runs (design §13.7).
 *
 * <p>An exec step is the one step kind whose tool is fully user-declared: it names a
 * {@code [generated.tools.<id>]} entry, or the {@code project} pseudo-tool, and carries its own
 * environment, cache policy, and produced lane. Resolving that reference is the whole job here.
 */
final class ProjectConfigGeneratedExec {
    private static final LocalId PROJECT = new LocalId("project");
    private static final String JAVA = "java";

    private ProjectConfigGeneratedExec() {
    }

    static GeneratedSourceStep step(
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
                tool(step, sources, versions),
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

    private static ExecToolSettings tool(
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

    private static ProducesLane produces(GeneratedOutputKind kind) {
        return switch (kind) {
            case JAVA_SOURCES -> ProducesLane.JAVA_SOURCES;
            case TEST_SOURCES -> ProducesLane.TEST_SOURCES;
            case RESOURCES -> ProducesLane.RESOURCES;
            case TEST_RESOURCES -> ProducesLane.TEST_RESOURCES;
            case INTERMEDIATE -> ProducesLane.INTERMEDIATE;
        };
    }
}
