package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.SourceRootLanguage;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.schema.FinalManifestBuildFields;
import sh.zolt.toml.schema.FinalManifestCoverageFields;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Composes authored build and generated-source domains in canonical schema order. */
final class ManifestBuildConfigurationDecoder {
    private final ManifestBuildDecoder buildDecoder = new ManifestBuildDecoder();
    private final ManifestCompilerDecoder compilerDecoder = new ManifestCompilerDecoder();
    private final ManifestResourcesDecoder resourcesDecoder = new ManifestResourcesDecoder();
    private final ManifestGeneratedSourcesDecoder generatedDecoder =
            new ManifestGeneratedSourcesDecoder();
    private final ManifestTestsDecoder testsDecoder = new ManifestTestsDecoder();
    private final ManifestCoverageDecoder coverageDecoder = new ManifestCoverageDecoder();

    Decoded decode(
            ManifestDecodeIndex index,
            BuildDomainObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored build domain observer is required.");
        Optional<AuthoredBuild> build = buildDecoder.decode(index, partial ->
                observer.present(snapshot(
                        Optional.of(partial), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty())));
        Optional<AuthoredCompiler> compiler = compilerDecoder.decode(index, partial ->
                observer.present(snapshot(
                        build, Optional.of(partial), Optional.empty(),
                        Optional.empty(), Optional.empty())));
        Optional<AuthoredResources> resources = resourcesDecoder.decode(index, partial ->
                observer.present(snapshot(
                        build, compiler, Optional.of(partial),
                        Optional.empty(), Optional.empty())));
        Optional<AuthoredGeneratedSources> generated = generatedDecoder.decode(index, partial ->
                observer.present(snapshot(
                        build, compiler, resources, Optional.empty(), Optional.of(partial))));
        Optional<AuthoredTests> tests = testsDecoder.decode(index, partial -> observer.present(
                snapshot(build, compiler, resources, Optional.of(partial), generated)));
        Optional<AuthoredCoverage> coverage = coverageDecoder.decode(index);
        return new Decoded(
                new AuthoredBuildConfiguration(build, compiler, resources, tests, coverage),
                generated);
    }

    private static Decoded snapshot(
            Optional<AuthoredBuild> build,
            Optional<AuthoredCompiler> compiler,
            Optional<AuthoredResources> resources,
            Optional<AuthoredTests> tests,
            Optional<AuthoredGeneratedSources> generated) {
        return new Decoded(new AuthoredBuildConfiguration(
                build, compiler, resources, tests, Optional.empty()), generated);
    }

    @FunctionalInterface
    interface BuildDomainObserver {
        void present(Decoded domains);
    }

    record Decoded(
            AuthoredBuildConfiguration build,
            Optional<AuthoredGeneratedSources> generated) {
        Decoded {
            Objects.requireNonNull(build, "Decoded build configuration must not be null.");
            generated = Objects.requireNonNull(
                    generated, "Decoded generated sources must not be null.");
        }
    }
}

