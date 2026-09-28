package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.List;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.incremental.GeneratedOutputAttribution;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;

/** Preflights and executes non-javac main-language compilation. */
final class MainLanguageCompileExecutor {
    private final GroovyCompilerRunner groovyCompilerRunner;
    private final IncrementalCompileStateRecorder incrementalCompileStateRecorder;

    MainLanguageCompileExecutor(
            GroovyCompilerRunner groovyCompilerRunner,
            IncrementalCompileStateRecorder incrementalCompileStateRecorder) {
        this.groovyCompilerRunner = groovyCompilerRunner;
        this.incrementalCompileStateRecorder = incrementalCompileStateRecorder;
    }

    Plan preflight(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus,
            GroovyCompilerToolchain groovyToolchain) {
        GroovyCompilerRunner.JointOptions options = groovyOptions(
                config, sources, classpaths, jdkStatus);
        requireGroovyToolchain(options, groovyToolchain);
        return new Plan(options, groovyToolchain);
    }

    MainCompileSourceExecutor.Attempt compile(
            Plan plan,
            Path projectDirectory,
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            JdkStatus jdkStatus) {
        if (!plan.active()) {
            throw new IllegalArgumentException(
                    "Main language compilation requires an active preflight plan.");
        }
        List<Path> allSources = sources.allMainSources();
        String platformApiWarning = CompilerPlatformApi.determinismWarning(
                plan.groovyOptions().hostPlatformApi(), "main", jdkStatus);
        incrementalCompileStateRecorder.deleteMainState(outputDirectory);
        CompileOutputCleaner.resetMain(
                projectDirectory, config, outputDirectory, generatedSourcesDirectory);
        JavacResult result = groovyCompilerRunner.compileJoint(
                jdkStatus.java().orElseThrow(),
                allSources,
                plan.groovyToolchain().launcherClasspath(),
                classpaths.compile(),
                outputDirectory,
                plan.groovyOptions());
        return MainCompileSourceExecutor.withPlatformApiWarning(
                new MainCompileSourceExecutor.Attempt(
                        result,
                        "full",
                        "groovy-main-sources",
                        CompileDiagnostics.legacy(allSources.size(), false),
                        GeneratedOutputAttribution.absent(),
                        allSources),
                platformApiWarning);
    }

    private static GroovyCompilerRunner.JointOptions groovyOptions(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus) {
        if (sources.groovyMainSources().isEmpty()) {
            return null;
        }
        return GroovyJointCompilePolicy.options(
                config, sources.allMainSources(), classpaths, jdkStatus);
    }

    private static void requireGroovyToolchain(
            GroovyCompilerRunner.JointOptions options,
            GroovyCompilerToolchain groovyToolchain) {
        if (options != null && groovyToolchain == null) {
            throw new GroovyCompileException(
                    "Groovy main compilation requires a checksum-verified compiler toolchain before "
                            + "cached output can be reused or compile output can be cleaned. Resolve verified "
                            + "org.apache.groovy:groovy package metadata and retry.");
        }
    }

    record Plan(
            GroovyCompilerRunner.JointOptions groovyOptions,
            GroovyCompilerToolchain groovyToolchain) {
        boolean active() {
            return groovyOptions != null;
        }
    }
}
