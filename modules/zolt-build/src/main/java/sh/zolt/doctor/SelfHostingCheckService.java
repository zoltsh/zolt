package sh.zolt.doctor;

import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SelfHostingCheckService {
    private static final String JUNIT_PLATFORM_CONSOLE = "org.junit.platform:junit-platform-console-standalone";

    private final ManifestProjectConfigLoader manifestLoader;

    public SelfHostingCheckService() {
        this(new ManifestProjectConfigLoader());
    }

    SelfHostingCheckService(ManifestProjectConfigLoader manifestLoader) {
        this.manifestLoader = manifestLoader;
    }

    public SelfHostingCheckResult check(Path projectDirectory) {
        Path root = projectDirectory.toAbsolutePath().normalize();
        ProjectConfig config = manifestLoader.load(root.resolve("zolt.toml"));
        return check(root, config);
    }

    public SelfHostingCheckResult check(Path projectDirectory, ProjectConfig config) {
        Path root = projectDirectory.toAbsolutePath().normalize();
        List<SelfHostingCheckResult.SelfHostingCheck> checks = new ArrayList<>();
        add(checks, "main class",
                config.project().main().isPresent(),
                "project main is " + config.project().main().orElse("<missing>"),
                "add [project].main so Zolt can run and package itself");
        add(checks, "lockfile",
                Files.isRegularFile(root.resolve("zolt.lock")),
                "zolt.lock exists",
                "run zolt resolve to create zolt.lock");
        add(checks, "main sources",
                config.build().sourceRoots().stream().anyMatch(source -> Files.isDirectory(root.resolve(source))),
                "at least one configured main source root exists",
                "create a configured main source root or update [build].sources");
        add(checks, "test sources",
                testSourceRoots(config).stream().anyMatch(testSource -> Files.isDirectory(root.resolve(testSource))),
                "at least one configured test source root exists",
                "create a configured test source root or update [test.sources]");
        add(checks, "JUnit Platform Console",
                declaresJUnitPlatformConsole(config),
                JUNIT_PLATFORM_CONSOLE + " is declared",
                "add " + JUNIT_PLATFORM_CONSOLE + " to [dependencies.test]");
        NativeSettings nativeSettings = config.nativeSettings().withDefaultImageName(config.project().name());
        add(checks, "native image name",
                nativeSettings.imageName() != null && !nativeSettings.imageName().isBlank(),
                "native image name is " + nativeSettings.imageName(),
                "add [native].imageName or rely on a non-empty project name");
        add(checks, "native output",
                nativeSettings.output() != null && !nativeSettings.output().isBlank(),
                "native output is " + nativeSettings.output(),
                "add [native].output");
        add(checks, "native no-fallback",
                nativeSettings.args().contains("--no-fallback"),
                "[native].args contains --no-fallback",
                "add --no-fallback to [native].args for release-grade self-hosting");
        return new SelfHostingCheckResult(checks);
    }

    private static boolean declaresJUnitPlatformConsole(ProjectConfig config) {
        return config.testDependencies().containsKey(JUNIT_PLATFORM_CONSOLE)
                || config.managedTestDependencies().contains(JUNIT_PLATFORM_CONSOLE);
    }

    private static List<String> testSourceRoots(ProjectConfig config) {
        List<String> roots = new ArrayList<>();
        roots.addAll(config.build().testSources());
        roots.addAll(config.build().groovyTestSources());
        return List.copyOf(roots);
    }

    private static void add(
            List<SelfHostingCheckResult.SelfHostingCheck> checks,
            String name,
            boolean ok,
            String okMessage,
            String failureMessage) {
        checks.add(new SelfHostingCheckResult.SelfHostingCheck(name, ok, ok ? okMessage : failureMessage));
    }
}
