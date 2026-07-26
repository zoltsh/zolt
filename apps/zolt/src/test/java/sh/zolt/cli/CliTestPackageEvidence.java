package sh.zolt.cli;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanOutput;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageArtifact;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CliTestPackageEvidence {
    private CliTestPackageEvidence() {
    }

    public static void write(Path projectRoot) throws IOException {
        ProjectConfig config =
                new ZoltTomlParser().parse(projectRoot.resolve("zolt.toml"));
        PackagePlan plan = new PackagePlanService().plan(
                projectRoot,
                config,
                new ZoltLockfileReader().read(
                        projectRoot.resolve("zolt.lock")));
        plan.runtimeClasspathPath().ifPresent(path -> write(path, ""));
        List<PackageArtifact> artifacts = new ArrayList<>();
        for (PackagePlanOutput output : plan.evidence().outputs()) {
            if ("main".equals(output.kind())
                    || "runtime-classpath".equals(output.kind())) {
                continue;
            }
            if (!Files.isRegularFile(output.path())) {
                write(output.path(), output.kind() + "\n");
            }
            artifacts.add(new PackageArtifact(
                    output.kind(),
                    output.path(),
                    1));
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
                artifacts,
                List.of());
        new PackageEvidenceManifestWriter().write(
                projectRoot,
                config,
                plan,
                result,
                artifacts);
    }

    private static void write(Path path, String value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
