package sh.zolt.publish;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class PublishTestPackageEvidence {
    private PublishTestPackageEvidence() {
    }

    static void write(Path projectRoot) throws IOException {
        ProjectConfig config =
                new ManifestProjectConfigLoader().load(projectRoot.resolve("zolt.toml"));
        PackagePlan plan = new PackagePlanService().plan(
                projectRoot,
                config,
                new ZoltLockfileReader().read(
                        projectRoot.resolve("zolt.lock")));
        if (plan.runtimeClasspathPath().isPresent()) {
            Path sidecar = plan.runtimeClasspathPath().orElseThrow();
            Files.createDirectories(sidecar.getParent());
            Files.writeString(sidecar, "");
        }
        PackageResult result = new PackageResult(
                new BuildResult(
                        Optional.empty(),
                        0,
                        0,
                        projectRoot.resolve("target/classes"),
                        ""),
                plan.mode(),
                plan.archivePath(),
                plan.runtimeClasspathPath(),
                Optional.empty(),
                1,
                config.project().main().isPresent(),
                plan.applicationLayout(),
                List.of(),
                List.of());
        new PackageEvidenceManifestWriter().write(
                projectRoot,
                config,
                plan,
                result,
                List.of());
    }
}
