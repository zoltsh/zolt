package sh.zolt.quality.packaging;

import static sh.zolt.quality.QualityCheckService.MANIFEST_METADATA;
import static sh.zolt.quality.QualityCheckService.PACKAGE_METADATA;

import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
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
                    "[package].tests",
                    "Test sources are present, but tests jar generation is disabled for this library package.",
                    "Set [package].tests = true or remove test sources from the library artifact story.");
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
        return contentQualityCheck.check(member, projectRoot, config, lockfilePath, requirePackage);
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

    private static boolean hasPublicationMetadata(PublicationMetadata metadata) {
        return !metadata.name().isBlank()
                || !metadata.description().isBlank()
                || !metadata.url().isBlank()
                || !metadata.license().isBlank()
                || !metadata.developers().isEmpty()
                || !metadata.scm().isBlank()
                || !metadata.issues().isBlank();
    }

    private static Optional<QualityCheckResult> firstMissingPublicationMetadata(
            Optional<String> member,
            PublicationMetadata metadata) {
        if (metadata.name().isBlank()) {
            return missingPublicationField(member, "name");
        }
        if (metadata.description().isBlank()) {
            return missingPublicationField(member, "description");
        }
        if (metadata.url().isBlank()) {
            return missingPublicationField(member, "url");
        }
        if (metadata.license().isBlank()) {
            return missingPublicationField(member, "license");
        }
        if (metadata.developers().isEmpty()) {
            return missingPublicationField(member, "developers");
        }
        if (metadata.scm().isBlank()) {
            return missingPublicationField(member, "scm");
        }
        if (metadata.issues().isBlank()) {
            return missingPublicationField(member, "issues");
        }
        return Optional.empty();
    }

    private static Optional<QualityCheckResult> missingPublicationField(Optional<String> member, String field) {
        return Optional.of(QualityCheckResult.failed(
                PACKAGE_METADATA,
                member,
                "[package.metadata]." + field,
                "Library package metadata is enabled, but publication metadata field `" + field + "` is missing.",
                "Fill [package.metadata]." + field + " in zolt.toml."));
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
