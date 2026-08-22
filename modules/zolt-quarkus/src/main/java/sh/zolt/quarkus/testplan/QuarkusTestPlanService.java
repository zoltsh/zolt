package sh.zolt.quarkus.testplan;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.quarkus.QuarkusPlanException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class QuarkusTestPlanService {
    private final UnsupportedTestScanner unsupportedTestScanner;

    public QuarkusTestPlanService() {
        this(new QuarkusUnsupportedTestScanner()::scan);
    }

    QuarkusTestPlanService(UnsupportedTestScanner unsupportedTestScanner) {
        if (unsupportedTestScanner == null) {
            throw new QuarkusPlanException("Quarkus unsupported test scanner is required.");
        }
        this.unsupportedTestScanner = unsupportedTestScanner;
    }

    public QuarkusTestPlan plan(Path projectDirectory, ProjectConfig config) {
        if (projectDirectory == null) {
            throw new QuarkusPlanException("Quarkus test plan requires a project directory.");
        }
        if (config == null) {
            throw new QuarkusPlanException("Quarkus test plan requires a project config.");
        }
        if (!config.frameworkSettings().quarkus().enabled()) {
            throw new QuarkusPlanException(
                    "Quarkus is not enabled for this project. "
                            + "Add `[package] mode = \"quarkus\"` to zolt.toml, "
                            + "run `zolt resolve`, then run `zolt quarkus test-plan` again.");
        }
        Path root = projectDirectory.toAbsolutePath().normalize();
        Path testOutputDirectory = outputPath(root, "[build.output].test", config.build().testOutput());
        Path quarkusDirectory = outputPath(
                root,
                "Quarkus test output",
                config.build().outputRoot() + "/quarkus");
        return new QuarkusTestPlan(
                root,
                testOutputDirectory,
                Files.isDirectory(testOutputDirectory),
                quarkusDirectory.resolve("test-application-model.dat"),
                quarkusDirectory.resolve("zolt-test-bootstrap.properties"),
                unsupportedTestScanner.scan(testOutputDirectory));
    }

    private static Path outputPath(Path root, String key, String configuredPath) {
        try {
            return ProjectPaths.output(root, key, configuredPath);
        } catch (ProjectPathException exception) {
            throw new QuarkusPlanException(exception.getMessage(), exception);
        }
    }

    @FunctionalInterface
    interface UnsupportedTestScanner {
        java.util.List<QuarkusUnsupportedTest> scan(Path testOutputDirectory);
    }
}
