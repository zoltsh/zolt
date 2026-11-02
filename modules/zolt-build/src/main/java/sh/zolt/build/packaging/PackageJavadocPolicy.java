package sh.zolt.build.packaging;

import sh.zolt.build.PackageException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Path;

final class PackageJavadocPolicy {
    private PackageJavadocPolicy() {
    }

    static void requireSupported(Path projectDirectory, ProjectConfig config) {
        if (!config.packageSettings().javadoc()) {
            return;
        }
        for (String configuredRoot : config.build().sourceRoots()) {
            Path sourceRoot = ProjectPaths.existingRoot(
                    projectDirectory,
                    "[build].sources",
                    configuredRoot);
            try {
                boolean kotlin = PackageSupplementalArtifactFiles.sourceArchiveFiles(sourceRoot).stream()
                        .anyMatch(path -> path.getFileName().toString().endsWith(".kt"));
                if (kotlin) {
                    throw PackageException.actionable(
                            "Cannot package a Javadoc JAR for Kotlin main sources; Kotlin/Dokka Javadoc "
                                    + "publication is outside the bounded Kotlin/JVM preview.",
                            "Disable [package].javadoc or generate and publish Dokka documentation through "
                                    + "an external qualified workflow.");
                }
            } catch (IOException exception) {
                throw new PackageException(
                        "Could not inspect Kotlin main sources before packaging Javadoc at "
                                + sourceRoot
                                + ". Check that the source tree is readable and retry.",
                        exception);
            }
        }
    }
}
