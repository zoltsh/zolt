package sh.zolt.explain.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MavenKotlinPluginEvidenceTest {
    @TempDir
    private Path tempDir;

    private final MavenStaticProjectInspector inspector = new MavenStaticProjectInspector();

    @Test
    void provesConventionalGoalPhasePairsWithoutConfiguration() throws IOException {
        MavenInspectionResult result = inspect("""
                <execution><goals><goal>compile</goal></goals></execution>
                <execution><goals><goal>test-compile</goal></goals></execution>
                """, "", "");

        MavenPluginInspection plugin = kotlinPlugin(result);
        assertTrue(plugin.conventionalKotlinJvmExecutions());
        assertEquals(java.util.List.of("compile", "test-compile"), plugin.phases());
        assertFalse(plugin.configurationPresent());
        assertEquals("", plugin.extensions());
        assertFalse(plugin.pluginDependenciesPresent());
        assertTrue(plugin.kotlinPluginProperties().isEmpty());
        assertEquals("", plugin.kaptIncludeCompileClasspath());
        assertTrue(MavenSignalRules.draftableKotlinJvmPluginShape(plugin));
        assertSignal(result, "maven.kotlin.manual-migration");

        String json = new MavenExplainFormatter().json(result);
        assertFalse(json.contains("conventionalKotlinJvmExecutions"));
        assertFalse(json.contains("configurationPresent"));
        assertFalse(json.contains("kotlinPluginProperties"));
        assertFalse(json.contains("kaptIncludeCompileClasspath"));
    }

    @Test
    void rejectsMissingDuplicateAndSwappedExecutionBindings() throws IOException {
        MavenInspectionResult missing = inspect("", "", "");
        assertFalse(kotlinPlugin(missing).conventionalKotlinJvmExecutions());
        assertSignal(missing, "maven.kotlin.manual-migration");

        MavenInspectionResult duplicate = inspect("""
                <execution><goals><goal>compile</goal></goals></execution>
                <execution><goals><goal>compile</goal></goals></execution>
                """, "", "");
        assertFalse(kotlinPlugin(duplicate).conventionalKotlinJvmExecutions());
        assertSignal(duplicate, "maven.language.unsupported");

        MavenInspectionResult swapped = inspect("""
                <execution>
                  <phase>test-compile</phase>
                  <goals><goal>compile</goal></goals>
                </execution>
                <execution>
                  <phase>compile</phase>
                  <goals><goal>test-compile</goal></goals>
                </execution>
                """, "", "");
        MavenPluginInspection swappedPlugin = kotlinPlugin(swapped);
        assertEquals(java.util.List.of("compile", "test-compile"), swappedPlugin.goals());
        assertEquals(java.util.List.of("compile", "test-compile"), swappedPlugin.phases());
        assertFalse(swappedPlugin.conventionalKotlinJvmExecutions());
        assertSignal(swapped, "maven.language.unsupported");
    }

    @Test
    void rejectsPluginAndExecutionConfiguration() throws IOException {
        MavenInspectionResult pluginConfigured = inspect(
                "<execution><goals><goal>compile</goal></goals></execution>",
                "<configuration><jvmTarget>21</jvmTarget></configuration>",
                "");
        MavenPluginInspection pluginConfiguredEvidence = kotlinPlugin(pluginConfigured);
        assertTrue(pluginConfiguredEvidence.configurationPresent());
        assertTrue(MavenSignalRules.boundedKotlinJvmCompilation(pluginConfiguredEvidence));
        assertFalse(MavenSignalRules.draftableKotlinJvmPluginShape(pluginConfiguredEvidence));
        assertSignal(pluginConfigured, "maven.kotlin.manual-migration");

        MavenInspectionResult executionConfigured = inspect("""
                <execution>
                  <goals><goal>compile</goal></goals>
                  <configuration><sourceDirs><sourceDir>src/main/kotlin</sourceDir></sourceDirs></configuration>
                </execution>
                """, "", "");
        MavenPluginInspection executionConfiguredEvidence = kotlinPlugin(executionConfigured);
        assertTrue(executionConfiguredEvidence.configurationPresent());
        assertFalse(MavenSignalRules.draftableKotlinJvmPluginShape(executionConfiguredEvidence));
        assertSignal(executionConfigured, "maven.kotlin.manual-migration");
    }

    @Test
    void recordsProjectLevelKotlinPluginControlsAsNonDraftable() throws IOException {
        MavenInspectionResult result = inspect(
                "<execution><goals><goal>compile</goal></goals></execution>",
                "",
                "",
                """
                <properties>
                  <kotlin.version>2.2.20</kotlin.version>
                  <kotlin.compiler.jvmTarget>17</kotlin.compiler.jvmTarget>
                  <kotlin.compiler.languageVersion>2.1</kotlin.compiler.languageVersion>
                </properties>
                """);

        MavenPluginInspection plugin = kotlinPlugin(result);
        assertEquals(
                java.util.List.of(
                        "kotlin.compiler.jvmTarget",
                        "kotlin.compiler.languageVersion"),
                plugin.kotlinPluginProperties());
        assertTrue(MavenSignalRules.boundedKotlinJvmCompilation(plugin));
        assertFalse(MavenSignalRules.draftableKotlinJvmPluginShape(plugin));
        assertSignal(result, "maven.kotlin.manual-migration");
    }

    @Test
    void distinguishesAutomaticExtensionsFromMixedExecutionShapesAndDependencies() throws IOException {
        String compile = "<execution><goals><goal>compile</goal></goals></execution>";
        MavenInspectionResult extensions = inspect(compile, "<extensions>true</extensions>", "");
        assertEquals("true", kotlinPlugin(extensions).extensions());
        assertSignal(extensions, "maven.language.unsupported");

        MavenInspectionResult unresolvedExtensions = inspect(
                compile, "<extensions>${kotlin.extensions}</extensions>", "");
        assertEquals("${kotlin.extensions}", kotlinPlugin(unresolvedExtensions).extensions());
        assertSignal(unresolvedExtensions, "maven.language.unsupported");

        MavenInspectionResult dependencies = inspect(compile, "", """
                <dependencies>
                  <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>compiler-plugin</artifactId>
                    <version>1.0.0</version>
                  </dependency>
                </dependencies>
                """);
        assertTrue(kotlinPlugin(dependencies).pluginDependenciesPresent());
        assertSignal(dependencies, "maven.language.unsupported");

        MavenInspectionResult disabledExtensions = inspect(compile, "<extensions>false</extensions>", "");
        MavenPluginInspection disabledExtensionsEvidence = kotlinPlugin(disabledExtensions);
        assertEquals("false", disabledExtensionsEvidence.extensions());
        assertTrue(MavenSignalRules.draftableKotlinJvmPluginShape(disabledExtensionsEvidence));
        assertSignal(disabledExtensions, "maven.kotlin.manual-migration");

        MavenInspectionResult automaticExtension = inspect(
                "",
                "<extensions>true</extensions>",
                "",
                "<properties><kapt.include.compile.classpath>false</kapt.include.compile.classpath></properties>");
        MavenPluginInspection automaticEvidence = kotlinPlugin(automaticExtension);
        assertFalse(automaticEvidence.conventionalKotlinJvmExecutions());
        assertEquals("false", automaticEvidence.kaptIncludeCompileClasspath());
        assertTrue(MavenSignalRules.boundedKotlinJvmCompilation(automaticEvidence));
        assertTrue(MavenSignalRules.draftableKotlinJvmPluginShape(automaticEvidence));
        assertSignal(automaticExtension, "maven.kotlin.manual-migration");

        MavenInspectionResult automaticDiscovery = inspect("", "<extensions>true</extensions>", "");
        assertFalse(MavenSignalRules.draftableKotlinJvmPluginShape(
                kotlinPlugin(automaticDiscovery)));
    }

    private MavenInspectionResult inspect(
            String executions,
            String pluginConfiguration,
            String pluginDependencies) throws IOException {
        return inspect(executions, pluginConfiguration, pluginDependencies, "");
    }

    private MavenInspectionResult inspect(
            String executions,
            String pluginConfiguration,
            String pluginDependencies,
            String projectProperties) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>kotlin-plugin-evidence</artifactId>
                  <version>1.0.0</version>
                  %s
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                        <version>2.2.20</version>
                        %s
                        %s
                        <executions>%s</executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(
                        projectProperties,
                        pluginConfiguration,
                        pluginDependencies,
                        executions));
        return inspector.inspect(tempDir);
    }

    private static MavenPluginInspection kotlinPlugin(MavenInspectionResult result) {
        return result.projects().getFirst().plugins().stream()
                .filter(plugin -> plugin.coordinate().startsWith(
                        "org.jetbrains.kotlin:kotlin-maven-plugin:"))
                .findFirst()
                .orElseThrow();
    }

    private static void assertSignal(MavenInspectionResult result, String id) {
        assertTrue(
                result.signals().stream().anyMatch(signal -> signal.id().equals(id)),
                () -> "expected " + id + " in " + result.signals());
    }
}
