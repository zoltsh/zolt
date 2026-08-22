package sh.zolt.manifest.authored;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;

/** Authored {@code [build]} settings without conventional defaults materialized. */
public record AuthoredBuild(
        List<ManifestRelativePath> sources,
        Optional<Output> output,
        Optional<Metadata> metadata) {
    public AuthoredBuild {
        sources = ManifestModelValues.orderedDistinctList(sources, "Build source roots");
        output = Objects.requireNonNull(output, "Authored build output must not be null.");
        metadata = Objects.requireNonNull(metadata, "Authored build metadata must not be null.");
        if (sources.isEmpty() && output.isEmpty() && metadata.isEmpty()) {
            throw new IllegalArgumentException("Authored build settings must not be empty.");
        }
    }

    /** Paths in {@code [build.output]}; child paths are relative to {@link #root()}. */
    public record Output(
            Optional<ManifestRelativePath> root,
            Optional<ManifestRelativePath> main,
            Optional<ManifestRelativePath> test,
            Optional<ManifestRelativePath> integration) {
        public Output {
            root = Objects.requireNonNull(root, "Authored output root must not be null.");
            main = Objects.requireNonNull(main, "Authored main output must not be null.");
            test = Objects.requireNonNull(test, "Authored test output must not be null.");
            integration = Objects.requireNonNull(
                    integration, "Authored integration-test output must not be null.");
            if (root.isEmpty() && main.isEmpty() && test.isEmpty() && integration.isEmpty()) {
                throw new IllegalArgumentException("Authored build output must not be empty.");
            }
        }
    }

    /** Authored field presence in {@code [build.metadata]}, including explicit false values. */
    public record Metadata(
            Optional<Boolean> buildInfo,
            Optional<Boolean> git,
            Optional<Boolean> reproducible) {
        public Metadata {
            buildInfo = Objects.requireNonNull(buildInfo, "Authored buildInfo value must not be null.");
            git = Objects.requireNonNull(git, "Authored git value must not be null.");
            reproducible = Objects.requireNonNull(
                    reproducible, "Authored reproducible value must not be null.");
            if (buildInfo.isEmpty() && git.isEmpty() && reproducible.isEmpty()) {
                throw new IllegalArgumentException("Authored build metadata must not be empty.");
            }
        }
    }
}
