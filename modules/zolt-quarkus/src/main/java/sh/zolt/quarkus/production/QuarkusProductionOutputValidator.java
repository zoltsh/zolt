package sh.zolt.quarkus.production;

import sh.zolt.quarkus.QuarkusAugmentationException;
import sh.zolt.quarkus.bootstrap.descriptor.QuarkusBootstrapDescriptor;
import java.nio.file.Path;

public final class QuarkusProductionOutputValidator {
    private static final String FAST_JAR = "fast-jar";
    private static final String FAST_JAR_RUNNER = "quarkus-run.jar";

    public void validate(QuarkusBootstrapDescriptor descriptor, QuarkusProductionApplicationSummary summary) {
        if (descriptor == null) {
            throw new QuarkusAugmentationException("Quarkus bootstrap descriptor is required.");
        }
        if (summary == null) {
            throw new QuarkusAugmentationException("Quarkus production application summary is required.");
        }
        if (!FAST_JAR.equals(descriptor.packageMode())) {
            throw new QuarkusAugmentationException(
                    "Quarkus package mode "
                            + descriptor.packageMode()
                            + " is not supported yet. Zolt currently supports fast-jar only.");
        }
        if (!summary.hasJar()) {
            throw new QuarkusAugmentationException(
                    "Quarkus production application did not report a jar result. "
                            + "Check the Quarkus package configuration and try again.");
        }
        if (summary.uberJar()) {
            throw new QuarkusAugmentationException(
                    "Quarkus production application reported an uber jar, but Zolt expected fast-jar output. "
                            + "[package] mode = \"quarkus\" builds the fast-jar layout; remove the "
                            + "Quarkus uber-jar configuration and try again.");
        }

        Path packageDirectory = normalize(descriptor.packageDirectory());
        Path expectedRunner = packageDirectory.resolve(FAST_JAR_RUNNER).normalize();
        Path actualRunner = normalize(summary.jarPath());
        if (!expectedRunner.equals(actualRunner)) {
            throw new QuarkusAugmentationException(
                    "Quarkus production application reported runner jar "
                            + actualRunner
                            + " but Zolt expected "
                            + expectedRunner
                            + ". Check the Quarkus package output layout.");
        }
        if (summary.libraryDirectory() != null) {
            Path libraryDirectory = normalize(summary.libraryDirectory());
            if (!libraryDirectory.startsWith(packageDirectory)) {
                throw new QuarkusAugmentationException(
                        "Quarkus production application reported library directory "
                                + libraryDirectory
                                + " outside planned package directory "
                                + packageDirectory
                                + ". Check the Quarkus package output layout.");
            }
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
