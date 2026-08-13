package sh.zolt.sbom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import sh.zolt.lockfile.CacheRelativePath;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import sh.zolt.maven.repository.RawPom;
import sh.zolt.maven.repository.RawPomLicense;
import sh.zolt.maven.repository.RawPomParent;
import sh.zolt.maven.repository.RawPomParseException;
import sh.zolt.maven.repository.RawPomParser;

/**
 * Resolves dependency licenses from cached POMs only — never fetches, never fails the SBOM.
 *
 * <p>For each package it reads the cached POM at {@code cacheRoot/<pom-path>}; if the POM declares no
 * {@code <licenses>}, it walks the {@code <parent>} coordinates through the cache (nearest ancestor
 * wins, cycle-guarded). A missing cached POM or an empty chain yields {@code UNKNOWN}. Licenses are
 * parsed as explicit SPDX expressions first, then normalized through {@link SpdxLicenseMapping};
 * unmatched licenses stay {@code UNMAPPED} with their raw name/url. Results are memoized per
 * coordinate for the duration of a run.
 */
public final class PomLicenseResolver {
    private final Path cacheRoot;
    private final RawPomParser pomParser;
    private final DeclaredLicenseResolver declaredLicenseResolver;
    private final MavenRepositoryPathBuilder pathBuilder;
    private final Map<String, List<SbomLicense>> memo = new HashMap<>();

    public PomLicenseResolver(Path cacheRoot) {
        this(cacheRoot, new RawPomParser(), new SpdxLicenseMapping(), new MavenRepositoryPathBuilder());
    }

    public PomLicenseResolver(Path cacheRoot, RawPomParser pomParser, SpdxLicenseMapping mapping) {
        this(cacheRoot, pomParser, mapping, new MavenRepositoryPathBuilder());
    }

    PomLicenseResolver(
            Path cacheRoot,
            RawPomParser pomParser,
            SpdxLicenseMapping mapping,
            MavenRepositoryPathBuilder pathBuilder) {
        this.cacheRoot = cacheRoot;
        this.pomParser = pomParser;
        this.declaredLicenseResolver = new DeclaredLicenseResolver(new sh.zolt.license.SpdxExpressionParser(), mapping);
        this.pathBuilder = pathBuilder;
    }

    /** Builds a {@link LicenseIndex} for the given external packages (workspace packages are skipped). */
    public LicenseIndex index(List<LockPackage> externalPackages) {
        Map<String, List<SbomLicense>> byCoordinate = new TreeMap<>();
        TreeSet<String> unresolved = new TreeSet<>();
        for (LockPackage lockPackage : externalPackages) {
            String coordinate = coordinate(lockPackage);
            List<SbomLicense> licenses = resolve(lockPackage);
            byCoordinate.put(coordinate, licenses);
            if (isUnknown(licenses)) {
                unresolved.add(coordinate);
            }
        }
        return new LicenseIndex(byCoordinate, List.copyOf(unresolved));
    }

    /** Resolves the licenses for one package, memoized by coordinate. Always returns a non-empty list. */
    public List<SbomLicense> resolve(LockPackage lockPackage) {
        return memo.computeIfAbsent(coordinate(lockPackage), key -> resolveUncached(lockPackage));
    }

    private List<SbomLicense> resolveUncached(LockPackage lockPackage) {
        Optional<RawPom> pom = lockPackage.pomPath().flatMap(this::readPom);
        if (pom.isEmpty()) {
            return List.of(SbomLicense.unknown());
        }
        Set<String> visited = new HashSet<>();
        visited.add(coordinate(lockPackage));
        List<SbomLicense> licenses = resolveChain(pom.orElseThrow(), visited);
        return licenses.isEmpty() ? List.of(SbomLicense.unknown()) : licenses;
    }

    private List<SbomLicense> resolveChain(RawPom pom, Set<String> visited) {
        RawPom current = pom;
        while (current != null) {
            if (!current.licenses().isEmpty()) {
                return mapLicenses(current.licenses());
            }
            Optional<RawPomParent> parent = current.parent();
            if (parent.isEmpty()) {
                return List.of();
            }
            RawPomParent rawParent = parent.orElseThrow();
            String parentCoordinate =
                    rawParent.groupId() + ":" + rawParent.artifactId() + ":" + rawParent.version();
            if (!visited.add(parentCoordinate)) {
                return List.of();
            }
            current = readPom(new CacheRelativePath(pathBuilder.pomPath(
                    new Coordinate(rawParent.groupId(), rawParent.artifactId(), Optional.of(rawParent.version())))))
                    .orElse(null);
        }
        return List.of();
    }

    private List<SbomLicense> mapLicenses(List<RawPomLicense> rawLicenses) {
        List<SbomLicense> licenses = new ArrayList<>();
        for (RawPomLicense raw : rawLicenses) {
            if (raw.name().isEmpty() && raw.url().isEmpty()) {
                continue;
            }
            licenses.add(declaredLicenseResolver.resolve(raw.name(), raw.url()));
        }
        return licenses;
    }

    private Optional<RawPom> readPom(CacheRelativePath repositoryRelativePath) {
        Path path = repositoryRelativePath.resolveWithin(cacheRoot);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(pomParser.parse(Files.readAllBytes(path)));
        } catch (IOException | RawPomParseException exception) {
            // Never fetch, never fail: an unreadable cached POM is simply UNKNOWN.
            return Optional.empty();
        }
    }

    private static boolean isUnknown(List<SbomLicense> licenses) {
        return licenses.size() == 1 && licenses.getFirst().status() == SbomLicenseStatus.UNKNOWN;
    }

    private static String coordinate(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version();
    }
}
