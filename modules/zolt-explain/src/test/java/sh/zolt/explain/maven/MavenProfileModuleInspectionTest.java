package sh.zolt.explain.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.MigrationBlockerReportFormatter;
import sh.zolt.explain.MigrationBlockerReports;
import sh.zolt.explain.MigrationReadinessScorecard;
import sh.zolt.explain.MigrationReadinessScorecardFormatter;
import sh.zolt.explain.MigrationReadinessScorecards;
import sh.zolt.explain.emit.DraftEmit;
import sh.zolt.explain.emit.DraftWorkspace;
import sh.zolt.explain.emit.InspectionToManifest;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenProfileModuleInspectionTest {
    @TempDir
    private Path tempDir;

    @Test
    void profileDeclaredModulesAreReportedWithoutBecomingDefaultProjects() throws IOException {
        MavenInspectionResult result = inspectProfileModuleFixture();

        assertEquals(2, result.projects().size());
        assertTrue(result.projects().stream().noneMatch(project -> project.path().equals(Path.of("extra"))));
        MavenProjectInspection root = result.projects().stream()
                .filter(project -> project.path().equals(Path.of(".")))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("extra"), root.profiles().getFirst().modules());
        assertTrue(result.signals().stream().anyMatch(signal ->
                signal.id().equals("maven.profile.modules.detected")
                        && signal.message().contains("extras")
                        && signal.message().contains("extra")));
        assertTrue(result.signals().stream().anyMatch(signal ->
                signal.id().equals("maven.reactor.detected")
                        && signal.message().contains("1 top-level module")
                        && signal.message().contains("1 profile-declared module")
                        && signal.message().contains("extra")));

        String json = new MavenExplainFormatter().json(result);
        assertTrue(json.contains("\"modules\": [\"extra\"]"), () -> json);
    }

    @Test
    void workspaceEmitNotesProfileDeclaredModulesOmittedFromMembers() throws IOException {
        MavenInspectionResult result = inspectProfileModuleFixture();

        DraftEmit emit = new InspectionToManifest().emitFromMaven(result);
        DraftWorkspace workspace = assertInstanceOf(DraftWorkspace.class, emit);

        AuthoredWorkspace root = workspace.root().workspace().orElseThrow();
        assertEquals(
                List.of("app"),
                root.members().include().stream().map(WorkspaceMemberPattern::value).toList());
        // Omitting `default` is the implicit-all selection, which is exactly every drafted member.
        assertTrue(root.members().defaultMembers().isEmpty());
        assertTrue(workspace.notes().stream().anyMatch(note ->
                note.contains("profile `extras`")
                        && note.contains("extra")
                        && note.contains("omitted from workspace members")),
                () -> workspace.notes().toString());
    }

    @Test
    void scorecardAndBlockersSurfaceIncompleteProfileModuleCoverage() throws IOException {
        MigrationReadinessScorecard scorecard =
                MigrationReadinessScorecards.from(inspectProfileModuleFixture());
        String scorecardText = new MigrationReadinessScorecardFormatter().text(scorecard);
        String blockerText = new MigrationBlockerReportFormatter()
                .text(MigrationBlockerReports.from(scorecard));
        String blockerJson = new MigrationBlockerReportFormatter()
                .json(MigrationBlockerReports.from(scorecard));

        assertTrue(scorecardText.contains("profile-declared Maven modules omitted from workspace emit"),
                () -> scorecardText);
        assertTrue(scorecardText.contains("[workspace] reviewed members"), () -> scorecardText);
        assertTrue(scorecardText.contains("signal: maven.profile.modules.detected"), () -> scorecardText);
        assertTrue(blockerText.contains("profile-declared Maven modules omitted from workspace emit"),
                () -> blockerText);
        assertTrue(blockerJson.contains("\"signalId\": \"maven.profile.modules.detected\""), () -> blockerJson);
        assertTrue(blockerJson.contains("\"followUp\": \"\""), () -> blockerJson);
    }

    private MavenInspectionResult inspectProfileModuleFixture() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>app</module>
                  </modules>
                  <profiles>
                    <profile>
                      <id>extras</id>
                      <activation>
                        <jdk>[21,)</jdk>
                      </activation>
                      <modules>
                        <module>extra</module>
                      </modules>
                    </profile>
                  </profiles>
                </project>
                """);
        writeChildPom("app");
        writeChildPom("extra");
        return new MavenStaticProjectInspector().inspect(tempDir);
    }

    private void writeChildPom(String artifactId) throws IOException {
        Path directory = tempDir.resolve(artifactId);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(artifactId));
    }
}
