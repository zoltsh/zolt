package sh.zolt.manifest.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredNativeImage;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.manifest.authored.AuthoredPackageManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredSpringBoot;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.BomSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.project.QuarkusPackageMode;
import sh.zolt.project.QuarkusSettings;
import sh.zolt.project.SpringBootSettings;
import sh.zolt.project.UberDuplicatePolicy;

/**
 * Projects the final {@code [package]}, {@code [bom]}, {@code [framework.spring-boot]}, and
 * {@code [native]} domains onto the legacy packaging, framework, and native settings.
 */
final class ProjectConfigPackaging {
    private static final String DEFAULT_NATIVE_OUTPUT = "native";

    private ProjectConfigPackaging() {
    }

    static PackageSettings packageSettings(
            AuthoredPackaging packaging,
            PublicationMetadata metadata,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        Optional<AuthoredPackage> settings = packaging.packageSettings();
        return new PackageSettings(
                mode(packaging),
                settings.flatMap(AuthoredPackage::sources).orElse(false),
                settings.flatMap(AuthoredPackage::javadoc).orElse(false),
                settings.flatMap(AuthoredPackage::testJar).orElse(false),
                metadata,
                packaging.manifest().map(AuthoredPackageManifest::attributes).orElse(Map.of()),
                duplicates(settings),
                bom(packaging.bom(), versions));
    }

    static FrameworkSettings framework(AuthoredPackaging packaging) {
        return new FrameworkSettings(
                new SpringBootSettings(packaging.springBoot()
                        .flatMap(AuthoredSpringBoot::nativeImage)
                        .orElse(false)),
                new QuarkusSettings(
                        mode(packaging) == PackageMode.QUARKUS,
                        QuarkusPackageMode.FAST_JAR));
    }

    static NativeSettings nativeSettings(
            AuthoredPackaging packaging,
            String projectName,
            String outputRoot) {
        Optional<AuthoredNativeImage> image = packaging.nativeImage();
        if (image.isEmpty()) {
            return new NativeSettings("", outputRoot + "/" + DEFAULT_NATIVE_OUTPUT, List.of());
        }
        AuthoredNativeImage authored = image.orElseThrow();
        return new NativeSettings(
                authored.name().orElse(projectName),
                ProjectConfigBuild.joined(outputRoot, authored.output(), DEFAULT_NATIVE_OUTPUT),
                authored.args().orElse(List.of()));
    }

    static PackageMode mode(AuthoredPackaging packaging) {
        if (packaging.bom().isPresent()) {
            return PackageMode.BOM;
        }
        return packaging.packageSettings()
                .flatMap(AuthoredPackage::mode)
                .map(ProjectConfigPackaging::mode)
                .orElse(PackageMode.THIN);
    }

    private static PackageMode mode(AuthoredPackage.Mode mode) {
        return switch (mode) {
            case JAR -> PackageMode.THIN;
            case UBER_JAR -> PackageMode.UBER;
            case WAR -> PackageMode.WAR;
            case SPRING_BOOT -> PackageMode.SPRING_BOOT;
            case SPRING_BOOT_WAR -> PackageMode.SPRING_BOOT_WAR;
            case QUARKUS -> PackageMode.QUARKUS;
        };
    }

    private static UberDuplicatePolicy duplicates(Optional<AuthoredPackage> settings) {
        return settings.flatMap(AuthoredPackage::duplicates)
                .map(policy -> policy == AuthoredPackage.DuplicatePolicy.FIRST_WINS
                        ? UberDuplicatePolicy.FIRST_WINS
                        : UberDuplicatePolicy.FAIL)
                .orElse(UberDuplicatePolicy.FAIL);
    }

    private static BomSettings bom(
            Optional<AuthoredBom> bom,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        if (bom.isEmpty()) {
            return BomSettings.none();
        }
        AuthoredBom authored = bom.orElseThrow();
        return new BomSettings(
                members(authored.members()),
                versions(authored.versions().orElse(Map.of()), versions),
                imports(authored.imports().orElse(Map.of()), versions));
    }

    private static BomSettings.Members members(Optional<AuthoredBom.Members> members) {
        if (members.isEmpty()) {
            return BomSettings.Members.none();
        }
        AuthoredBom.Members authored = members.orElseThrow();
        return switch (authored.selection()) {
            case AuthoredBom.AllMembers ignored -> new BomSettings.Members(
                    true, List.of(), paths(authored.exclude()));
            case AuthoredBom.ExplicitMembers explicit -> new BomSettings.Members(
                    false, paths(explicit.paths()), paths(authored.exclude()));
        };
    }

    private static List<BomSettings.ManagedVersion> versions(
            Map<sh.zolt.manifest.DependencyCoordinate, AuthoredBom.Version> entries,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        List<BomSettings.ManagedVersion> managed = new ArrayList<>();
        entries.forEach((coordinate, version) -> managed.add(new BomSettings.ManagedVersion(
                coordinate.value(),
                ProjectConfigVersions.resolve(
                        version.selector(), versions, "[bom.versions] `" + coordinate + "`"),
                ProjectConfigVersions.reference(version.selector()),
                version.classifier().orElse(null),
                version.type().orElse(null))));
        return List.copyOf(managed);
    }

    private static List<BomSettings.ImportedBom> imports(
            Map<sh.zolt.manifest.DependencyCoordinate, PlatformSelector> entries,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        List<BomSettings.ImportedBom> imported = new ArrayList<>();
        entries.forEach((coordinate, selector) -> imported.add(new BomSettings.ImportedBom(
                coordinate.value(),
                ProjectConfigVersions.resolve(
                        selector, versions, "[bom.imports] `" + coordinate + "`"),
                ProjectConfigVersions.reference(selector))));
        return List.copyOf(imported);
    }

    private static List<String> paths(List<WorkspaceMemberPath> paths) {
        return paths.stream().map(WorkspaceMemberPath::value).toList();
    }
}
