package sh.zolt.resolve.lockfile.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;

/** Forms member-local lock roots from the eight authored dependency lanes. */
public final class AuthoredDependencyRootPlanner {
    private final CoordinateParser coordinateParser;

    public AuthoredDependencyRootPlanner(CoordinateParser coordinateParser) {
        this.coordinateParser = coordinateParser;
    }

    public List<LockDependencyRoot> plan(ProjectConfig config, List<LockPackage> packages) {
        List<Declaration> declarations = declarations(config);
        List<LockDependencyRoot> roots = new ArrayList<>();
        for (Declaration declaration : declarations) {
            if (declaration.publishOnly()) {
                roots.add(declaration.publishOnlyRoot());
                continue;
            }
            List<LockPackage> selected = packages.stream()
                    .filter(declaration::selectsIdentity)
                    .toList();
            if (selected.isEmpty()) {
                throw ResolveException.actionable(
                        "Could not find the selected package for authored dependency `"
                                + declaration.packageId() + "` in ["
                                + DependencyMetadata.manifestSection(declaration.section()) + "].",
                        "Run `zolt resolve` again; if the problem persists, replace the declaration with an exact released coordinate.");
            }
            String version = selected.getFirst().version();
            if (selected.stream().anyMatch(lockPackage -> !version.equals(lockPackage.version()))) {
                throw ResolveException.actionable(
                        "Authored dependency `" + declaration.packageId() + "` in ["
                                + DependencyMetadata.manifestSection(declaration.section())
                                + "] selected more than one version in the same artifact variant and scope.",
                        "Add an exact dependency constraint, then run `zolt resolve` again.");
            }
            roots.add(declaration.resolvedRoot(version));
        }
        return List.copyOf(roots);
    }

    public void requireNoDirectRelocation(
            ProjectConfig config,
            DependencyRequest original,
            DependencyRequest relocated,
            String retryCommand) {
        if (sameCoordinate(original, relocated)) {
            return;
        }
        declarations(config).stream()
                .filter(declaration -> !declaration.publishOnly())
                .filter(declaration -> declaration.matches(original))
                .findFirst()
                .ifPresent(declaration -> {
                    String replacement = relocated.packageId() + ":" + relocated.requestedVersion();
                    throw ResolveException.actionable(
                            "Direct dependency `" + original.packageId() + ":" + original.requestedVersion()
                                    + "` in [" + DependencyMetadata.manifestSection(declaration.section())
                                    + "] relocates to `" + replacement
                                    + "`, which cannot preserve the exact authored dependency-root identity required by lockfile version 7.",
                            "Replace the declaration with `" + replacement + "`, then run `" + retryCommand + "` again.");
                });
    }

    private List<Declaration> declarations(ProjectConfig config) {
        List<Declaration> values = new ArrayList<>();
        add(values, config, "api.dependencies", DependencyLane.API, DependencyScope.COMPILE,
                config.apiDependencies(), config.managedApiDependencies());
        add(values, config, "dependencies", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE,
                config.dependencies(), config.managedDependencies());
        add(values, config, "runtime.dependencies", DependencyLane.RUNTIME, DependencyScope.RUNTIME,
                config.runtimeDependencies(), config.managedRuntimeDependencies());
        add(values, config, "provided.dependencies", DependencyLane.PROVIDED, DependencyScope.PROVIDED,
                config.providedDependencies(), config.managedProvidedDependencies());
        add(values, config, "dev.dependencies", DependencyLane.DEV, DependencyScope.DEV,
                config.devDependencies(), config.managedDevDependencies());
        add(values, config, "test.dependencies", DependencyLane.TEST, DependencyScope.TEST,
                config.testDependencies(), config.managedTestDependencies());
        add(values, config, "annotationProcessors", DependencyLane.PROCESSOR, DependencyScope.PROCESSOR,
                config.annotationProcessors(), config.managedAnnotationProcessors());
        add(values, config, "test.annotationProcessors", DependencyLane.TEST_PROCESSOR, DependencyScope.TEST_PROCESSOR,
                config.testAnnotationProcessors(), config.managedTestAnnotationProcessors());
        config.dependencyMetadata().values().stream()
                .filter(DependencyMetadata::publishOnly)
                .forEach(metadata -> values.add(publishOnly(config, metadata)));
        return List.copyOf(values);
    }

