package sh.zolt.build.testruntime.compile;

import java.nio.file.Path;
import java.util.List;
import sh.zolt.build.compile.EffectiveCompilerIdentity;
import sh.zolt.build.compile.GroovyCompilerToolchain;
import sh.zolt.build.compile.GroovyCompilerToolchainResolver;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.build.compile.KotlinCompilerToolchain;
import sh.zolt.build.compile.KotlinCompilerToolchainResolver;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;

/** Resolves the isolated compiler runtime selected for the test source-set language. */
record TestCompilerSelection(
        String identity,
        Classpath groovyLauncherClasspath,
        Classpath kotlinLauncherClasspath,
        KotlinCompilerOptions kotlinOptions) {
    static TestCompilerSelection select(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> classpathPackages,
            JdkStatus jdkStatus,
            Path mainOutputDirectory) {
        KotlinCompilerOptions kotlinOptions = sources.kotlinTestSources().isEmpty()
                ? null
                : KotlinTestCompilePolicy.options(
                        config,
                        sources,
                        classpaths,
                        jdkStatus,
                        mainOutputDirectory);
        KotlinCompilerToolchain kotlin = sources.kotlinTestSources().isEmpty()
                ? null
                : new KotlinCompilerToolchainResolver().resolve(
                        classpathPackages,
                        config.compilerSettings().kotlinVersion(),
                        KotlinCompilationScope.TEST);
        GroovyCompilerToolchain groovy = sources.groovyTestSources().isEmpty()
                ? null
                : new GroovyCompilerToolchainResolver().resolve(
                        classpathPackages,
                        GroovyCompilerToolchainResolver.SourceSet.TEST,
                        config.compilerSettings().groovyVersion());
        return new TestCompilerSelection(
                identity(jdkStatus, groovy, kotlin),
                groovy == null ? emptyClasspath() : groovy.launcherClasspath(),
                kotlin == null ? emptyClasspath() : kotlin.launcherClasspath(),
                kotlinOptions);
    }

    private static String identity(
            JdkStatus jdkStatus,
            GroovyCompilerToolchain groovy,
            KotlinCompilerToolchain kotlin) {
        if (kotlin != null) {
            return EffectiveCompilerIdentity.of(
                    jdkStatus,
                    "kotlinCompiler",
                    kotlin.identity(),
                    "kotlin-test-v2-friend-main");
        }
        return groovy == null
                ? EffectiveCompilerIdentity.of(jdkStatus)
                : EffectiveCompilerIdentity.of(jdkStatus, groovy);
    }

    private static Classpath emptyClasspath() {
        return new Classpath(List.of());
    }
}
