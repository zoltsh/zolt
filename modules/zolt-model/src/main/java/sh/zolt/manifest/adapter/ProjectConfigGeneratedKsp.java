package sh.zolt.manifest.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;

/** Resolves one KSP step's named tool into the exact project settings consumed downstream. */
final class ProjectConfigGeneratedKsp {
    private static final LocalId KSP = new LocalId("ksp");

    private ProjectConfigGeneratedKsp() {
    }

    static KspGenerationSettings settings(
            Optional<LocalId> toolReference,
            AuthoredGeneratedSources sources,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            Map<String, String> options) {
        LocalId tool = toolReference.orElse(KSP);
        AuthoredGeneratedTool declaration = sources.tools().declarations().get(tool);
        if (!(declaration instanceof AuthoredGeneratedTool.Ksp ksp)) {
            throw new IllegalArgumentException(
                    "Generated KSP step requires `" + tool + "` to be a declared KSP tool.");
        }
        String subject = "[generated.tools." + tool + "]";
        return new KspGenerationSettings(
                tool.value(),
                Optional.of(ProjectConfigVersions.resolve(ksp.version(), versions, subject)),
                Optional.ofNullable(ProjectConfigVersions.reference(ksp.version())),
                processors(ksp.processors(), versions, subject),
                options);
    }

    private static List<KspProcessorSettings> processors(
            List<GeneratedArtifactRequest> requests,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            String subject) {
        List<KspProcessorSettings> processors = new ArrayList<>(requests.size());
        for (GeneratedArtifactRequest request : requests) {
            DependencySelector selector = request.selector();
            processors.add(new KspProcessorSettings(
                    request.coordinate().value(),
                    ProjectConfigVersions.resolve(selector, versions, subject),
                    Optional.ofNullable(ProjectConfigVersions.reference(selector))));
        }
        return List.copyOf(processors);
    }
}
