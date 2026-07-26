package sh.zolt.build.packageauthority;

import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Current-member provided declarations that authoritatively override deployable runtime artifacts.
 *
 * <p>Workspace lock directness is aggregate state, so it is deliberately ignored here. Authority is
 * the intersection of this member's exact {@code [provided.dependencies]} declarations and the
 * resolved provided lane. Runtime reachability is retained separately so Spring Boot WAR placement
 * can choose exactly one of {@code WEB-INF/lib} and {@code WEB-INF/lib-provided}.
 */
public final class ProvidedPackagingOverrides {
    private final Set<String> artifactVariants;
    private final Set<String> runtimeArtifactVariants;

    private ProvidedPackagingOverrides(
            Set<String> artifactVariants,
            Set<String> runtimeArtifactVariants) {
        this.artifactVariants = Set.copyOf(artifactVariants);
        this.runtimeArtifactVariants =
                Set.copyOf(runtimeArtifactVariants);
    }

    public static ProvidedPackagingOverrides fromConfigAndLockPackages(
            ProjectConfig config,
            List<LockPackage> packages) {
        Set<String> providedVariants = packages.stream()
                .filter(lockPackage ->
                        lockPackage.scope() == DependencyScope.PROVIDED)
                .map(NestedArtifactIdentity::of)
                .map(NestedArtifactIdentity::artifactVariantKey)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        return create(
                config,
                providedVariants,
                packages.stream()
                        .filter(lockPackage -> lockPackage
                                .scope()
                                .entersMainRuntimeClasspath())
                        .map(NestedArtifactIdentity::of)
                        .map(NestedArtifactIdentity::artifactVariantKey)
                        .collect(java.util.stream.Collectors.toCollection(
                                LinkedHashSet::new)));
    }

    public static ProvidedPackagingOverrides fromConfigAndClasspathPackages(
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages) {
        Set<String> providedVariants = packages.stream()
                .filter(packageEntry ->
                        packageEntry.scope() == DependencyScope.PROVIDED)
                .map(packageEntry ->
                        packageEntry.resolvedPackage().artifactIdentity())
                .map(NestedArtifactIdentity::artifactVariantKey)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        return create(
                config,
                providedVariants,
                packages.stream()
                        .filter(packageEntry -> packageEntry
                                .scope()
                                .entersMainRuntimeClasspath())
                        .map(packageEntry -> packageEntry
                                .resolvedPackage()
                                .artifactIdentity()
                                .artifactVariantKey())
                        .collect(java.util.stream.Collectors.toCollection(
                                LinkedHashSet::new)));
    }

    public boolean suppresses(
            NestedArtifactIdentity identity,
            PackageMode mode) {
        if (requiresDefaultSpringBootLoader(mode)
                && SpringBootLoaderArtifact.isDefaultLoader(
                        identity.packageId(),
                        identity.extension(),
                        identity.classifier())) {
            return false;
        }
        return artifactVariants.contains(identity.artifactVariantKey());
    }

    public boolean packagesProvided(
            NestedArtifactIdentity identity,
            PackageMode mode) {
        if (requiresDefaultSpringBootLoader(mode)
                && SpringBootLoaderArtifact.isDefaultLoader(
                        identity.packageId(),
                        identity.extension(),
                        identity.classifier())) {
            return false;
        }
        String variant = identity.artifactVariantKey();
        return artifactVariants.contains(variant)
                || !runtimeArtifactVariants.contains(variant);
    }

    private static ProvidedPackagingOverrides create(
            ProjectConfig config,
            Set<String> providedVariants,
            Set<String> runtimeVariants) {
        Set<String> declarations =
                declaredProvidedArtifactVariants(config);
        Set<String> authoritative = new LinkedHashSet<>(
                providedVariants);
        authoritative.retainAll(declarations);
        return new ProvidedPackagingOverrides(
                authoritative,
                runtimeVariants);
    }

    private static Set<String> declaredProvidedArtifactVariants(
            ProjectConfig config) {
        Set<String> coordinates = new LinkedHashSet<>(
                config.providedDependencies().keySet());
        coordinates.addAll(config.managedProvidedDependencies());
        CoordinateParser parser = new CoordinateParser();
        Set<String> variants = new LinkedHashSet<>();
        for (String value : coordinates) {
            Coordinate coordinate = parser.parse(value);
            String packageId = coordinate.packageId();
            DependencyMetadata metadata = config.dependencyMetadata().get(
                    DependencyMetadata.key(
                            "provided.dependencies",
                            packageId));
            LockArtifactVariant variant = new LockArtifactVariant(
                    metadata == null || metadata.type() == null
                            ? "jar"
                            : metadata.type(),
                    metadata == null
                            ? Optional.empty()
                            : Optional.ofNullable(
                                    metadata.classifier()));
            variants.add(NestedArtifactIdentity.of(
                            PackageId.from(coordinate),
                            "declared",
                            variant,
                            NestedArtifactIdentity.SourceKind.EXTERNAL)
                    .artifactVariantKey());
        }
        return Set.copyOf(variants);
    }

    private static boolean requiresDefaultSpringBootLoader(PackageMode mode) {
        return mode == PackageMode.SPRING_BOOT
                || mode == PackageMode.SPRING_BOOT_WAR;
    }
}
