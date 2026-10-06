package sh.zolt.build.generatedsource;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.BuildException;
import sh.zolt.build.generatedsource.ksp.KspMainGenerationCoordinator;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.generated.GeneratedSourceException;
import sh.zolt.generated.ProtobufGeneratedSourceService;
import sh.zolt.project.ProjectConfig;

/** Runs every main pre-compile generator in its dependency-safe order. */
public final class MainGeneratedSourceCoordinator {
    private final OpenApiGeneratedSourceService openApi;
    private final ProtobufGeneratedSourceService protobuf;
    private final ExecGeneratedSourceService exec;
    private final KspMainGenerationCoordinator ksp;

    public MainGeneratedSourceCoordinator(
            OpenApiGeneratedSourceService openApi,
            ProtobufGeneratedSourceService protobuf,
            ExecGeneratedSourceService exec,
            KspMainGenerationCoordinator ksp) {
        this.openApi = Objects.requireNonNull(openApi, "OpenAPI generator is required.");
        this.protobuf = Objects.requireNonNull(protobuf, "Protobuf generator is required.");
        this.exec = Objects.requireNonNull(exec, "Exec generator is required.");
        this.ksp = Objects.requireNonNull(ksp, "KSP generator is required.");
    }

    public void generateMain(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> packages,
            boolean offline) {
        openApi.generateMain(projectDirectory, config, packages);
        try {
            protobuf.generateMain(projectDirectory, config);
        } catch (GeneratedSourceException exception) {
            throw new BuildException(exception.getMessage(), exception);
        }
        exec.generateMain(projectDirectory, config, packages, offline);
        ksp.generate(projectDirectory, config, classpaths, packages);
    }
}
