package sh.zolt.toml.manifest.write;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestResourceFields;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits authored resource roots, filtering, and typed token sources without defaults. */
final class ManifestResourcesWriter {
    private static final String CONVENTIONAL_MAIN = "src/main/resources";
    private static final String CONVENTIONAL_TEST = "src/test/resources";
    private static final ManifestSection RESOURCES = section(FinalManifestPaths.RESOURCES);
    private static final ManifestSection FILTER = section(FinalManifestPaths.RESOURCES_FILTER);
    private static final ManifestSection TOKENS = section(FinalManifestPaths.RESOURCES_TOKENS);

    void write(ManifestTomlEmitter emitter, Optional<AuthoredResources> resources) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        Objects.requireNonNull(resources, "Authored resources are required.")
                .ifPresent(value -> writeResources(emitter, value));
    }

    private static void writeResources(
            ManifestTomlEmitter emitter, AuthoredResources resources) {
        emitter.section(RESOURCES);
        if (!resources.main().isEmpty() && !isConventional(resources.main(), CONVENTIONAL_MAIN)) {
            emitter.field(
                    FinalManifestResourceFields.RESOURCES_MAIN,
                    paths(resources.main()));
        }
        if (!resources.test().isEmpty() && !isConventional(resources.test(), CONVENTIONAL_TEST)) {
            emitter.field(
                    FinalManifestResourceFields.RESOURCES_TEST,
                    paths(resources.test()));
        }
        resources.filter().ifPresent(filter -> writeFilter(emitter, filter));
        if (!resources.tokens().isEmpty()) {
            writeTokens(emitter, resources.tokens());
        }
    }

    private static void writeFilter(
            ManifestTomlEmitter emitter, AuthoredResources.Filter filter) {
        emitter.section(FILTER);
        filter.targets()
                .filter(targets -> !targets.equals(List.of(AuthoredResources.Target.MAIN)))
                .ifPresent(targets -> emitter.field(
                        FinalManifestResourceFields.RESOURCES_FILTER_TARGETS,
                        strings(targets.stream()
                                .map(AuthoredResources.Target::configValue)
                                .toList())));
        emitter.field(
                FinalManifestResourceFields.RESOURCES_FILTER_INCLUDE,
                strings(filter.include().stream().map(value -> value.value()).toList()));
        filter.missing()
                .filter(value -> value != AuthoredResources.MissingTokenPolicy.FAIL)
                .ifPresent(value -> emitter.field(
                        FinalManifestResourceFields.RESOURCES_FILTER_MISSING,
                        string(value.configValue())));
    }

    private static void writeTokens(
            ManifestTomlEmitter emitter, Map<LocalId, AuthoredResources.Token> tokens) {
        emitter.section(TOKENS);
        for (Map.Entry<LocalId, AuthoredResources.Token> entry : tokens.entrySet()) {
            emitter.dynamicField(
                    FinalManifestResourceFields.RESOURCES_TOKENS_ENTRY,
                    entry.getKey().value(),
                    token(entry.getValue()));
        }
    }

    private static String token(AuthoredResources.Token token) {
        ManifestTomlValueEncoder.InlineMember member = switch (token) {
            case AuthoredResources.Token.Project project -> ManifestTomlValueEncoder.member(
                    FinalManifestObjectShapes.RESOURCE_TOKEN_PROJECT.name(),
                    string(project.field().configValue()));
            case AuthoredResources.Token.Environment environment -> ManifestTomlValueEncoder.member(
                    FinalManifestObjectShapes.RESOURCE_TOKEN_ENV.name(),
                    string(environment.env().value()));
            case AuthoredResources.Token.Literal literal -> ManifestTomlValueEncoder.member(
                    FinalManifestObjectShapes.RESOURCE_TOKEN_VALUE.name(),
                    string(literal.value()));
        };
        return ManifestTomlValueEncoder.inlineObject(List.of(member));
    }

    private static boolean isConventional(
            List<ManifestRelativePath> paths, String conventional) {
        return paths.size() == 1 && paths.getFirst().value().equals(conventional);
    }

    private static String paths(List<ManifestRelativePath> paths) {
        return strings(paths.stream().map(ManifestRelativePath::value).toList());
    }

    private static String strings(List<String> values) {
        return ManifestTomlValueEncoder.array(values.stream()
                .map(ManifestResourcesWriter::string)
                .toList());
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}
