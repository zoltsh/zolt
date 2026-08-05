package sh.zolt.build.packageplan;

import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackageWorkspaceInputPlanner {
    private PackageWorkspaceInputPlanner() {
    }

    static List<PackagePlanWorkspaceInput> workspaceInputs(
            Path projectRoot,
            List<LockPackage> packages) {
        return workspaceInputs(
                projectRoot,
                packages,
                new PackageOutputFingerprintIndex());
    }

    static List<PackagePlanWorkspaceInput> workspaceInputs(
            Path projectRoot,
            List<LockPackage> packages,
            PackageOutputFingerprintIndex outputFingerprints) {
        Map<String, PackagePlanWorkspaceInput> inputs = new LinkedHashMap<>();
        packages.stream()
                .filter(lockPackage -> lockPackage.workspace().isPresent()
                        && lockPackage.workspaceOutput().isPresent())
                .filter(PackageWorkspaceInputPlanner::entersPackageBuild)
                .sorted(Comparator.comparing(lockPackage ->
                        NestedArtifactIdentity.of(lockPackage).coordinate()))
                .forEach(lockPackage -> {
                    String workspace = lockPackage.workspace().orElseThrow();
                    String output = lockPackage.workspaceOutput().orElseThrow();
                    Path source = sourceDirectory(projectRoot, workspace, output, outputFingerprints);
                    NestedArtifactIdentity artifactIdentity =
                            NestedArtifactIdentity.of(lockPackage);
                    String coordinate = artifactIdentity.coordinate();
                    inputs.putIfAbsent(coordinate, new PackagePlanWorkspaceInput(
                            coordinate,
                            "workspace:" + normalize(workspace) + "/" + normalize(output),
                            artifactIdentity,
                            source,
                            outputFingerprints.fingerprint(source)));
                });
        return List.copyOf(inputs.values());
    }

    static List<PackagePlanMaterializedInput> materializedInputs(
            Path projectRoot,
            ProjectConfig config,
            List<PackagePlanDependency> dependencies,
            List<PackagePlanWorkspaceInput> workspaceInputs) {
        PackageMode mode = config.packageSettings().mode();
        if (mode != PackageMode.SPRING_BOOT
                && mode != PackageMode.WAR
                && mode != PackageMode.SPRING_BOOT_WAR) {
            return List.of();
        }
        Map<String, PackagePlanDependency> dependenciesByCoordinate = new LinkedHashMap<>();
        for (PackagePlanDependency dependency : dependencies) {
            dependenciesByCoordinate.put(dependency.coordinate(), dependency);
        }
        Path staging = ProjectPaths.output(
                projectRoot,
                "package runtime input staging",
                config.build().outputRoot() + "/zolt-package/runtime-inputs");
        List<PackagePlanMaterializedInput> materialized = new ArrayList<>();
        for (PackagePlanWorkspaceInput input : workspaceInputs) {
            PackagePlanDependency dependency = dependenciesByCoordinate.get(input.coordinate());
            if (dependency == null || !materializedDisposition(dependency.disposition())) {
                continue;
            }
            materialized.add(new PackagePlanMaterializedInput(
                    input.coordinate(),
                    input.identity(),
                    input.sourceDirectory(),
                    input.fingerprint(),
                    staging.resolve(input.artifactIdentity().nestedJarName())));
        }
        return List.copyOf(materialized);
    }

    private static boolean materializedDisposition(String disposition) {
        return "included".equals(disposition)
                || "loader".equals(disposition)
                || "provided".equals(disposition);
    }

    private static boolean entersPackageBuild(LockPackage lockPackage) {
        DependencyScope scope = lockPackage.scope();
        return scope.entersMainCompileClasspath()
                || scope.entersMainRuntimeClasspath()
                || scope.entersMainProcessorClasspath()
                || scope == DependencyScope.QUARKUS_DEPLOYMENT;
    }

    static Path sourceDirectory(
            Path projectRoot,
            String workspace,
            String output) {
        return sourceDirectory(projectRoot, workspace, output, new PackageOutputFingerprintIndex());
    }

    static Path sourceDirectory(
            Path projectRoot,
            String workspace,
            String output,
            PackageOutputFingerprintIndex directories) {
        Path ancestor = projectRoot;
        while (ancestor != null) {
            Path member = ancestor.resolve(workspace).normalize();
            Path candidate = member.resolve(output).normalize();
            if (directories.directoryExists(candidate)) {
                return ProjectPaths.output(member, "workspaceOutput", output);
            }
            ancestor = ancestor.getParent();
        }
        ancestor = projectRoot;
        while (ancestor != null) {
            Path member = ancestor.resolve(workspace).normalize();
            if (directories.directoryExists(member)) {
                return ProjectPaths.output(member, "workspaceOutput", output);
            }
            ancestor = ancestor.getParent();
        }
        Path fallbackRoot = projectRoot.getParent() == null
                ? projectRoot
                : projectRoot.getParent();
        return fallbackRoot.resolve(workspace).resolve(output).toAbsolutePath().normalize();
    }

    static Path sourceDirectory(Path projectRoot, LockPackage lockPackage) {
        return sourceDirectory(
                projectRoot,
                lockPackage.workspace().orElseThrow(),
                lockPackage.workspaceOutput().orElseThrow());
    }

    private static String normalize(String value) {
        return Path.of(value).normalize().toString().replace('\\', '/');
    }
}
