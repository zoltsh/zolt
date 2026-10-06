package sh.zolt.build.testruntime.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ExecGeneratedSourceService;
import sh.zolt.build.generatedsource.OpenApiGeneratedSourceService;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.generated.GeneratedSourceException;
import sh.zolt.generated.ProtobufGeneratedSourceService;
import sh.zolt.project.ProjectConfig;

/** Runs test generators in their compile-safe order without crowding test compilation orchestration. */
final class TestGeneratedSourceCoordinator {
    private final OpenApiGeneratedSourceService openApi;
    private final ProtobufGeneratedSourceService protobuf;
    private final ExecGeneratedSourceService exec;
    private final KspTestGenerationCoordinator ksp;

    TestGeneratedSourceCoordinator(
            OpenApiGeneratedSourceService openApi,
            ProtobufGeneratedSourceService protobuf,
            ExecGeneratedSourceService exec,
            KspTestGenerationCoordinator ksp) {
        this.openApi = Objects.requireNonNull(openApi, "OpenAPI generator is required.");
        this.protobuf = Objects.requireNonNull(protobuf, "Protobuf generator is required.");
        this.exec = Objects.requireNonNull(exec, "Exec generator is required.");
        this.ksp = Objects.requireNonNull(ksp, "KSP generator is required.");
    }

    void generatePreCompile(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> packages,
            Path mainOutputDirectory) {
        openApi.generateTest(projectDirectory, config, packages);
        try {
            protobuf.generateTest(projectDirectory, config);
        } catch (GeneratedSourceException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
        exec.generateTest(projectDirectory, config, packages);
        ksp.generate(projectDirectory, config, classpaths, packages, mainOutputDirectory);
    }

    void generatePostCompile(
            Path projectDirectory,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages) {
        exec.generateTestPostCompile(projectDirectory, config, packages, false);
    }
}
