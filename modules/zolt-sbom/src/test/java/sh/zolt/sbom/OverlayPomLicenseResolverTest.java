package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cache.CachedArtifact;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cache.RepositoryCacheScope;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;

final class OverlayPomLicenseResolverTest {
    @Test
    void inheritsLicenseFromParentPomInTheLockedOverlayScope(@TempDir Path root) throws IOException {
        Path cacheRoot = root.resolve("cache");
        Path overlayRoot = root.resolve("overlay");
        LocalArtifactCache cache = new LocalArtifactCache(cacheRoot);
        Coordinate child = coordinate("child", "1.0.0");
        Coordinate parent = coordinate("parent", "2.0.0");
        CachedArtifact childPom = cachePom(cache, overlayRoot, child, childPom());
        cachePom(cache, overlayRoot, parent, parentPom("MIT License"));

        List<SbomLicense> licenses = new PomLicenseResolver(cacheRoot).resolve(locked(childPom));

        assertEquals(List.of("MIT"), labels(licenses));
    }

    @Test
    void reportsUnknownWhenOverlayParentPomIsMissing(@TempDir Path root) throws IOException {
        Path cacheRoot = root.resolve("cache");
        Path overlayRoot = root.resolve("overlay");
        LocalArtifactCache cache = new LocalArtifactCache(cacheRoot);
        CachedArtifact childPom = cachePom(cache, overlayRoot, coordinate("child", "1.0.0"), childPom());

        List<SbomLicense> licenses = new PomLicenseResolver(cacheRoot).resolve(locked(childPom));

        assertEquals(SbomLicenseStatus.UNKNOWN, licenses.getFirst().status());
    }

    @Test
    void reportsUnknownWhenMatchingOverlayRootsDisagreeOnParentBytes(@TempDir Path root)
            throws IOException {
        Path cacheRoot = root.resolve("cache");
        LocalArtifactCache cache = new LocalArtifactCache(cacheRoot);
        Coordinate child = coordinate("child", "1.0.0");
        Coordinate parent = coordinate("parent", "2.0.0");
        Path firstOverlay = root.resolve("first-overlay");
        Path secondOverlay = root.resolve("second-overlay");
        CachedArtifact childPom = cachePom(cache, firstOverlay, child, childPom());
        cachePom(cache, secondOverlay, child, childPom());
        cachePom(cache, firstOverlay, parent, parentPom("MIT License"));
        cachePom(cache, secondOverlay, parent, parentPom("Apache License, Version 2.0"));

        List<SbomLicense> licenses = new PomLicenseResolver(cacheRoot).resolve(locked(childPom));

        assertEquals(SbomLicenseStatus.UNKNOWN, licenses.getFirst().status());
    }

    @Test
    void changingOverlayParentContentReplacesScopedProvenance(@TempDir Path root)
            throws IOException {
        Path cacheRoot = root.resolve("cache");
        Path overlayRoot = root.resolve("overlay");
        LocalArtifactCache cache = new LocalArtifactCache(cacheRoot);
        Coordinate child = coordinate("child", "1.0.0");
        Coordinate parent = coordinate("parent", "2.0.0");
        CachedArtifact childPom = cachePom(cache, overlayRoot, child, childPom());
        cachePom(cache, overlayRoot, parent, parentPom("MIT License"));
        assertEquals(
                List.of("MIT"),
                labels(new PomLicenseResolver(cacheRoot).resolve(locked(childPom))));

        cachePom(cache, overlayRoot, parent, parentPom("Apache License, Version 2.0"));

        assertEquals(
                List.of("Apache-2.0"),
                labels(new PomLicenseResolver(cacheRoot).resolve(locked(childPom))));
    }

    private static CachedArtifact cachePom(
            LocalArtifactCache cache,
            Path overlayRoot,
            Coordinate coordinate,
            String xml) throws IOException {
        String repositoryPath = new MavenRepositoryPathBuilder().pomPath(coordinate);
        Path sourcePath = overlayRoot.resolve(repositoryPath);
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, xml);
        RepositoryCacheScope scope = RepositoryCacheScope.forOverlay(
                "MAVEN_LOCAL", "maven-local", overlayRoot);
        return cache.materializeOverlayPom(scope, coordinate, "maven-local", sourcePath);
    }

    private static LockPackage locked(CachedArtifact pom) {
        Coordinate coordinate = pom.coordinate();
        return new LockPackage(
                PackageId.from(coordinate),
                coordinate.version().orElseThrow(),
                pom.source(),
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.of(pom.repositoryPath()),
                Optional.empty(),
                Optional.of(pom.sha256()),
                List.of());
    }

    private static Coordinate coordinate(String artifact, String version) {
        return new Coordinate("org.example", artifact, Optional.of(version));
    }

    private static List<String> labels(List<SbomLicense> licenses) {
        return licenses.stream().map(SbomLicense::label).sorted().toList();
    }

    private static String childPom() {
        return """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>2.0.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """;
    }

    private static String parentPom(String license) {
        return """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>2.0.0</version>
                  <licenses><license><name>%s</name></license></licenses>
                </project>
                """.formatted(license);
    }
}
