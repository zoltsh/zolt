package sh.zolt.build.packaging;

import sh.zolt.build.BuildResult;
import sh.zolt.project.PackageMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record PackageResult(
        BuildResult buildResult,
        PackageMode mode,
        Path jarPath,
        Optional<Path> runtimeClasspathPath,
        Optional<Path> evidenceManifestPath,
        int entryCount,
        boolean hasMainClass,
        String applicationLayout,
        List<PackageArtifact> artifacts,
        List<PackageMergeDecision> mergeDecisions,
        List<PackageMaterializedInput> materializedInputs,
        boolean packagingReused) {
    public PackageResult {
        mode = mode == null ? PackageMode.THIN : mode;
        runtimeClasspathPath = runtimeClasspathPath == null ? Optional.empty() : runtimeClasspathPath;
        evidenceManifestPath = evidenceManifestPath == null ? Optional.empty() : evidenceManifestPath;
        applicationLayout = applicationLayout == null || applicationLayout.isBlank()
                ? defaultApplicationLayout(mode)
                : applicationLayout;
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        mergeDecisions = mergeDecisions == null ? List.of() : List.copyOf(mergeDecisions);
        materializedInputs = materializedInputs == null ? List.of() : List.copyOf(materializedInputs);
    }

    public PackageResult withArtifactsAndEvidence(
            List<PackageArtifact> artifacts,
            Optional<Path> evidenceManifestPath) {
        return new PackageResult(
                buildResult,
                mode,
                jarPath,
                runtimeClasspathPath,
                evidenceManifestPath,
                entryCount,
                hasMainClass,
                applicationLayout,
                artifacts,
                mergeDecisions,
                materializedInputs,
                packagingReused);
    }

    public PackageResult withApplicationLayout(String applicationLayout) {
        return new PackageResult(
                buildResult,
                mode,
                jarPath,
                runtimeClasspathPath,
                evidenceManifestPath,
                entryCount,
                hasMainClass,
                applicationLayout,
                artifacts,
                mergeDecisions,
                materializedInputs,
                packagingReused);
    }

    public PackageResult withMergeDecisions(List<PackageMergeDecision> mergeDecisions) {
        return new PackageResult(
                buildResult,
                mode,
                jarPath,
                runtimeClasspathPath,
                evidenceManifestPath,
                entryCount,
                hasMainClass,
                applicationLayout,
                artifacts,
                mergeDecisions,
                materializedInputs,
                packagingReused);
    }

    public PackageResult withMaterializedInputs(
            List<PackageMaterializedInput> materializedInputs) {
        return new PackageResult(
                buildResult,
                mode,
                jarPath,
                runtimeClasspathPath,
                evidenceManifestPath,
                entryCount,
                hasMainClass,
                applicationLayout,
                artifacts,
                mergeDecisions,
                materializedInputs,
                packagingReused);
    }

    public PackageResult(
            BuildResult buildResult,
            PackageMode mode,
            Path jarPath,
            Optional<Path> runtimeClasspathPath,
            Optional<Path> evidenceManifestPath,
            int entryCount,
            boolean hasMainClass,
            String applicationLayout,
            List<PackageArtifact> artifacts,
            List<PackageMergeDecision> mergeDecisions) {
        this(
                buildResult,
                mode,
                jarPath,
                runtimeClasspathPath,
                evidenceManifestPath,
                entryCount,
                hasMainClass,
                applicationLayout,
                artifacts,
                mergeDecisions,
                List.of(),
                false);
    }

    public PackageResult(
            BuildResult buildResult,
            PackageMode mode,
            Path jarPath,
            Optional<Path> runtimeClasspathPath,
            Optional<Path> evidenceManifestPath,
            int entryCount,
            boolean hasMainClass,
            List<PackageArtifact> artifacts) {
        this(
                buildResult,
                mode,
                jarPath,
                runtimeClasspathPath,
                evidenceManifestPath,
                entryCount,
                hasMainClass,
                defaultApplicationLayout(mode),
                artifacts,
                List.of(),
                List.of(),
                false);
    }

    public PackageResult(
            BuildResult buildResult,
            PackageMode mode,
            Path jarPath,
            Optional<Path> runtimeClasspathPath,
            int entryCount,
            boolean hasMainClass) {
        this(buildResult, mode, jarPath, runtimeClasspathPath, Optional.empty(), entryCount, hasMainClass, List.of());
    }

    public PackageResult(
            BuildResult buildResult,
            Path jarPath,
            Optional<Path> runtimeClasspathPath,
            int entryCount,
            boolean hasMainClass) {
        this(buildResult, PackageMode.THIN, jarPath, runtimeClasspathPath, entryCount, hasMainClass);
    }

    public PackageResult(
            BuildResult buildResult,
            Path jarPath,
            int entryCount,
            boolean hasMainClass) {
        this(buildResult, PackageMode.THIN, jarPath, Optional.empty(), entryCount, hasMainClass);
    }

    private static String defaultApplicationLayout(PackageMode mode) {
        return switch (mode == null ? PackageMode.THIN : mode) {
            case THIN, UBER -> "archive root";
            case SPRING_BOOT -> "BOOT-INF/classes";
            case WAR, SPRING_BOOT_WAR -> "WEB-INF/classes";
            case QUARKUS -> "framework package output";
            case BOM -> throw new IllegalStateException("BOM projects publish a POM and produce no package archive.");
        };
    }
}
