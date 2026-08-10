package sh.zolt.toolchain.catalog;

import sh.zolt.error.ActionableException;
import sh.zolt.net.NetworkTransport;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.catalog.metadata.GraalVmCommunityMetadataResolver;
import sh.zolt.toolchain.catalog.metadata.HttpToolchainMetadataTransport;
import sh.zolt.toolchain.catalog.metadata.JavaToolchainMetadataResolver;
import sh.zolt.toolchain.catalog.metadata.JavaToolchainRelease;
import sh.zolt.toolchain.catalog.metadata.TemurinMetadataResolver;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ResolvingJavaToolchainCatalog implements JavaToolchainCatalog {
    private final Map<JavaDistribution, JavaToolchainMetadataResolver> resolvers;
    private final JavaToolchainCatalog legacyCatalog;

    public ResolvingJavaToolchainCatalog(NetworkTransport transport) {
        this(metadataResolvers(new HttpToolchainMetadataTransport(transport)), new BundledJavaToolchainCatalog());
    }

    ResolvingJavaToolchainCatalog(
            Map<JavaDistribution, JavaToolchainMetadataResolver> resolvers,
            JavaToolchainCatalog legacyCatalog) {
        this.resolvers = Map.copyOf(resolvers);
        this.legacyCatalog = legacyCatalog;
    }

    @Override
    public Optional<LockedJavaToolchain> lock(JavaToolchainRequest request, HostPlatform platform) {
        return locks(request, platform, false).stream()
                .filter(locked -> locked.platform().equals(platform))
                .findFirst();
    }

    @Override
    public List<LockedJavaToolchain> locks(JavaToolchainRequest request, HostPlatform platform) {
        return locks(request, platform, false);
    }

    @Override
    public List<LockedJavaToolchain> locks(
            JavaToolchainRequest request,
            HostPlatform platform,
            boolean refresh) {
        if (!refresh) {
            List<LockedJavaToolchain> seeded = legacyCatalog.locks(request, platform);
            if (seeded.stream().anyMatch(locked -> locked.platform().equals(platform))) {
                return seeded;
            }
        }
        JavaDistribution distribution = request.distribution().orElseThrow(() -> new ActionableException(
                "Java toolchain metadata resolution needs an explicit distribution.",
                "Set [toolchain.java].distribution to graalvm-community or temurin."));
        JavaToolchainMetadataResolver resolver = Optional.ofNullable(resolvers.get(distribution))
                .orElseThrow(() -> new ActionableException(
                        "No Java toolchain metadata resolver is configured for " + distribution.id() + ".",
                        "Choose one of Zolt's supported Java distributions."));
        List<JavaToolchainRelease> releases = resolver.resolve(
                request,
                JavaToolchainCatalogSupport.lockPlatforms(platform));
        if (releases.stream().noneMatch(release -> release.platform().equals(platform))) {
            throw new ActionableException(
                    "No "
                            + distribution.id()
                            + " Java "
                            + request.version()
                            + " GA artifact is published for "
                            + platform.id()
                            + ".",
                    "Choose a distribution or Java feature version that the upstream publisher provides for "
                            + platform.id()
                            + ".");
        }
        return releases.stream()
                .map(release -> locked(request, distribution, release))
                .toList();
    }

    @Override
    public List<LockedJavaToolchain> available() {
        return legacyCatalog.available();
    }

    @Override
    public Optional<JavaToolchainArtifact> artifact(LockedJavaToolchain locked) {
        return JavaToolchainCatalogSupport.artifact(locked).or(() -> legacyCatalog.artifact(locked));
    }

    private static LockedJavaToolchain locked(
            JavaToolchainRequest request,
            JavaDistribution distribution,
            JavaToolchainRelease release) {
        return new LockedJavaToolchain(
                JavaToolchainCatalogSupport.id(distribution, request),
                request,
                release.platform(),
                release.resolvedVersion(),
                distribution,
                release.catalog(),
                release.artifactUri().toString(),
                release.sha256(),
                JavaToolchainCatalogSupport.layout(distribution, request, release.platform()));
    }

    private static Map<JavaDistribution, JavaToolchainMetadataResolver> metadataResolvers(
            HttpToolchainMetadataTransport transport) {
        EnumMap<JavaDistribution, JavaToolchainMetadataResolver> resolvers =
                new EnumMap<>(JavaDistribution.class);
        resolvers.put(JavaDistribution.TEMURIN, new TemurinMetadataResolver(transport));
        resolvers.put(JavaDistribution.GRAALVM_COMMUNITY, new GraalVmCommunityMetadataResolver(transport));
        return resolvers;
    }
}
