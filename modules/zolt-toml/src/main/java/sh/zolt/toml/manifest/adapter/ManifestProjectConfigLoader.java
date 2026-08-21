package sh.zolt.toml.manifest.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import sh.zolt.error.ActionableError;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.adapter.ProjectConfigCoverage;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.project.CoverageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.ZoltManifestParser;

/**
 * Loads one standalone {@code zolt.toml} written in the final manifest language and projects it onto
 * the legacy {@link ProjectConfig} the build engine consumes.
 *
 * <p>This loader parses only the final language. It is the final-language twin of
 * {@link sh.zolt.toml.ZoltTomlParser}, which keeps parsing only the legacy language; no source
 * revision contains a parser that accepts both (design §21.2). Rejected legacy spellings surface as
 * ordinary unknown-field or unknown-value diagnostics with no compatibility hints (design §21,
 * Phase 2).
 */
public final class ManifestProjectConfigLoader {
    private final ZoltManifestParser parser;
    private final EffectiveManifestComposer composer;
    private final EffectiveProjectConfigAdapter adapter;

    public ManifestProjectConfigLoader() {
        this(new ZoltManifestParser(), new EffectiveManifestComposer(), new EffectiveProjectConfigAdapter());
    }

    public ManifestProjectConfigLoader(
            ZoltManifestParser parser,
            EffectiveManifestComposer composer,
            EffectiveProjectConfigAdapter adapter) {
        this.parser = parser;
        this.composer = composer;
        this.adapter = adapter;
    }

    /** Loads and adapts the manifest at {@code path}. */
    public ProjectConfig load(Path path) {
        return load(read(path));
    }

    /** Parses and adapts already-captured manifest bytes. */
    public ProjectConfig load(String source) {
        return adapter.adapt(effective(source));
    }

    /** Parses {@code source} into its final document without composing an effective view. */
    public ZoltManifestDocument document(String source) {
        Objects.requireNonNull(source, "Manifest source is required.");
        return parser.parse(source);
    }

    /** Parses the manifest at {@code path} into its final document. */
    public ZoltManifestDocument document(Path path) {
        return document(read(path));
    }

    /**
     * Reads only the {@code [coverage]} floors, returning {@link CoverageSettings#none()} when the
     * file is absent so callers can treat "no config" as "no floors". This replaces the legacy
     * {@code parseCoverageFloors} entry point; the final field names are {@code line}, {@code branch},
     * {@code instruction}, and {@code method} (design §10.10).
     */
    public CoverageSettings coverageFloors(Path path) {
        if (!Files.exists(path)) {
            return CoverageSettings.none();
        }
        return coverageFloors(read(path));
    }

    /**
     * Reads the {@code [coverage]} floors from already-captured manifest bytes. The floors are read
     * exactly as authored so a virtual workspace root, which carries {@code [coverage]} but no
     * {@code [project]} to compose, is a valid input.
     */
    public CoverageSettings coverageFloors(String source) {
        return ProjectConfigCoverage.authored(document(source).authored().build().coverage());
    }

    /** Parses and composes {@code source} into its effective standalone view. */
    public EffectiveManifest effective(String source) {
        Objects.requireNonNull(source, "Manifest source is required.");
        ZoltManifestDocument document = parser.parse(source);
        try {
            return composer.composeStandalone(document.authored());
        } catch (IllegalArgumentException exception) {
            throw new ZoltConfigException(exception.getMessage());
        }
    }

    private static String read(Path path) {
        Objects.requireNonNull(path, "Manifest path is required.");
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not read zolt.toml at " + path + ".",
                    "Check that the file exists and is readable.",
                    exception));
        }
    }
}
