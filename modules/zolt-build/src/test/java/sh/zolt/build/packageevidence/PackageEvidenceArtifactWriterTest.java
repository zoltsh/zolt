package sh.zolt.build.packageevidence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanEvidence;
import sh.zolt.build.packageplan.PackagePlanOutput;
import sh.zolt.build.packaging.PackageArtifact;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.project.PackageMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageEvidenceArtifactWriterTest {
    @TempDir
    private Path projectDir;

    @Test
    void writesMainArtifactThenClassifierSortedSupplementalArtifacts() throws IOException {
        Path classes = projectDir.resolve("target/classes");
        Path main = projectDir.resolve("target/demo-0.1.0.jar");
        Path sources = projectDir.resolve("target/demo-0.1.0-sources.jar");
        Path javadoc = projectDir.resolve("target/demo-0.1.0-javadoc.jar");
        Files.createDirectories(main.getParent());
        Files.writeString(main, "main artifact");
        Files.writeString(sources, "sources artifact");
        Files.writeString(javadoc, "javadoc artifact");
        PackageResult result = new PackageResult(
                new BuildResult(Optional.empty(), 0, 0, classes, ""),
                PackageMode.THIN,
                main,
                Optional.empty(),
                Optional.empty(),
                7,
                true,
                List.of());
        PackagePlan plan = new PackagePlan(
                projectDir,
                PackageMode.THIN,
                main,
                classes,
                "archive root",
                Optional.empty(),
                List.of(),
                List.of(),
                new PackagePlanEvidence(
                        "test",
                        "test",
                        "test",
                        "test",
                        "test",
                        "test",
                        List.of(
                                new PackagePlanOutput("main", main, "file", "thin"),
                                new PackagePlanOutput("javadoc", javadoc, "file", "jar"),
                                new PackagePlanOutput("sources", sources, "file", "jar")),
                        List.of(),
                        List.of(),
                        List.of()));

        StringBuilder json = new StringBuilder();
        PackageEvidenceArtifactWriter.write(json, projectDir, plan, result, List.of(
                new PackageArtifact("sources", sources, 3),
                new PackageArtifact("javadoc", javadoc, 2)), new PackageArchiveDigests());
        String artifacts = json.toString();
        int mainIndex = artifacts.indexOf("\"classifier\": \"main\"");
        int javadocIndex = artifacts.indexOf("\"classifier\": \"javadoc\"");
        int sourcesIndex = artifacts.indexOf("\"classifier\": \"sources\"");

        assertTrue(mainIndex >= 0);
        assertTrue(javadocIndex > mainIndex);
        assertTrue(sourcesIndex > javadocIndex);
        assertTrue(artifacts.contains("\"type\": \"thin\""));
        assertTrue(artifacts.contains("\"entries\": 7"));
        assertTrue(artifacts.contains("\"path\": \"target/demo-0.1.0-javadoc.jar\""));
        assertTrue(artifacts.contains("\"sha256\": \"sha256:"));
    }
}
