package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.NativeImageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.project.NativeSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class NativeBuildServiceDiagnosticsTest extends NativeBuildServiceTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"error-handler", "warning-service", "unsupported-message", "UnsupportedFeatureException"})
    void successfulImageNamesAndArtifactPathsAreNotDiagnostics(String name) throws IOException {
        NativeBuildService service = service(command -> {
            Path candidate = Path.of(command.getLast());
            writeNativeBinary(candidate);
            return new NativeImageRunner.ProcessResult(0, """
                    GraalVM Native Image: Generating '%s' (executable)...
                    Produced artifacts:
                     %s (executable)
                    Finished generating '%s' in 16.7s.
                    """.formatted(candidate.getFileName(), candidate, candidate.getFileName()));
        });
        NativeBuildResult result = service.buildNativeImage(
                projectDir,
                config(Optional.of("com.example.Main"),
                        new NativeSettings(name, "target/native-custom", List.of())),
                packageResult(),
                List.of(),
                Path.of("native-image"));

        assertEquals("native", Files.readString(result.nativeImageResult().outputBinary()));
        assertTrue(Files.isExecutable(result.nativeImageResult().outputBinary()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Warning: unsupported reflection configuration",
            "  WARNING: A restricted method has been called",
            "[native-image] Warning: missing reachability metadata",
            "[warning] missing reachability metadata",
            "[ERROR] missing configuration",
            "Error: unsupported configuration",
            "UnsupportedFeatureException: unsupported bytecode",
            "com.oracle.svm.core.util.UnsupportedFeatureException: unsupported bytecode",
            "Caused by: com.oracle.svm.core.util.UnsupportedFeatureException: unsupported bytecode",
            "Exception in thread \"main\" com.oracle.svm.core.util.UnsupportedFeatureException: unsupported bytecode",
            "Unsupported features detected",
            "\u001b[33mWarning: deprecated option\u001b[0m"
    })
    void actualDiagnosticsPreservePreviousBinary(String diagnostic) throws IOException {
        Path binary = projectDir.resolve("target/native-custom/demo-native");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "previous");
        NativeBuildService service = service(command -> {
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, diagnostic + "\n");
        });
        PackageResult packaged = packageResult();

        NativeImageException failure = assertThrows(NativeImageException.class,
                () -> service.buildNativeImage(
                        projectDir, config(Optional.of("com.example.Main")), packaged,
                        List.of(), Path.of("native-image")));

        assertTrue(failure.getMessage().contains(diagnostic));
        assertEquals("previous", Files.readString(binary));
    }

    private PackageResult packageResult() throws IOException {
        Path classes = projectDir.resolve("target/classes");
        Files.createDirectories(classes);
        Path jar = projectDir.resolve("target/demo-0.1.0.jar");
        Files.writeString(jar, "jar");
        return new PackageResult(
                new BuildResult(Optional.empty(), 1, 0, classes, ""), jar, 1, true);
    }
}
