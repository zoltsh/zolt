package sh.zolt.build.fingerprint;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class StoredBuildFingerprintInputs {
    private StoredBuildFingerprintInputs() {
    }

    static String read(
            Path outputDirectory,
            String fileName,
            String scope,
            String refreshCommand) {
        Path fingerprintPath = outputDirectory
                .toAbsolutePath()
                .normalize()
                .resolve(fileName);
        if (!Files.isRegularFile(fingerprintPath)) {
            return "missing";
        }
        try {
            return "sha256:"
                    + BuildFingerprintInputs.inputsSha256(
                            Files.readString(fingerprintPath));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read the canonical "
                            + scope
                            + " build input fingerprint at "
                            + fingerprintPath
                            + ". Run `"
                            + refreshCommand
                            + "` to refresh it.",
                    exception);
        }
    }
}