/** Decodes authored build roots, output paths, and metadata without applying defaults. */
final class ManifestBuildDecoder {
    Optional<AuthoredBuild> decode(
            ManifestDecodeIndex index,
            BuildPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored build presence observer is required.");
        Optional<ValidatedManifestField> sourcesField = index.field(
                FinalManifestBuildFields.BUILD_SOURCES);
        Presence presence = new Presence(sourcesField, observer);
        List<ManifestRelativePath> sources = sourcesField
                .map(field -> paths(field, presence))
                .orElseGet(List::of);
        Optional<AuthoredBuild.Output> output = output(index, sources, presence);
        Optional<AuthoredBuild.Metadata> metadata = metadata(index, sources, presence);
        if (sourcesField.isEmpty() && output.isEmpty() && metadata.isEmpty()) {
            return Optional.empty();
        }
        sourcesField.ifPresent(field ->
                ManifestSemanticDiagnostics.requireNonEmptyArray(field, sources));
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(
                                FinalManifestPaths.BUILD,
                                FinalManifestPaths.BUILD_OUTPUT,
                                FinalManifestPaths.BUILD_METADATA)
                        .orElseThrow(() -> new IllegalStateException(
                                "Authored build aggregate has no direct field evidence.")),
                () -> new AuthoredBuild(sources, output, metadata)));
    }

    private static Optional<AuthoredBuild.Output> output(
            ManifestDecodeIndex index,
            List<ManifestRelativePath> sources,
            Presence presence) {
        Optional<ValidatedManifestField> rootField = index.field(
                FinalManifestBuildFields.BUILD_OUTPUT_ROOT);
        Optional<ManifestRelativePath> root = path(rootField);
        rootField.ifPresent(field -> presence.output(
                field,
                sources,
                root,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
        Optional<ValidatedManifestField> mainField = index.field(
                FinalManifestBuildFields.BUILD_OUTPUT_MAIN);
        Optional<ManifestRelativePath> main = path(mainField);
        mainField.ifPresent(field -> presence.output(
                field,
                sources,
                root,
                main,
                Optional.empty(),
                Optional.empty()));
        Optional<ValidatedManifestField> testField = index.field(
                FinalManifestBuildFields.BUILD_OUTPUT_TEST);
        Optional<ManifestRelativePath> test = path(testField);
        testField.ifPresent(field -> presence.output(
                field, sources, root, main, test, Optional.empty()));
        Optional<ValidatedManifestField> integrationField = index.field(
                FinalManifestBuildFields.BUILD_OUTPUT_INTEGRATION);
        Optional<ManifestRelativePath> integration = path(integrationField);
        integrationField.ifPresent(field -> presence.output(
                field, sources, root, main, test, integration));
        if (root.isEmpty() && main.isEmpty() && test.isEmpty() && integration.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(FinalManifestPaths.BUILD_OUTPUT)
                        .orElseThrow(() -> new IllegalStateException(
                                "Authored build aggregate has no direct field evidence.")),
                () -> new AuthoredBuild.Output(root, main, test, integration)));
    }

    private static Optional<AuthoredBuild.Metadata> metadata(
            ManifestDecodeIndex index,
            List<ManifestRelativePath> sources,
            Presence presence) {
        Optional<ValidatedManifestField> buildInfoField = index.field(
                FinalManifestBuildFields.BUILD_METADATA_BUILD_INFO);
        Optional<Boolean> buildInfo = buildInfoField.map(ManifestTomlValues::booleanValue);
        buildInfoField.ifPresent(field -> presence.metadata(
                field, sources, buildInfo, Optional.empty(), Optional.empty()));
        Optional<ValidatedManifestField> gitField = index.field(
                FinalManifestBuildFields.BUILD_METADATA_GIT);
        Optional<Boolean> git = gitField.map(ManifestTomlValues::booleanValue);
        gitField.ifPresent(field -> presence.metadata(
                field, sources, buildInfo, git, Optional.empty()));
        Optional<ValidatedManifestField> reproducibleField = index.field(
                FinalManifestBuildFields.BUILD_METADATA_REPRODUCIBLE);
        Optional<Boolean> reproducible = reproducibleField.map(ManifestTomlValues::booleanValue);
        reproducibleField.ifPresent(field -> presence.metadata(
                field, sources, buildInfo, git, reproducible));
        if (buildInfo.isEmpty() && git.isEmpty() && reproducible.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(FinalManifestPaths.BUILD_METADATA)
                        .orElseThrow(() -> new IllegalStateException(
                                "Authored build aggregate has no direct field evidence.")),
                () -> new AuthoredBuild.Metadata(buildInfo, git, reproducible)));
    }

    private static List<ManifestRelativePath> paths(
            ValidatedManifestField field,
            Presence presence) {
        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<ManifestRelativePath> paths = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            paths.add(ManifestSemanticDiagnostics.construct(
                    field,
                    () -> SourceRootLanguage.requireSupported(
                            new ManifestRelativePath(authored.get(index)))));
            presence.sources(field, paths);
        }
        return List.copyOf(paths);
    }

    private static Optional<ManifestRelativePath> path(
            Optional<ValidatedManifestField> field) {
        return field.map(value -> ManifestSemanticDiagnostics.construct(
                value,
                () -> new ManifestRelativePath(ManifestTomlValues.string(value))));
    }

    private static final class Presence {
        private final Optional<ValidatedManifestField> sourcesField;
        private final BuildPresenceObserver observer;
        private boolean observed;

        private Presence(
                Optional<ValidatedManifestField> sourcesField,
                BuildPresenceObserver observer) {
            this.sourcesField = sourcesField;
            this.observer = observer;
        }

        private void sources(
                ValidatedManifestField field,
                List<ManifestRelativePath> sources) {
            ManifestSemanticDiagnostics.construct(
                field,
                () -> observe(new AuthoredBuild(
                        sources, Optional.empty(), Optional.empty())));
        }

        private void output(
                ValidatedManifestField field,
                List<ManifestRelativePath> sources,
                Optional<ManifestRelativePath> root,
                Optional<ManifestRelativePath> main,
                Optional<ManifestRelativePath> test,
                Optional<ManifestRelativePath> integration) {
            if (observed) {
                return;
            }
            AuthoredBuild.Output output = ManifestSemanticDiagnostics.construct(
                    field, () -> new AuthoredBuild.Output(root, main, test, integration));
            ManifestSemanticDiagnostics.construct(
                    sourcesField.orElse(field),
                    () -> observe(new AuthoredBuild(
                            sources, Optional.of(output), Optional.empty())));
        }

        private void metadata(
                ValidatedManifestField field,
                List<ManifestRelativePath> sources,
                Optional<Boolean> buildInfo,
                Optional<Boolean> git,
                Optional<Boolean> reproducible) {
            if (observed) {
                return;
            }
            AuthoredBuild.Metadata metadata = ManifestSemanticDiagnostics.construct(
                    field, () -> new AuthoredBuild.Metadata(buildInfo, git, reproducible));
            ManifestSemanticDiagnostics.construct(
                    sourcesField.orElse(field),
                    () -> observe(new AuthoredBuild(
                            sources, Optional.empty(), Optional.of(metadata))));
        }

        private AuthoredBuild observe(AuthoredBuild build) {
            if (!observed) {
                observer.present(build);
                observed = true;
            }
            return build;
        }
    }

    @FunctionalInterface
    interface BuildPresenceObserver {
        void present(AuthoredBuild build);
    }

}

