package sh.zolt.toml.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.schema.FinalManifestBuildFields;
import sh.zolt.toml.schema.FinalManifestCoverageFields;
import sh.zolt.toml.schema.ManifestField;

/** Composes authored build and generated-source domains in canonical schema order. */
final class ManifestBuildConfigurationDecoder {
    private final ManifestBuildDecoder buildDecoder = new ManifestBuildDecoder();
    private final ManifestCompilerDecoder compilerDecoder = new ManifestCompilerDecoder();
    private final ManifestResourcesDecoder resourcesDecoder = new ManifestResourcesDecoder();
    private final ManifestGeneratedSourcesDecoder generatedDecoder =
            new ManifestGeneratedSourcesDecoder();
    private final ManifestTestsDecoder testsDecoder = new ManifestTestsDecoder();
    private final ManifestCoverageDecoder coverageDecoder = new ManifestCoverageDecoder();

    Decoded decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<AuthoredBuild> build = buildDecoder.decode(index);
        Optional<AuthoredCompiler> compiler = compilerDecoder.decode(index);
        Optional<AuthoredResources> resources = resourcesDecoder.decode(index);
        Optional<AuthoredGeneratedSources> generated = generatedDecoder.decode(index);
        Optional<AuthoredTests> tests = testsDecoder.decode(index);
        Optional<AuthoredCoverage> coverage = coverageDecoder.decode(index);
        AuthoredBuildConfiguration configuration = new AuthoredBuildConfiguration(
                build, compiler, resources, tests, coverage);
        return new Decoded(configuration, generated);
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
    private static final List<ManifestField> OUTPUT_FIELDS = List.of(
            FinalManifestBuildFields.BUILD_OUTPUT_ROOT,
            FinalManifestBuildFields.BUILD_OUTPUT_MAIN,
            FinalManifestBuildFields.BUILD_OUTPUT_TEST,
            FinalManifestBuildFields.BUILD_OUTPUT_INTEGRATION);
    private static final List<ManifestField> METADATA_FIELDS = List.of(
            FinalManifestBuildFields.BUILD_METADATA_BUILD_INFO,
            FinalManifestBuildFields.BUILD_METADATA_GIT,
            FinalManifestBuildFields.BUILD_METADATA_REPRODUCIBLE);
    private static final List<ManifestField> BUILD_FIELDS = List.of(
            FinalManifestBuildFields.BUILD_SOURCES,
            FinalManifestBuildFields.BUILD_OUTPUT_ROOT,
            FinalManifestBuildFields.BUILD_OUTPUT_MAIN,
            FinalManifestBuildFields.BUILD_OUTPUT_TEST,
            FinalManifestBuildFields.BUILD_OUTPUT_INTEGRATION,
            FinalManifestBuildFields.BUILD_METADATA_BUILD_INFO,
            FinalManifestBuildFields.BUILD_METADATA_GIT,
            FinalManifestBuildFields.BUILD_METADATA_REPRODUCIBLE);

    Optional<AuthoredBuild> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> sourcesField = index.field(
                FinalManifestBuildFields.BUILD_SOURCES);
        List<ManifestRelativePath> sources = sourcesField
                .map(ManifestBuildDecoder::paths)
                .orElseGet(List::of);
        Optional<AuthoredBuild.Output> output = output(index);
        Optional<AuthoredBuild.Metadata> metadata = metadata(index);
        if (sourcesField.isEmpty() && output.isEmpty() && metadata.isEmpty()) {
            return Optional.empty();
        }
        ValidatedManifestField anchor = firstPresent(index, BUILD_FIELDS);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredBuild(sources, output, metadata)));
    }

    private static Optional<AuthoredBuild.Output> output(ManifestDecodeIndex index) {
        Optional<ManifestRelativePath> root = path(
                index, FinalManifestBuildFields.BUILD_OUTPUT_ROOT);
        Optional<ManifestRelativePath> main = path(
                index, FinalManifestBuildFields.BUILD_OUTPUT_MAIN);
        Optional<ManifestRelativePath> test = path(
                index, FinalManifestBuildFields.BUILD_OUTPUT_TEST);
        Optional<ManifestRelativePath> integration = path(
                index, FinalManifestBuildFields.BUILD_OUTPUT_INTEGRATION);
        if (root.isEmpty() && main.isEmpty() && test.isEmpty() && integration.isEmpty()) {
            return Optional.empty();
        }
        ValidatedManifestField anchor = firstPresent(index, OUTPUT_FIELDS);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredBuild.Output(root, main, test, integration)));
    }

    private static Optional<AuthoredBuild.Metadata> metadata(ManifestDecodeIndex index) {
        Optional<Boolean> buildInfo = bool(
                index, FinalManifestBuildFields.BUILD_METADATA_BUILD_INFO);
        Optional<Boolean> git = bool(
                index, FinalManifestBuildFields.BUILD_METADATA_GIT);
        Optional<Boolean> reproducible = bool(
                index, FinalManifestBuildFields.BUILD_METADATA_REPRODUCIBLE);
        if (buildInfo.isEmpty() && git.isEmpty() && reproducible.isEmpty()) {
            return Optional.empty();
        }
        ValidatedManifestField anchor = firstPresent(index, METADATA_FIELDS);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredBuild.Metadata(buildInfo, git, reproducible)));
    }

    private static List<ManifestRelativePath> paths(ValidatedManifestField field) {
        return ManifestSemanticDiagnostics.construct(
                field,
                () -> ManifestTomlValues.strings(field).stream()
                        .map(ManifestRelativePath::new)
                        .toList());
    }

    private static Optional<ManifestRelativePath> path(
            ManifestDecodeIndex index,
            ManifestField handle) {
        return index.field(handle).map(field -> ManifestSemanticDiagnostics.construct(
                field,
                () -> new ManifestRelativePath(ManifestTomlValues.string(field))));
    }

    private static Optional<Boolean> bool(
            ManifestDecodeIndex index,
            ManifestField handle) {
        return index.field(handle).map(ManifestTomlValues::booleanValue);
    }

    private static ValidatedManifestField firstPresent(
            ManifestDecodeIndex index,
            List<ManifestField> fields) {
        return fields.stream()
                .map(index::field)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Authored build aggregate has no direct field evidence."));
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
        ValidatedManifestField anchor = lineField
                .or(() -> branchField)
                .or(() -> instructionField)
                .or(() -> methodField)
                .orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredCoverage(line, branch, instruction, method)));
    }

    private static Optional<CoveragePercentage> floor(
            Optional<ValidatedManifestField> field) {
        return field.map(value -> ManifestSemanticDiagnostics.construct(
                value,
                () -> new CoveragePercentage(ManifestTomlValues.number(value))));
    }
}
