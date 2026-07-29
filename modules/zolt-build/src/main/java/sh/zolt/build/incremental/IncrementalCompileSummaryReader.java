package sh.zolt.build.incremental;

import sh.zolt.build.BuildException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class IncrementalCompileSummaryReader {
    public Optional<IncrementalCompileSummary> readMain(Path outputDirectory) {
        return read(outputDirectory, IncrementalCompileState.mainStatePath(outputDirectory));
    }

    public Optional<IncrementalCompileSummary> readTest(Path outputDirectory) {
        return read(outputDirectory, IncrementalCompileState.testStatePath(outputDirectory));
    }

    private static Optional<IncrementalCompileSummary> read(
            Path outputDirectory,
            Path statePath) {
        if (!Files.isRegularFile(statePath)) {
            return Optional.empty();
        }
        try (BufferedReader reader = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
            Map<String, String> scalars = scalarFields(reader);
            if (!IncrementalCompileStateEncoding.VERSION.equals(scalars.get("version"))) {
                return Optional.empty();
            }
            Path recordedOutput = Path.of(
                            IncrementalCompileStateEncoding.decode(scalars.getOrDefault("outputDirectory", "")))
                    .toAbsolutePath()
                    .normalize();
            if (!recordedOutput.equals(outputDirectory.toAbsolutePath().normalize())) {
                return Optional.empty();
            }
            return Optional.of(new IncrementalCompileSummary(
                    scalars.get("publicAbiDigest"),
                    scalars.get("packagePrivateAbiDigest"),
                    scalars.get("outputManifestDigest")));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read incremental compile summary at "
                            + statePath
                            + ". Delete the file or run a full build to refresh it.",
                    exception);
        }
    }

    private static Map<String, String> scalarFields(BufferedReader reader) throws IOException {
        Map<String, String> fields = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && line.indexOf('\t') < 0) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                fields.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return fields;
    }
}
