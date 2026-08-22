package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestResourceFields;

/** Decodes authored resource roots, filters, and token sources without applying defaults. */
final class ManifestResourcesDecoder {
    Optional<AuthoredResources> decode(
            ManifestDecodeIndex index,
            ResourcesPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored resources presence observer is required.");
        Optional<ValidatedManifestField> mainField = index.field(
                FinalManifestResourceFields.RESOURCES_MAIN);
        Optional<ValidatedManifestField> testField = index.field(
                FinalManifestResourceFields.RESOURCES_TEST);
        Optional<ValidatedManifestSection> filterSection = index.section(
                FinalManifestPaths.RESOURCES_FILTER);
        Optional<ValidatedManifestSection> tokensSection = index.section(
                FinalManifestPaths.RESOURCES_TOKENS);
        if (mainField.isEmpty()
                && testField.isEmpty()
                && filterSection.isEmpty()
                && tokensSection.isEmpty()) {
            return Optional.empty();
        }

        AtomicBoolean observed = new AtomicBoolean();
        Consumer<AuthoredResources> presence = resources -> {
            if (observed.compareAndSet(false, true)) {
                observer.present(resources);
            }
        };
        AuthoredResources resources = AuthoredResources.empty();
        if (mainField.isPresent()) {
            ValidatedManifestField field = mainField.orElseThrow();
            AuthoredResources prior = resources;
            ManifestSemanticDiagnostics.construct(
                    field, () -> observe(prior, presence));
            List<ManifestRelativePath> main = roots(field, true);
            ManifestSemanticDiagnostics.requireNonEmptyArray(field, main);
            resources = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredResources(
                            main, prior.test(), prior.filter(), prior.tokens()));
        }
        if (testField.isPresent()) {
            ValidatedManifestField field = testField.orElseThrow();
            AuthoredResources prior = resources;
            ManifestSemanticDiagnostics.construct(
                    field, () -> observe(prior, presence));
            List<ManifestRelativePath> test = roots(field, false);
            ManifestSemanticDiagnostics.requireNonEmptyArray(field, test);
            resources = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredResources(
                            prior.main(), test, prior.filter(), prior.tokens()));
        }
        if (filterSection.isPresent()) {
            AuthoredResources.Filter filter = filter(index, resources, presence);
            AuthoredResources prior = resources;
            resources = ManifestSemanticDiagnostics.construct(
                    filterSection.orElseThrow(),
                    () -> new AuthoredResources(
                            prior.main(),
                            prior.test(),
                            Optional.of(filter),
                            prior.tokens()));
        }
        if (tokensSection.isPresent()) {
            ValidatedManifestSection section = tokensSection.orElseThrow();
            AuthoredResources prior = resources;
            resources = ManifestSemanticDiagnostics.construct(
                    section, () -> observe(prior, presence));
            resources = tokens(index, resources);
        }
        return Optional.of(resources);
    }

    private static List<ManifestRelativePath> roots(
            ValidatedManifestField field,
            boolean main) {
        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<ManifestRelativePath> roots = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            ManifestRelativePath root = ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new ManifestRelativePath(authored.get(index)));
            roots.add(root);
            ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> main
                            ? new AuthoredResources(
                                    roots, List.of(), Optional.empty(), Map.of())
                            : new AuthoredResources(
                                    List.of(), roots, Optional.empty(), Map.of()));
        }
        return List.copyOf(roots);
    }

    private static AuthoredResources.Filter filter(
            ManifestDecodeIndex index,
            AuthoredResources base,
            Consumer<AuthoredResources> presence) {
        Optional<ValidatedManifestField> targetsField = index.field(
                FinalManifestResourceFields.RESOURCES_FILTER_TARGETS);
        Optional<List<AuthoredResources.Target>> targets = targetsField.map(
                ManifestResourcesDecoder::targets);
        ValidatedManifestField includeField = ManifestSemanticDiagnostics.requiredField(
                index, FinalManifestResourceFields.RESOURCES_FILTER_INCLUDE);
        List<ResourceGlob> include = include(
                includeField, targetsField, targets, base, presence);
        AuthoredResources.Filter filter = ManifestSemanticDiagnostics.construct(
                includeField,
                () -> new AuthoredResources.Filter(
                        Optional.empty(), include, Optional.empty()));

        if (targetsField.isPresent()) {
            ValidatedManifestField field = targetsField.orElseThrow();
            Optional<List<AuthoredResources.Target>> authoredTargets = targets;
            filter = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredResources.Filter(
                            authoredTargets, include, Optional.empty()));
        }

        Optional<ValidatedManifestField> missingField = index.field(
                FinalManifestResourceFields.RESOURCES_FILTER_MISSING);
        if (missingField.isPresent()) {
            ValidatedManifestField field = missingField.orElseThrow();
            Optional<AuthoredResources.MissingTokenPolicy> missing = Optional.of(
                    missing(field));
            AuthoredResources.Filter prior = filter;
            filter = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredResources.Filter(
                            prior.targets(), prior.include(), missing));
        }
        return filter;
    }

    private static List<ResourceGlob> include(
            ValidatedManifestField field,
            Optional<ValidatedManifestField> targetsField,
            Optional<List<AuthoredResources.Target>> targets,
            AuthoredResources base,
            Consumer<AuthoredResources> presence) {
        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<ResourceGlob> include = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            ResourceGlob glob = ManifestSemanticDiagnostics.construct(
                    field, index, () -> new ResourceGlob(authored.get(index)));
            include.add(glob);
            AuthoredResources.Filter filter = ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new AuthoredResources.Filter(
                            targets, include, Optional.empty()));
            ValidatedManifestField anchor = targetsField.orElse(field);
            int anchorIndex = targetsField.isPresent() ? 0 : index;
            ManifestSemanticDiagnostics.construct(
                    anchor,
                    anchorIndex,
                    () -> observe(
                            new AuthoredResources(
                                    base.main(),
                                    base.test(),
                                    Optional.of(filter),
                                    base.tokens()),
                            presence));
        }
        return List.copyOf(include);
    }

    private static List<AuthoredResources.Target> targets(
            ValidatedManifestField field) {
        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<AuthoredResources.Target> targets = new ArrayList<>(authored.size());
        List<ResourceGlob> validationInclude = List.of(new ResourceGlob("*"));
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            targets.add(ManifestAuthoredSymbols.authored(
                    field,
                    authored.get(index),
                    AuthoredResources.Target.values(),
                    AuthoredResources.Target::configValue));
            ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new AuthoredResources.Filter(
                            Optional.of(targets), validationInclude, Optional.empty()));
        }
        if (targets.isEmpty()) {
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredResources.Filter(
                            Optional.of(List.of()), validationInclude, Optional.empty()));
        }
        return List.copyOf(targets);
    }

    private static AuthoredResources.MissingTokenPolicy missing(
            ValidatedManifestField field) {
        return ManifestAuthoredSymbols.authored(
                field,
                ManifestTomlValues.string(field),
                AuthoredResources.MissingTokenPolicy.values(),
                AuthoredResources.MissingTokenPolicy::configValue);
    }

    private static AuthoredResources tokens(
            ManifestDecodeIndex index,
            AuthoredResources base) {
        LinkedHashMap<LocalId, AuthoredResources.Token> tokens = new LinkedHashMap<>();
        AuthoredResources resources = base;
        for (ManifestDecodeIndex.Entry entry :
                index.entries(FinalManifestResourceFields.RESOURCES_TOKENS_ENTRY)) {
            ValidatedManifestField field = entry.field();
            LocalId id = ManifestSemanticDiagnostics.construct(
                    field, () -> new LocalId(entry.key()));
            ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
            AuthoredResources.Token token = token(table);
            if (tokens.put(id, token) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate resource token `" + id + "`.");
            }
            AuthoredResources prior = resources;
            resources = token instanceof AuthoredResources.Token.Environment
                    ? ManifestSemanticDiagnostics.construct(
                            table,
                            FinalManifestObjectShapes.RESOURCE_TOKEN_ENV,
                            () -> withTokens(prior, tokens))
                    : ManifestSemanticDiagnostics.construct(
                            field, () -> withTokens(prior, tokens));
        }
        return resources;
    }

    private static AuthoredResources.Token token(ManifestInlineTable table) {
        Optional<String> project = table.optionalString(
                FinalManifestObjectShapes.RESOURCE_TOKEN_PROJECT);
        if (project.isPresent()) {
            String value = project.orElseThrow();
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.RESOURCE_TOKEN_PROJECT,
                    () -> new AuthoredResources.Token.Project(projectField(value)));
        }
        Optional<String> environment = table.optionalString(
                FinalManifestObjectShapes.RESOURCE_TOKEN_ENV);
        if (environment.isPresent()) {
            String value = environment.orElseThrow();
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.RESOURCE_TOKEN_ENV,
                    () -> new AuthoredResources.Token.Environment(
                            new EnvironmentVariableName(value)));
        }
        String value = table.requiredString(FinalManifestObjectShapes.RESOURCE_TOKEN_VALUE);
        return ManifestSemanticDiagnostics.construct(
                table,
                FinalManifestObjectShapes.RESOURCE_TOKEN_VALUE,
                () -> new AuthoredResources.Token.Literal(value));
    }

    private static AuthoredResources.ProjectField projectField(String value) {
        return Arrays.stream(AuthoredResources.ProjectField.values())
                .filter(field -> field.configValue().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported resource token project field `" + value + "`."));
    }

    @FunctionalInterface
    interface ResourcesPresenceObserver {
        void present(AuthoredResources resources);
    }

    private static AuthoredResources observe(
            AuthoredResources resources,
            Consumer<AuthoredResources> presence) {
        presence.accept(resources);
        return resources;
    }

    private static AuthoredResources withTokens(
            AuthoredResources prior,
            Map<LocalId, AuthoredResources.Token> tokens) {
        return new AuthoredResources(
                prior.main(), prior.test(), prior.filter(), tokens);
    }
}
