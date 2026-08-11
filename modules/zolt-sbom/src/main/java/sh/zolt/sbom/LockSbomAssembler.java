package sh.zolt.sbom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;

/**
 * Assembles an {@link SbomModel} from a resolved {@link ZoltLockfile} and its {@link ProjectConfig}.
 *
 * <p>This is a pure read of the lock — the dependency edges, hashes, and per-member attribution are
 * already persisted there (see the design record). No re-resolution and no network. Scope filtering,
 * multi-scope dedup (required wins), edge filtering to surviving endpoints, and deterministic sorting
 * all happen here so the writers only serialize.
 */
public final class LockSbomAssembler {
    private final DeclaredLicenseResolver declaredLicenseResolver = new DeclaredLicenseResolver();

    public SbomModel assemble(
            ProjectConfig config,
            ZoltLockfile lockfile,
            SbomScopeSelection selection,
            Optional<String> timestamp,
            String toolVersion) {
        return assemble(config, lockfile, selection, timestamp, toolVersion, LicenseIndex.empty());
    }

    public SbomModel assemble(
            ProjectConfig config,
            ZoltLockfile lockfile,
            SbomScopeSelection selection,
            Optional<String> timestamp,
            String toolVersion,
            LicenseIndex licenses) {
        SbomComponent root = rootComponent(config);

        // Dedup components by bom-ref (purl). Multi-scope duplicates merge; required wins.
        Map<String, ComponentAccumulator> byRef = new LinkedHashMap<>();
        List<LockPackage> included = new ArrayList<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            SbomScopeGroup group = SbomScopeGroup.of(lockPackage.scope());
            if (!selection.includes(group)) {
                continue;
            }
            included.add(lockPackage);
            accumulate(byRef, lockPackage, group);
        }

        List<SbomComponent> components = byRef.values().stream()
                .map(accumulator -> accumulator.toComponent(
                        emittableLicenses(licenses.forCoordinate(accumulator.coordinate()))))
                .sorted(Comparator.comparing(SbomComponent::bomRef))
                .toList();

