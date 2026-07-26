package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageevidence.PackageEvidenceVerifier;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.framework.FrameworkPackagePlanDependency;
import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuarkusPackageEvidenceLayoutTest {
    @TempDir
    private Path projectDir;

    @Test
    void completeFastJarLayoutIsAnAuthoritativeDirectoryOutput()
            throws IOException {
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withPackageSettings(new PackageSettings(PackageMode.QUARKUS));
        PackagePlanService plans =
                new PackagePlanService(List.of(new FastJarRules()));
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(),
                List.of());
        Path classes = projectDir.resolve("target/classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("Main.class"), "application bytecode");
        Path layout = projectDir.resolve("target/quarkus-app");
        Path app = layout.resolve("app/application.dat");
        Path lib = layout.resolve("lib/runtime.jar");
        Path quarkus = layout.resolve("quarkus/generated.dat");
        Path runner = layout.resolve("quarkus-run.jar");
        write(app, "app");
        write(lib, "lib");
        write(quarkus, "quarkus");
        write(runner, "runner");
        PackagePlan plan = plans.plan(projectDir, config, lockfile);
        PackageResult result = new PackageResult(
                new BuildResult(Optional.empty(), 0, 0, classes, ""),
                PackageMode.QUARKUS,
                runner,
                Optional.empty(),
                Optional.empty(),
                4,
                true,
                plan.applicationLayout(),
                List.of(),
                List.of());
        Path evidence = new PackageEvidenceManifestWriter().write(
                projectDir,
                config,
                plan,
                result,
                List.of());
        assertCurrent(plans, config, lockfile, evidence);

        Files.writeString(app, "changed app");
        assertLayoutStale(plans, config, lockfile, evidence);
        Files.writeString(app, "app");

        Files.delete(lib);
        assertLayoutStale(plans, config, lockfile, evidence);
        write(lib, "lib");

        Files.writeString(quarkus, "changed quarkus");
        assertLayoutStale(plans, config, lockfile, evidence);
    }

    private void assertCurrent(
            PackagePlanService plans,
            ProjectConfig config,
            ZoltLockfile lockfile,
            Path evidence) {
        List<String> problems = verify(plans, config, lockfile, evidence);
        assertTrue(problems.isEmpty(), problems.toString());
    }

    private void assertLayoutStale(
            PackagePlanService plans,
            ProjectConfig config,
            ZoltLockfile lockfile,
            Path evidence) {
        List<String> problems = verify(plans, config, lockfile, evidence);
        assertTrue(
                problems.stream().anyMatch(problem ->
                        problem.contains("package output `quarkus-layout` changed")),
                problems.toString());
    }

    private List<String> verify(
            PackagePlanService plans,
            ProjectConfig config,
            ZoltLockfile lockfile,
            Path evidence) {
        PackagePlan current = plans.plan(projectDir, config, lockfile);
        return new PackageEvidenceVerifier()
                .verify(projectDir, current, evidence)
                .problems();
    }

    private static void write(Path path, String content)
            throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static final class FastJarRules
            implements FrameworkPackagePlanRules {
        @Override
        public String evidenceIdentity() {
            return "test-quarkus-fast-jar-v1";
        }

        @Override
        public boolean supports(PackageMode mode) {
            return mode == PackageMode.QUARKUS;
        }

        @Override
        public FrameworkPackagePlanDependency dependency(
                LockPackage lockPackage,
                ProjectConfig config) {
            throw new AssertionError("No dependencies are planned in this fixture.");
        }

        @Override
        public Path archivePath(
                Path projectRoot,
                ProjectConfig config) {
            return projectRoot.resolve("target/quarkus-app/quarkus-run.jar");
        }

        @Override
        public String applicationLayout(ProjectConfig config) {
            return "target/quarkus-app/app";
        }
    }
}
