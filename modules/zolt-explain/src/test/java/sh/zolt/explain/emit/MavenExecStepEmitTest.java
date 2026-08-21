package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.maven.MavenStaticProjectInspector;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedOutputKind;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredExecStep;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies {@code --emit-toml} drafts {@code kind = "exec"} steps from exec-shaped Maven plugins. */
final class MavenExecStepEmitTest {
    @TempDir
    private Path tempDir;

    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void draftsJvmExecToolStepForExecJavaWithExecutableDependencies() throws IOException {
        DraftZoltToml draft = draft("""
                <plugin>
                  <groupId>org.codehaus.mojo</groupId>
                  <artifactId>exec-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <id>generate-api</id>
                      <phase>generate-sources</phase>
                      <goals><goal>java</goal></goals>
                      <configuration>
                        <mainClass>com.example.tool.Main</mainClass>
                        <arguments><argument>--out</argument><argument>target/generated</argument></arguments>
                        <executableDependencies>
                          <dependency><groupId>com.example</groupId><artifactId>codegen-tool</artifactId></dependency>
                        </executableDependencies>
                      </configuration>
                    </execution>
                  </executions>
                </plugin>
                """);
        AuthoredExecStep step = execStep(draft);
        AuthoredGeneratedTool declaration = tools(draft).get(step.tool());

        assertTrue(declaration instanceof AuthoredGeneratedTool.Jvm, () -> tools(draft).toString());
        AuthoredGeneratedTool.Jvm jvm = (AuthoredGeneratedTool.Jvm) declaration;
        // The main class now belongs to the [generated.tools.<id>] declaration, not the step: only the
        // `project` pseudo-tool carries mainClass on the step itself.
        assertEquals("com.example.tool.Main", jvm.mainClass().value());
        assertTrue(step.mainClass().isEmpty(), () -> step.mainClass().toString());
        assertEquals("com.example:codegen-tool", jvm.coordinates().getFirst().coordinate().value());
        // A jvm exec tool whose version the audit cannot read is pinned to the legal placeholder 0.0.0.
        assertEquals(
                new DependencySelector.FixedVersion("0.0.0"),
                jvm.coordinates().getFirst().selector());
        assertEquals(GeneratedOutputKind.JAVA_SOURCES, step.produces());
        assertEquals(List.of("--out", "target/generated"), step.args());
        assertEquals(List.of(MavenExecStepDrafter.INPUT_PLACEHOLDER), globs(step.inputs()));
        // Exec outputs are relative to [build.output].root, so the drafted path lost its `target/` prefix.
        assertEquals("generated/sources/generate-api", step.output().value());
        assertTrue(draft.notes().stream().anyMatch(note -> note.contains("executableDependencies")),
                draft.notes()::toString);
    }

    @Test
    void draftsProcessToolStepForExecExecGoal() throws IOException {
        DraftZoltToml draft = draft("""
                <plugin>
                  <groupId>org.codehaus.mojo</groupId>
                  <artifactId>exec-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <phase>generate-resources</phase>
                      <goals><goal>exec</goal></goals>
                      <configuration>
                        <executable>protoc</executable>
                        <arguments><argument>--java_out=target/gen</argument></arguments>
                      </configuration>
                    </execution>
                  </executions>
                </plugin>
                """);
        AuthoredExecStep step = execStep(draft);
        AuthoredGeneratedTool declaration = tools(draft).get(step.tool());

        assertTrue(declaration instanceof AuthoredGeneratedTool.Process, () -> tools(draft).toString());
        AuthoredGeneratedTool.Process process = (AuthoredGeneratedTool.Process) declaration;
        assertEquals("protoc", process.binary().value());
        assertTrue(process.allowUnpinnedTool());
        assertEquals(GeneratedOutputKind.RESOURCES, step.produces());
    }

    @Test
    void clampsProjectToolSourceLaneToResourcesWithReviewNote() throws IOException {
        DraftZoltToml draft = draft("""
                <plugin>
                  <groupId>org.codehaus.mojo</groupId>
                  <artifactId>exec-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <phase>generate-sources</phase>
                      <goals><goal>java</goal></goals>
                      <configuration><mainClass>com.example.Generator</mainClass></configuration>
                    </execution>
                  </executions>
                </plugin>
                """);
        AuthoredExecStep step = execStep(draft);

        assertEquals(new LocalId("project"), step.tool());
        assertEquals("com.example.Generator", step.mainClass().orElseThrow().value());
        // `project` is a pseudo-tool: it is referenced by the step and never declared under
        // [generated.tools].
        assertTrue(tools(draft).isEmpty(), () -> tools(draft).toString());
        assertEquals(GeneratedOutputKind.RESOURCES, step.produces());
        assertTrue(draft.notes().stream().anyMatch(note -> note.contains("tool = \"project\"")
                && note.contains("after compile")), draft.notes()::toString);
        assertTrue(draft.notes().stream().anyMatch(note -> note.contains(MavenExecStepDrafter.INPUT_PLACEHOLDER)),
                draft.notes()::toString);
    }

    @Test
    void rendererInjectsInputOutputTodoUnderExecSections() throws IOException {
        DraftZoltToml draft = draft("""
                <plugin>
                  <groupId>org.codehaus.mojo</groupId>
                  <artifactId>exec-maven-plugin</artifactId>
                  <executions>
                    <execution>
                      <phase>generate-resources</phase>
                      <goals><goal>exec</goal></goals>
                      <configuration><executable>protoc</executable></configuration>
                    </execution>
                  </executions>
                </plugin>
                """);
        String rendered = new DraftZoltTomlRenderer().render(draft, manifest -> """
                [generated.tools.protoc]
                kind = "process"
                binary = "protoc"

                [generated.main.gen]
                kind = "exec"
                tool = "protoc"
                inputs = ["REPLACE_ME"]
                output = "generated/resources/gen"
                produces = "resources"
                """);
        assertTrue(rendered.contains("[generated.main.gen]\n# TODO declare inputs/outputs"), rendered);
        // Tool declarations are not exec sections; only the step headers get the TODO.
        assertFalse(rendered.contains("[generated.tools.protoc]\n# TODO"), rendered);
    }

    private DraftZoltToml draft(String pluginXml) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <build><plugins>%s</plugins></build>
                </project>
                """.formatted(pluginXml));
        return mapper.fromMaven(new MavenStaticProjectInspector().inspect(tempDir));
    }

    private static AuthoredExecStep execStep(DraftZoltToml draft) {
        AuthoredGeneratedSources generated = generated(draft);
        return Stream.concat(generated.main().values().stream(), generated.test().values().stream())
                .filter(AuthoredExecStep.class::isInstance)
                .map(AuthoredExecStep.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no exec step drafted: " + generated));
    }

    private static Map<LocalId, AuthoredGeneratedTool> tools(DraftZoltToml draft) {
        return generated(draft).tools().declarations();
    }

    private static AuthoredGeneratedSources generated(DraftZoltToml draft) {
        return draft.manifest().generated()
                .orElseThrow(() -> new AssertionError("no generated sources drafted: " + draft.manifest()));
    }

    private static List<String> globs(List<ResourceGlob> inputs) {
        return inputs.stream().map(ResourceGlob::value).toList();
    }
}
