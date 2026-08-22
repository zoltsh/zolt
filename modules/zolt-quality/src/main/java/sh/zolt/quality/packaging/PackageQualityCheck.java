package sh.zolt.quality.packaging;

import static sh.zolt.quality.QualityCheckService.MANIFEST_METADATA;
import static sh.zolt.quality.QualityCheckService.PACKAGE_METADATA;

import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.quality.QualityCheckResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class PackageQualityCheck {
    private static final Set<String> ZOLT_OWNED_MANIFEST_ATTRIBUTES = Set.of(
            "manifest-version",
            "main-class");

    private final PackageContentQualityCheck contentQualityCheck;

    public PackageQualityCheck(
            PackagePlanService packagePlanService,
            PackageEvidenceManifestReader packageEvidenceManifestReader) {
        this.contentQualityCheck = new PackageContentQualityCheck(packagePlanService, packageEvidenceManifestReader);
    }

    public QualityCheckResult checkMetadata(
            Optional<String> member,
            Path projectRoot,
            ProjectConfig config) {
        PackageSettings settings = config.packageSettings();
        if (!usesLibraryPackageProfile(settings)) {
            return QualityCheckResult.passed(
                    PACKAGE_METADATA,
                    member,
                    config.project().name(),
                    "No library package metadata is requested.");
        }

        if (!settings.sources()) {
            return QualityCheckResult.failed(
                    PACKAGE_METADATA,
                    member,
                    "[package].sources",
                    "Library package metadata is enabled, but sources jar generation is disabled.",
                    "Set [package].sources = true for library projects.");
        }
        if (hasSourceFiles(projectRoot, config.build().sourceRoots()) && !settings.javadoc()) {
            return QualityCheckResult.failed(
                    PACKAGE_METADATA,
                    member,
                    "[package].javadoc",
                    "Library package metadata is enabled, but javadoc jar generation is disabled.",
                    "Set [package].javadoc = true when publishing Java APIs.");
        }
        if (hasSourceFiles(projectRoot, testSourceRoots(config.build())) && !settings.tests()) {
            return QualityCheckResult.failed(
                    PACKAGE_METADATA,
                    member,
                    "[package].testJar",
                    "Test sources are present, but tests jar generation is disabled for this library package.",
                    "Set [package].testJar = true or remove test sources from the library artifact story.");
        }

        Optional<QualityCheckResult> missingMetadata = firstMissingPublicationMetadata(member, settings.metadata());
        if (missingMetadata.isPresent()) {
            return missingMetadata.orElseThrow();
        }

        return QualityCheckResult.passed(
                PACKAGE_METADATA,
                member,
                config.project().name(),
                "Library package metadata is complete.");
    }

    public List<QualityCheckResult> checkContents(
            Optional<String> member,
            Path projectRoot,
            ProjectConfig config,
            Path lockfilePath,
            boolean requirePackage) {
        return checkContents(
                member,
                projectRoot,
                config,
                lockfilePath,
                LocalArtifactCache.defaultRoot(),
                requirePackage);
    }

    public List<QualityCheckResult> checkContents(
            Optional<String> member,
            Path projectRoot,
            ProjectConfig config,
            Path lockfilePath,
            Path cacheRoot,
            boolean requirePackage) {
        return contentQualityCheck.check(
                member,
                projectRoot,
                config,
                lockfilePath,
                cacheRoot,
                requirePackage);
    }

    public List<QualityCheckResult> checkContents(
            Optional<String> member,
            ProjectConfig config,
            PackagePlan plan,
            boolean requirePackage) {
        return contentQualityCheck.check(member, config, plan, requirePackage);
    }

    public QualityCheckResult checkManifestMetadata(
            Optional<String> member,
            ProjectConfig config) {
        PackageSettings settings = config.packageSettings();
        for (String attributeName : settings.manifestAttributes().keySet()) {
            if (ZOLT_OWNED_MANIFEST_ATTRIBUTES.contains(attributeName.toLowerCase(Locale.ROOT))) {
                return QualityCheckResult.failed(
                        MANIFEST_METADATA,
                        member,
                        "[package.manifest]." + attributeName,
                        "Manifest attribute `" + attributeName + "` is owned by Zolt.",
                        "Remove it from [package.manifest]; use [project].main for Main-Class.");
            }
        }

        if (!usesLibraryPackageProfile(settings)) {
            return QualityCheckResult.passed(
                    MANIFEST_METADATA,
                    member,
                    config.project().name(),
                    "No library manifest metadata is requested.");
        }

        if (!containsManifestAttribute(settings, "Automatic-Module-Name")) {
            return QualityCheckResult.failed(
                    MANIFEST_METADATA,
                    member,
                    "[package.manifest].Automatic-Module-Name",
                    "Library package metadata is enabled, but Automatic-Module-Name is missing.",
                    "Add [package.manifest].\"Automatic-Module-Name\" with a stable Java module name.");
        }

        return QualityCheckResult.passed(
                MANIFEST_METADATA,
                member,
                config.project().name(),
                "Library manifest metadata is deterministic.");
    }

    private static boolean containsManifestAttribute(PackageSettings settings, String name) {
        return settings.manifestAttributes().keySet().stream()
                .anyMatch(candidate -> candidate.equalsIgnoreCase(name));
    }

    private static boolean usesLibraryPackageProfile(PackageSettings settings) {
        return settings.sources()
                || settings.javadoc()
                || settings.tests()
                || hasPublicationMetadata(settings.metadata())
                || !settings.manifestAttributes().isEmpty();
    }

    /**
     * True when this project publishes a library, judged from the metadata Maven Central consumes.
     *
     * <p>Publication metadata now comes from {@code [project]}, {@code [project.scm]}, and
     * {@code [project.developers.<id>]} (design §14.4), so the POM display {@code name} and the flat
     * developer-name array are not evidence of a library profile. The name is derived from project
     * identity rather than authored, so every project carries one and reading it as a signal would
     * make every project look like a library; the flat array the final language cannot express at all.
     */
    private static boolean hasPublicationMetadata(PublicationMetadata metadata) {
        return !metadata.description().isBlank()
                || !metadata.url().isBlank()
                || !metadata.license().isBlank()
                || !metadata.developerEntries().isEmpty()
                || !metadata.scm().isBlank()
                || !metadata.scmConnection().isBlank()
                || !metadata.issues().isBlank();
    }

    /**
     * The first Maven Central requirement this project cannot satisfy (design §14.3).
     *
     * <p>Each rule names the field in the final language. The pre-cut {@code name} rule is gone
     * because §14.4 gave the POM display name no authored spelling — it is derived from project
     * identity, so no manifest can fail it — and the {@code developers} rule now reads the structured
     * {@code [project.developers.<id>]} entries §7.4 introduced, which is what Central's
     * name-and-email requirement needs.
     */
    private static Optional<QualityCheckResult> firstMissingPublicationMetadata(
            Optional<String> member,
            PublicationMetadata metadata) {
        if (metadata.description().isBlank()) {
            return missingPublicationField(member, "[project].description", "description");
        }
        if (metadata.url().isBlank()) {
            return missingPublicationField(member, "[project].url", "url");
        }
        if (metadata.license().isBlank()) {
            return missingPublicationField(member, "[project].license", "license");
        }
        if (metadata.developerEntries().isEmpty()) {
            return missingPublicationField(
                    member, "[project.developers.<id>]", "at least one developer");
        }
        if (metadata.scm().isBlank() && metadata.scmConnection().isBlank()) {
            return missingPublicationField(member, "[project.scm].url", "SCM location");
        }
        if (metadata.issues().isBlank()) {
            return missingPublicationField(member, "[project].issues", "issues URL");
        }
        return Optional.empty();
    }

    private static Optional<QualityCheckResult> missingPublicationField(
            Optional<String> member,
            String field,
            String subject) {
        return Optional.of(QualityCheckResult.failed(
                PACKAGE_METADATA,
                member,
                field,
                "Library package metadata is enabled, but publication metadata `" + subject
                        + "` is missing.",
                "Fill " + field + " in zolt.toml."));
    }

    private static List<String> testSourceRoots(BuildSettings build) {
        List<String> roots = new ArrayList<>();
        roots.add(build.test());
        roots.addAll(build.testSources());
        roots.addAll(build.groovyTestSources());
        return List.copyOf(new LinkedHashSet<>(roots));
    }

    private static boolean hasSourceFiles(Path projectRoot, List<String> roots) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path sourceRoot = normalizedRoot.resolve(root).normalize();
            if (!sourceRoot.startsWith(normalizedRoot) || !Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (var stream = Files.find(sourceRoot, Integer.MAX_VALUE, (path, attributes) ->
                    attributes.isRegularFile() && sourceLike(path))) {
                if (stream.findFirst().isPresent()) {
                    return true;
                }
            } catch (java.io.IOException exception) {
                return true;
            }
        }
        return false;
    }

    private static boolean sourceLike(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java") || fileName.endsWith(".groovy");
    }
}
