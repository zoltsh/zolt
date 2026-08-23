package sh.zolt.build.run;

import sh.zolt.build.BuildService;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.framework.FrameworkRunAugmenter;
import sh.zolt.framework.FrameworkRunResult;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.QuarkusPackageMode;
import sh.zolt.project.QuarkusSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

abstract class RunServiceTestSupport {
    @TempDir
    protected Path tempDir;

    protected RunService service(
            FrameworkRunAugmenter frameworkRunAugmenter,
            JavaRunner.ProcessRunner processRunner) {
        return service(frameworkRunAugmenter, processRunner, new JdkDetector());
    }

    protected RunService service(
            FrameworkRunAugmenter frameworkRunAugmenter,
            JavaRunner.ProcessRunner processRunner,
            JdkChecker jdkChecker) {
        return new RunService(
                new BuildService(jdkChecker),
                jdkChecker,
                new JavaRunner(":", processRunner),
                frameworkRunAugmenter);
    }

    protected static FrameworkRunAugmenter frameworkRunAugmenter(
            boolean enabled,
            Optional<FrameworkRunResult> result,
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot) {
        return new FrameworkRunAugmenter() {
            @Override
            public Optional<FrameworkRunResult> augmentIfEnabled(
                    ProjectBuildContext actualContext,
                    ProjectConfig actualConfig,
                    Path actualCacheRoot) {
                org.junit.jupiter.api.Assertions.assertEquals(
                        projectDirectory.toAbsolutePath().normalize(), actualContext.projectRoot());
                // The standalone run lane owns its lock, so the adapter is handed that path — never
                // asked to find one (design §4.5).
                org.junit.jupiter.api.Assertions.assertEquals(
                        projectDirectory.toAbsolutePath().normalize().resolve("zolt.lock"),
                        actualContext.lockfilePath());
                org.junit.jupiter.api.Assertions.assertEquals(config, actualConfig);
                org.junit.jupiter.api.Assertions.assertEquals(cacheRoot, actualCacheRoot);
                return result;
            }

            @Override
            public boolean isEnabled(ProjectConfig actualConfig) {
                org.junit.jupiter.api.Assertions.assertEquals(config, actualConfig);
                return enabled;
            }
        };
    }

    protected static ProjectConfig config(boolean quarkusEnabled, Optional<String> mainClass) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata("demo", "1.0.0", "com.example", currentJavaMajorVersion(), mainClass),
                        ProjectConfig.defaultRepositories(),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withFrameworkSettings(new FrameworkSettings(new QuarkusSettings(
                        quarkusEnabled,
                        QuarkusPackageMode.FAST_JAR)));
    }

    protected static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            return version.substring(2, 3);
        }
        int dot = version.indexOf('.');
        return dot >= 0 ? version.substring(0, dot) : version;
    }
}