    private void add(
            List<Declaration> declarations,
            ProjectConfig config,
            String section,
            DependencyLane lane,
            DependencyScope scope,
            Map<String, String> fixed,
            Iterable<String> managed) {
        fixed.keySet().forEach(coordinate -> declarations.add(
                declaration(config, section, lane, scope, coordinate, false, null)));
        for (String coordinate : managed) {
            declarations.add(declaration(config, section, lane, scope, coordinate, false, null));
        }
    }

    private Declaration publishOnly(ProjectConfig config, DependencyMetadata metadata) {
        if (metadata.managed() || metadata.version() == null) {
            throw ResolveException.actionable(
                    "Publish-only dependency `" + metadata.coordinate() + "` in [" + metadata.manifestSection()
                            + "] has no fixed or version-reference value.",
                    "Give it `version` or `versionRef`; platform-managed dependencies must participate in resolution.");
        }
        return declaration(
                config,
                metadata.section(),
                lane(metadata.section()),
                scope(metadata.section()),
                metadata.coordinate(),
                true,
                metadata.version());
    }

    private Declaration declaration(
            ProjectConfig config,
            String section,
            DependencyLane lane,
            DependencyScope scope,
            String coordinate,
            boolean publishOnly,
            String publishVersion) {
        PackageId packageId = PackageId.from(coordinateParser.parse(coordinate));
        DependencyMetadata metadata = config.dependencyMetadata()
                .get(DependencyMetadata.key(section, coordinate));
        return new Declaration(
                section,
                packageId,
                variant(metadata),
                lane,
                scope,
                metadata != null && metadata.optional(),
                publishOnly,
                publishVersion);
    }

    private static LockArtifactVariant variant(DependencyMetadata metadata) {
        return metadata == null
                ? LockArtifactVariant.defaultVariant()
                : new LockArtifactVariant(
                        metadata.type() == null ? "jar" : metadata.type(),
                        Optional.ofNullable(metadata.classifier()));
    }

    private static DependencyLane lane(String section) {
        return switch (section) {
            case "api.dependencies" -> DependencyLane.API;
            case "dependencies" -> DependencyLane.IMPLEMENTATION;
            case "runtime.dependencies" -> DependencyLane.RUNTIME;
            case "provided.dependencies" -> DependencyLane.PROVIDED;
            case "dev.dependencies" -> DependencyLane.DEV;
            case "test.dependencies" -> DependencyLane.TEST;
            case "annotationProcessors" -> DependencyLane.PROCESSOR;
            case "test.annotationProcessors" -> DependencyLane.TEST_PROCESSOR;
            default -> throw new ResolveException("Unsupported dependency section `" + section + "`.");
        };
    }

    private static DependencyScope scope(String section) {
        return switch (section) {
            case "api.dependencies", "dependencies" -> DependencyScope.COMPILE;
            case "runtime.dependencies" -> DependencyScope.RUNTIME;
            case "provided.dependencies" -> DependencyScope.PROVIDED;
            case "dev.dependencies" -> DependencyScope.DEV;
            case "test.dependencies" -> DependencyScope.TEST;
            case "annotationProcessors" -> DependencyScope.PROCESSOR;
            case "test.annotationProcessors" -> DependencyScope.TEST_PROCESSOR;
            default -> throw new ResolveException("Unsupported dependency section `" + section + "`.");
        };
    }

    private static boolean sameCoordinate(DependencyRequest left, DependencyRequest right) {
        return left.packageId().equals(right.packageId())
                && left.artifactVariant().equals(right.artifactVariant());
    }

    private record Declaration(
            String section,
            PackageId packageId,
            LockArtifactVariant variant,
            DependencyLane lane,
            DependencyScope scope,
            boolean optional,
            boolean publishOnly,
            String publishVersion) {
        boolean matches(DependencyRequest request) {
            return packageId.equals(request.packageId())
                    && scope == request.scope()
                    && variant.equals(request.artifactVariant());
        }

        boolean selectsIdentity(LockPackage lockPackage) {
            return packageId.equals(lockPackage.packageId())
                    && scope == lockPackage.scope()
                    && variant.equals(LockArtifactVariant.of(lockPackage));
        }

        LockDependencyRoot resolvedRoot(String selectedVersion) {
            return new LockDependencyRoot(
                    ".", packageId, selectedVersion, variant, lane, Optional.of(scope), optional, false);
        }

        LockDependencyRoot publishOnlyRoot() {
            return new LockDependencyRoot(
                    ".", packageId, publishVersion, variant, lane, Optional.empty(), optional, true);
        }
    }
}
