package sh.zolt.build.incremental;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class IncrementalCompileStateFingerprint {
    private static final String COMPILER_IDENTITY = "compilerIdentity=";

    private IncrementalCompileStateFingerprint() {
    }

    static String compilerIdentity(Path fingerprintPath) {
        try {
            return Files.readAllLines(fingerprintPath).stream()
                    .filter(line -> line.startsWith(COMPILER_IDENTITY))
                    .map(line -> line.substring(COMPILER_IDENTITY.length()))
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElseThrow(() -> new BuildException(
                            "Build fingerprint at " + fingerprintPath + " has no compiler identity."));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read compiler identity from build fingerprint at " + fingerprintPath + ".",
                    exception);
        }
    }
}
