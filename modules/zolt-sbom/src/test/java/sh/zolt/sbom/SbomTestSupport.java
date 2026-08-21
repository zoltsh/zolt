package sh.zolt.sbom;

import java.util.List;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.PublicationMetadata;

/** Shared fixtures for the SBOM tests: a demo project plus lockfile-package builders. */
abstract class SbomTestSupport {
    static final String TOOL_VERSION = "0.1.0-TEST";
    static final String SHA_A = "1111111111111111111111111111111111111111111111111111111111111111";
    static final String SHA_B = "2222222222222222222222222222222222222222222222222222222222222222";
    static final String SHA_C = "3333333333333333333333333333333333333333333333333333333333333333";

    protected static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                java.util.Map.of("central", "https://repo.maven.apache.org/maven2"),
                java.util.Map.of(),
                java.util.Map.of(),
                BuildSettings.defaults());
    }

    protected static ProjectConfig configWithLicense(String license, String licenseUrl) {
        PublicationMetadata metadata = new PublicationMetadata(
                "demo", "", "", license, licenseUrl, List.of(), List.of(), "", "", "", "", "");
        return config().withPackageSettings(new PackageSettings(
                PackageMode.THIN, false, false, false, metadata, java.util.Map.of()));
    }

    protected static ZoltLockfile lockfile(Optional<String> fingerprint, LockPackage... packages) {
        List<LockDependencyRoot> roots = java.util.Arrays.stream(packages)
                .filter(LockPackage::direct)
                .flatMap(lockPackage -> (lockPackage.members().isEmpty()
                                ? List.of(".")
                                : lockPackage.members())
                        .stream()
                        .map(member -> root(lockPackage.scope()) == null
                                ? null
                                : new LockDependencyRoot(
                                        member,
                                        lockPackage.packageId(),
                                        lockPackage.version(),
                                        LockArtifactVariant.of(lockPackage),
                                        root(lockPackage.scope()),
                                        Optional.of(lockPackage.scope()),
                                        false,
                                        false)))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                fingerprint,
                List.of(),
                List.of(packages),
                List.of(),
                List.of(),
                List.of(),
                roots);
    }

    private static DependencyLane root(DependencyScope scope) {
        return switch (scope) {
            case COMPILE -> DependencyLane.IMPLEMENTATION;
            case RUNTIME -> DependencyLane.RUNTIME;
            case PROVIDED -> DependencyLane.PROVIDED;
            case DEV -> DependencyLane.DEV;
            case TEST -> DependencyLane.TEST;
            case PROCESSOR -> DependencyLane.PROCESSOR;
            case TEST_PROCESSOR -> DependencyLane.TEST_PROCESSOR;
            default -> null;
        };
    }

    protected static SbomWorkspaceMember member(String path, String group, String name, String version) {
        return member(path, group, name, version, List.of());
    }

    protected static SbomWorkspaceMember member(
            String path,
            String group,
            String name,
            String version,
            List<String> dependencies) {
        return new SbomWorkspaceMember(path, ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, version, group, "21", Optional.empty()),
                java.util.Map.of("central", "https://repo.maven.apache.org/maven2"),
                java.util.Map.of(),
                java.util.Map.of(),
                BuildSettings.defaults()), dependencies);
    }

    protected static LockPackage workspacePackage(
            String group, String name, String version, String path, List<String> members) {
        return new LockPackage(
                new PackageId(group, name),
                version,
                "workspace",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(path),
                Optional.of("target/classes"),
                List.of(),
                members);
    }

    protected static LockPackage externalWithMembers(
            String group,
            String name,
            String version,
            DependencyScope scope,
            String jarSha256,
            List<String> dependencies,
            List<String> members) {
        String base = group.replace('.', '/') + "/" + name + "/" + version + "/" + name + "-" + version;
        return new LockPackage(
                new PackageId(group, name),
                version,
                "maven-central",
                scope,
                false,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of(jarSha256),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                members,
                List.of(),
                List.of(),
                List.of());
    }

    protected static LockPackage maven(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            boolean direct,
            String jarSha256,
            List<String> dependencies) {
        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "maven-central",
                scope,
                direct,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of(jarSha256),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /** A resolver artifact carrying a classifier, encoded in the {@code artifact} filename. */
    protected static LockPackage classified(
            String group,
            String artifact,
            String version,
            String classifier,
            String extension,
            String artifactSha256,
            List<String> dependencies) {
        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        String artifactPath = base + "-" + classifier + "." + extension;
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "maven-central",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.of(base + ".pom"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(artifactPath),
                Optional.of(extension),
                Optional.of(artifactSha256),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
