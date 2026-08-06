package sh.zolt.sbom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;

/**
 * Aggregates a whole workspace into ONE CycloneDX BOM: a root workspace component, each member as a
 * library component, and external dependencies deduped across members. Member→dependency edges come
 * from each member's exact projected graph roots; package edges come from the lock graph.
 *
 * <p>Member licenses are authoritative from each member's config; external licenses are resolved from
 * cached POMs (passed in as a {@link LicenseIndex}). The assembler is a pure read of already-parsed
 * inputs — no filesystem, no network.
 */
public final class WorkspaceSbomAssembler {
    private final SpdxLicenseMapping licenseMapping = new SpdxLicenseMapping();

    public SbomModel assemble(
            String workspaceName,
            List<SbomWorkspaceMember> members,
            ZoltLockfile lockfile,
            SbomScopeSelection selection,
            Optional<String> timestamp,
            String toolVersion,
            LicenseIndex externalLicenses) {
        SbomComponent root = rootComponent(workspaceName);

        // Member components (first-party). Coordinate and path both map to the member bom-ref.
        Map<String, String> memberPathToRef = new LinkedHashMap<>();
        List<SbomComponent> memberComponents = new ArrayList<>();
        for (SbomWorkspaceMember member : members) {
            SbomComponent component = memberComponent(member);
            memberComponents.add(component);
            memberPathToRef.put(member.path(), component.bomRef());
        }

        // External components (scope-filtered, deduped).
        LockMemberGraphIndex memberGraphs =
                new LockMemberGraphIndex(
                        lockfile.memberGraphs(), lockfile.packages());
        WorkspaceSbomExternalContexts contexts =
                WorkspaceSbomExternalContexts.create(
                        lockfile, selection, memberGraphs);
        Map<String, ExternalAccumulator> externals = new LinkedHashMap<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            if (lockPackage.workspace().isPresent() || !LockArtifacts.materialized(lockPackage)) {
                continue;
            }
            SbomScopeGroup group = SbomScopeGroup.of(lockPackage.scope());
            if (!selection.includes(group)) {
                continue;
            }
            String purl = LockArtifacts.purl(lockPackage);
            for (String contextRef : contexts.refs(lockPackage)) {
                ExternalAccumulator accumulator = externals.computeIfAbsent(
                        contextRef,
                        ref -> new ExternalAccumulator(
                                lockPackage.packageId().groupId(),
                                lockPackage.packageId().artifactId(),
                                lockPackage.version(),
                                ref,
                                purl));
                accumulator.raise(group.componentScope());
                LockArtifacts.hash(lockPackage)
                        .ifPresent(accumulator.hashes::add);
            }
        }
        // Members win coordinate mapping over any same-coordinate external (first-party identity).
        // Retain the historical bare GAV key, then add every exact scope-qualified workspace-package
        // identity from the lock so both legacy and version-3 edges land on the first-party component.
        Map<String, String> workspacePackageRefs = new LinkedHashMap<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            lockPackage.workspace()
                    .map(memberPathToRef::get)
                    .ifPresent(memberRef ->
                            workspacePackageRefs.put(
                                    refOf(lockPackage), memberRef));
        }

        List<SbomComponent> externalComponents = externals.values().stream()
                .map(accumulator -> accumulator.toComponent(
                        LockSbomAssembler.emittableLicenses(externalLicenses.forCoordinate(accumulator.coordinate()))))
                .toList();

        List<SbomComponent> components = new ArrayList<>();
        components.addAll(memberComponents);
        components.addAll(externalComponents);
        components.sort(Comparator.comparing(SbomComponent::bomRef));

        List<SbomDependency> dependencies = dependencyGraph(root, members, memberComponents, components, lockfile,
                contexts, workspacePackageRefs, memberPathToRef, selection, memberGraphs);
        String serialNumber = serialNumber(root.bomRef(), lockfile, components);
        return new SbomModel(
                serialNumber,
                timestamp,
                List.of(new SbomTool("zolt", toolVersion)),
                root,
                components,
                dependencies);
    }

    private List<SbomDependency> dependencyGraph(
            SbomComponent root,
            List<SbomWorkspaceMember> members,
            List<SbomComponent> memberComponents,
            List<SbomComponent> components,
            ZoltLockfile lockfile,
            WorkspaceSbomExternalContexts contexts,
            Map<String, String> workspacePackageRefs,
            Map<String, String> memberPathToRef,
            SbomScopeSelection selection,
            LockMemberGraphIndex memberGraphs) {
        Map<String, TreeSet<String>> edges = new TreeMap<>();
        edges.put(root.bomRef(), new TreeSet<>());
        for (SbomComponent component : components) {
            edges.computeIfAbsent(component.bomRef(), ref -> new TreeSet<>());
        }

        // The workspace root contains every member.
        for (SbomComponent member : memberComponents) {
            edges.get(root.bomRef()).add(member.bomRef());
        }

        LockDependencyIndex packageIndex = new LockDependencyIndex(lockfile.packages());
        lockfile.packages().stream()
                .flatMap(lockPackage -> lockPackage.dependencies().stream())
                .forEach(edge -> packageIndex.resolveGraphEdge(
                        edge, "zolt resolve --workspace"));
        lockfile.memberGraphs().stream()
                .flatMap(graph -> graph.dependencies().stream())
                .forEach(edge -> packageIndex.resolveGraphEdge(
                        edge, "zolt resolve --workspace"));
        for (SbomWorkspaceMember member : members) {
            String memberRef = memberPathToRef.get(member.path());
            if (memberRef == null) {
                continue;
            }
            for (String edge : member.dependencies()) {
                packageIndex.resolveGraphEdge(edge, "zolt resolve --workspace")
                        .filter(target -> selection.includes(SbomScopeGroup.of(target.scope())))
                        .map(target -> targetRef(
                                target,
                                member.path(),
                                contexts,
                                workspacePackageRefs))
                        .ifPresent(target -> edges.get(memberRef).add(target));
            }
        }
        for (LockPackage lockPackage : lockfile.packages()) {
            if (!selection.includes(SbomScopeGroup.of(lockPackage.scope()))) {
                continue;
            }
            if (lockPackage.workspace().isPresent()) {
                String ref = workspacePackageRefs.get(refOf(lockPackage));
                if (ref == null) {
                    continue;
                }
                for (String edge : lockPackage.dependencies()) {
                    packageIndex.resolveGraphEdge(edge, "zolt resolve --workspace")
                            .map(target -> targetRef(
                                    target,
                                    lockPackage.workspace().orElseThrow(),
                                    contexts,
                                    workspacePackageRefs))
                            .ifPresent(target -> edges.get(ref).add(target));
                }
                continue;
            }
            List<String> contextMembers = lockPackage.members().isEmpty()
                    ? List.of("")
                    : lockPackage.members();
            for (String memberPath : contextMembers) {
                String ref = contexts.ref(lockPackage, memberPath);
                if (ref == null) {
                    continue;
                }
                List<String> dependencies = memberPath.isEmpty()
                        ? lockPackage.dependencies()
                        : memberGraphs.dependenciesFor(
                                memberPath, lockPackage);
                for (String edge : dependencies) {
                    packageIndex.resolveGraphEdge(
                                    edge, "zolt resolve --workspace")
                            .map(target -> targetRef(
                                    target,
                                    memberPath,
                                    contexts,
                                    workspacePackageRefs))
                            .ifPresent(target -> edges.get(ref).add(target));
                }
            }
        }

        List<SbomDependency> dependencies = new ArrayList<>();
        for (var edge : edges.entrySet()) {
            dependencies.add(new SbomDependency(edge.getKey(), List.copyOf(edge.getValue())));
        }
        return dependencies;
    }

    /** The variant-qualified edge ref that points at (and uniquely keys) this package. */
    private static String refOf(LockPackage lockPackage) {
        return LockDependencyEdge.of(lockPackage).encode();
    }

    private static String targetRef(
            LockPackage target,
            String member,
            WorkspaceSbomExternalContexts contexts,
            Map<String, String> workspacePackageRefs) {
        if (target.workspace().isPresent()) {
            return workspacePackageRefs.get(refOf(target));
        }
        return contexts.ref(target, member);
    }

    private SbomComponent rootComponent(String workspaceName) {
        // A workspace is not a published Maven artifact, so the root carries a non-purl bom-ref and no
        // group/version/purl (the writer omits blank identity fields).
        return new SbomComponent(
                SbomComponentType.APPLICATION,
                "workspace:" + workspaceName,
                "",
                workspaceName,
                "",
                "",
                SbomComponentScope.REQUIRED,
                List.of(),
                List.of());
    }

    private SbomComponent memberComponent(SbomWorkspaceMember member) {
        ProjectConfig config = member.config();
        String group = config.project().group();
        String name = config.project().name();
        String version = config.project().version();
        String extension = config.packageSettings().mode().artifactType();
        String purl = PurlWriter.purl(group, name, version, extension, Optional.empty());
        return new SbomComponent(
                SbomComponentType.LIBRARY,
                purl,
                group,
                name,
                version,
                purl,
                SbomComponentScope.REQUIRED,
                List.of(),
                LockSbomAssembler.emittableLicenses(memberLicenses(config)));
    }

    private List<SbomLicense> memberLicenses(ProjectConfig config) {
        String license = config.packageSettings().metadata().license();
        String licenseUrl = config.packageSettings().metadata().licenseUrl();
        Optional<String> name = license.isBlank() ? Optional.empty() : Optional.of(license);
        Optional<String> url = licenseUrl.isBlank() ? Optional.empty() : Optional.of(licenseUrl);
        if (name.isEmpty() && url.isEmpty()) {
            return List.of();
        }
        return List.of(licenseMapping.spdxId(name, url)
                .map(SbomLicense::spdx)
                .orElseGet(() -> SbomLicense.unmapped(name, url)));
    }

    private String serialNumber(String rootRef, ZoltLockfile lockfile, List<SbomComponent> components) {
        String seed = lockfile.projectResolutionFingerprint()
                .filter(fingerprint -> !fingerprint.isBlank())
                .orElseGet(() -> SbomSerialNumber.fallbackSeed(
                        rootRef, components.stream().map(SbomComponent::purl).toList()));
        return SbomSerialNumber.serialNumber(seed);
    }

    private static final class ExternalAccumulator {
        private final String group;
        private final String name;
        private final String version;
        private final String bomRef;
        private final String purl;
        private final TreeSet<SbomHash> hashes = new TreeSet<>(
                Comparator.comparing(SbomHash::alg).thenComparing(SbomHash::content));
        private SbomComponentScope scope = SbomComponentScope.OPTIONAL;

        private ExternalAccumulator(
                String group,
                String name,
                String version,
                String bomRef,
                String purl) {
            this.group = group;
            this.name = name;
            this.version = version;
            this.bomRef = bomRef;
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
            return new SbomComponent(
                    SbomComponentType.LIBRARY, bomRef, group, name, version, purl,
                    scope, List.copyOf(hashes), licenses);
        }
    }

}