        List<SbomDependency> dependencies =
                dependencyGraph(root, included, lockfile.packages());
        String serialNumber = serialNumber(config, lockfile, components);
        return new SbomModel(
                serialNumber,
                timestamp,
                List.of(new SbomTool("zolt", toolVersion)),
                root,
                components,
                dependencies);
    }

    /** SBOM-emittable licenses: UNKNOWN dropped, deduplicated, sorted by label (id/name). */
    static List<SbomLicense> emittableLicenses(List<SbomLicense> licenses) {
        return licenses.stream()
                .filter(license -> license.status() != SbomLicenseStatus.UNKNOWN)
                .distinct()
                .sorted(Comparator.comparing(SbomLicense::label))
                .toList();
    }

    private void accumulate(
            Map<String, ComponentAccumulator> byRef,
            LockPackage lockPackage,
            SbomScopeGroup group) {
        String groupId = lockPackage.packageId().groupId();
        String artifactId = lockPackage.packageId().artifactId();
        String version = lockPackage.version();
        String purl = LockArtifacts.purl(lockPackage);

        ComponentAccumulator accumulator = byRef.computeIfAbsent(
                purl,
                ref -> new ComponentAccumulator(
                        SbomComponentType.LIBRARY, ref, groupId, artifactId, version, ref));
        accumulator.raise(group.componentScope());
        LockArtifacts.hash(lockPackage).ifPresent(accumulator.hashes::add);
    }

    private List<SbomDependency> dependencyGraph(
            SbomComponent root,
            List<LockPackage> included,
            List<LockPackage> completeLock) {
        // Resolve each variant-qualified edge to the exact included package (bare edges to the default/sole
        // one), then to its purl bom-ref. Two variants of one GAV are distinct components with distinct
        // purls, so an edge lands on the specific variant a dependent used instead of collapsing to one.
        LockDependencyIndex index = new LockDependencyIndex(completeLock);
        validateGraph(completeLock, index, "zolt resolve");
        Map<String, TreeSet<String>> edges = new TreeMap<>();
        edges.put(root.bomRef(), new TreeSet<>());
        for (LockPackage lockPackage : included) {
            edges.computeIfAbsent(LockArtifacts.purl(lockPackage), ref -> new TreeSet<>());
        }

        for (LockPackage lockPackage : included) {
            String ref = LockArtifacts.purl(lockPackage);
            if (lockPackage.direct()) {
                edges.get(root.bomRef()).add(ref);
            }
            TreeSet<String> dependsOn = edges.get(ref);
            for (String edge : lockPackage.dependencies()) {
                index.resolveGraphEdge(edge, "zolt resolve")
                        .map(LockArtifacts::purl)
                        .filter(edges::containsKey)
                        .ifPresent(dependsOn::add);
            }
        }

        List<SbomDependency> dependencies = new ArrayList<>();
        for (var edge : edges.entrySet()) {
            dependencies.add(new SbomDependency(edge.getKey(), List.copyOf(edge.getValue())));
        }
        return dependencies;
    }

    private static void validateGraph(
            List<LockPackage> packages,
            LockDependencyIndex index,
            String regenerateCommand) {
        packages.stream()
                .flatMap(lockPackage -> lockPackage.dependencies().stream())
                .forEach(edge -> index.resolveGraphEdge(edge, regenerateCommand));
    }

    private String serialNumber(ProjectConfig config, ZoltLockfile lockfile, List<SbomComponent> components) {
        String seed = lockfile.projectResolutionFingerprint()
                .filter(fingerprint -> !fingerprint.isBlank())
                .orElseGet(() -> SbomSerialNumber.fallbackSeed(
                        rootCoordinate(config),
                        components.stream().map(SbomComponent::purl).toList()));
        return SbomSerialNumber.serialNumber(seed);
    }

    private SbomComponent rootComponent(ProjectConfig config) {
        String group = config.project().group();
        String name = config.project().name();
        String version = config.project().version();
        String extension = rootExtension(config);
        String purl = PurlWriter.purl(group, name, version, extension, Optional.empty());
        return new SbomComponent(
                rootType(config),
                purl,
                group,
                name,
                version,
                purl,
                SbomComponentScope.REQUIRED,
                List.of(),
                emittableLicenses(rootLicenses(config)));
    }

    /** The root license is authoritative from config ([package.metadata]), not POM-extracted. */
    private List<SbomLicense> rootLicenses(ProjectConfig config) {
        String license = config.packageSettings().metadata().license();
        String licenseUrl = config.packageSettings().metadata().licenseUrl();
        Optional<String> name = license.isBlank() ? Optional.empty() : Optional.of(license);
        Optional<String> url = licenseUrl.isBlank() ? Optional.empty() : Optional.of(licenseUrl);
        if (name.isEmpty() && url.isEmpty()) {
            return List.of();
        }
        return List.of(declaredLicenseResolver.resolve(name, url));
    }

    private static SbomComponentType rootType(ProjectConfig config) {
        if (config.project().main().isPresent()) {
            return SbomComponentType.APPLICATION;
        }
        return switch (config.packageSettings().mode()) {
            case SPRING_BOOT, SPRING_BOOT_WAR, WAR, QUARKUS, UBER -> SbomComponentType.APPLICATION;
            case THIN, BOM -> SbomComponentType.LIBRARY;
        };
    }

    private static String rootExtension(ProjectConfig config) {
        return switch (config.packageSettings().mode()) {
            case WAR, SPRING_BOOT_WAR -> "war";
            case BOM -> "pom";
            case THIN, SPRING_BOOT, QUARKUS, UBER -> "jar";
        };
    }

    private static String rootCoordinate(ProjectConfig config) {
        return config.project().group() + ":" + config.project().name() + ":" + config.project().version();
    }

    private static final class ComponentAccumulator {
        private final SbomComponentType type;
        private final String bomRef;
        private final String group;
        private final String name;
        private final String version;
        private final String purl;
        private final TreeSet<SbomHash> hashes = new TreeSet<>(
                Comparator.comparing(SbomHash::alg).thenComparing(SbomHash::content));
        private SbomComponentScope scope = SbomComponentScope.OPTIONAL;

        private ComponentAccumulator(
                SbomComponentType type,
                String bomRef,
                String group,
                String name,
                String version,
                String purl) {
            this.type = type;
            this.bomRef = bomRef;
            this.group = group;
            this.name = name;
            this.version = version;
            this.purl = purl;
        }

        private void raise(SbomComponentScope candidate) {
            if (candidate == SbomComponentScope.REQUIRED) {
                scope = SbomComponentScope.REQUIRED;
            }
        }

        private String coordinate() {
            return group + ":" + name + ":" + version;
        }

        private SbomComponent toComponent(List<SbomLicense> licenses) {
            return new SbomComponent(type, bomRef, group, name, version, purl, scope, List.copyOf(hashes), licenses);
        }
    }
}
