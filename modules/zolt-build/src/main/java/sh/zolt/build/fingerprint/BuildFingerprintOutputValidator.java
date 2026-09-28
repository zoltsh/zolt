package sh.zolt.build.fingerprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Validates compiler outputs recorded by a persisted build fingerprint. */
public final class BuildFingerprintOutputValidator {
    private static final String MAIN_FILE_NAME = ".zolt-build-main.fingerprint";
    private static final String TEST_FILE_NAME = ".zolt-build-test.fingerprint";
    private final BuildFingerprintExpectedClasses expectedOutputs =
            new BuildFingerprintExpectedClasses();

    /** Whether the main fingerprint exists and every output recorded in it still exists. */
    public boolean mainOutputsCurrent(Path projectDirectory, Path outputDirectory) {
        return outputsCurrent(projectDirectory, outputDirectory, MAIN_FILE_NAME);
    }

    /** Whether the test fingerprint exists and every output recorded in it still exists. */
    public boolean testOutputsCurrent(Path projectDirectory, Path outputDirectory) {
        return outputsCurrent(projectDirectory, outputDirectory, TEST_FILE_NAME);
    }

    private boolean outputsCurrent(
            Path projectDirectory,
            Path outputDirectory,
            String fingerprintFileName) {
        Path fingerprint = outputDirectory.toAbsolutePath().normalize().resolve(fingerprintFileName);
        if (!Files.isRegularFile(fingerprint, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return expectedOutputs.recordedOutputsCurrent(
                    projectDirectory.toAbsolutePath().normalize(),
                    Files.readString(fingerprint, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return false;
        }
    }
}