/** Decodes authored coverage floors without applying workspace minimums. */
final class ManifestCoverageDecoder {
    Optional<AuthoredCoverage> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> lineField =
                index.field(FinalManifestCoverageFields.COVERAGE_LINE);
        Optional<ValidatedManifestField> branchField =
                index.field(FinalManifestCoverageFields.COVERAGE_BRANCH);
        Optional<ValidatedManifestField> instructionField =
                index.field(FinalManifestCoverageFields.COVERAGE_INSTRUCTION);
        Optional<ValidatedManifestField> methodField =
                index.field(FinalManifestCoverageFields.COVERAGE_METHOD);
        if (lineField.isEmpty()
                && branchField.isEmpty()
                && instructionField.isEmpty()
                && methodField.isEmpty()) {
            return Optional.empty();
        }

        Optional<CoveragePercentage> line = floor(lineField);
        Optional<CoveragePercentage> branch = floor(branchField);
        Optional<CoveragePercentage> instruction = floor(instructionField);
        Optional<CoveragePercentage> method = floor(methodField);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                index.firstDirectField(FinalManifestPaths.COVERAGE).orElseThrow(),
                () -> new AuthoredCoverage(line, branch, instruction, method)));
    }

    private static Optional<CoveragePercentage> floor(
            Optional<ValidatedManifestField> field) {
        return field.map(value -> ManifestSemanticDiagnostics.construct(
                value,
                () -> new CoveragePercentage(ManifestTomlValues.number(value))));
    }
}
