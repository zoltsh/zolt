package sh.zolt.build.packaging;

import sh.zolt.build.PackageException;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.error.ActionableError;
import sh.zolt.process.ProcessSupervisor;
import sh.zolt.process.SupervisedProcessResult;
import sh.zolt.process.SupervisedProcessSpec;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Resolves and checks the documentation tool before package outputs can change. */
final class PackageJavadocTool {
    private PackageJavadocTool() {}

    static Optional<Path> preflight(ProjectConfig config, JdkChecker checker) {
        if (!config.packageSettings().javadoc()) {
            return Optional.empty();
        }
        JdkStatus selected = checker.detect(config.project().java());
        if (!selected.ok()) {
            throw PackageException.actionable(
                    "Cannot generate Javadoc with the selected project JDK.",
                    String.join(" ", selected.problems()));
        }
        Path javac = selected.javac().orElseThrow().toAbsolutePath().normalize();
        Path javadoc = javac.resolveSibling(
                javac.getFileName().toString().endsWith(".exe") ? "javadoc.exe" : "javadoc");
        if (!Files.isRegularFile(javadoc) || !Files.isExecutable(javadoc)) {
            throw unavailable(javadoc, "The tool is missing or is not executable.", null);
        }
        try {
            // -J-version works with both older JDKs and current JDKs, without creating docs.
            SupervisedProcessResult result = new ProcessSupervisor().run(
                    SupervisedProcessSpec.builder(List.of(javadoc.toString(), "-J-version"))
                            .timeout(Duration.ofSeconds(15))
                            .build());
            if (result.exitCode() != 0 || result.timedOut()) {
                throw unavailable(javadoc,
                        result.timedOut() ? "The tool availability check timed out."
                                : "The tool availability check exited with code " + result.exitCode()
                                        + ". " + result.diagnosticTail().strip(),
                        null);
            }
        } catch (IOException exception) {
            throw unavailable(javadoc, "The tool could not be started.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(javadoc, "The tool availability check was interrupted.", exception);
        }
        return Optional.of(javadoc);
    }

    private static PackageException unavailable(Path executable, String detail, Throwable cause) {
        return new PackageException(ActionableError.of(
                "Cannot run the selected JDK's Javadoc tool at " + executable + ". " + detail,
                "Select or install a complete project JDK containing an executable javadoc tool, "
                        + "then retry packaging; or disable [package].javadoc.",
                cause));
    }
}
