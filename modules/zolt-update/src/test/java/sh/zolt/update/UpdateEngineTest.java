package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.toml.manifest.edit.ManifestSourceEditor;
import java.util.List;
import org.junit.jupiter.api.Test;

final class UpdateEngineTest {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();


    @Test
    void defaultCeilingStaysWithinCurrentMajor() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.example:lib", "central", "1.2.3", "1.2.5", "1.9.0", "2.0.0");
        UpdatePlan plan = engine(discovery).plan(
                scope("""
                        [dependencies]
                        "com.example:lib" = "1.2.3"
                        """),
                options(UpdateCeiling.DEFAULT));

        UpdateEdit edit = singleEdit(plan);
        assertEquals("1.9.0", edit.toVersion());
        assertEquals(UpdateClass.MINOR, edit.changeClass());
    }

    @Test
    void latestCeilingAllowsMajorAndPatchCeilingCaps() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.example:lib", "central", "1.2.3", "1.2.5", "1.9.0", "2.0.0");
        String toml = """
                [dependencies]
                "com.example:lib" = "1.2.3"
                """;

        assertEquals("2.0.0", singleEdit(engine(discovery).plan(scope(toml), options(UpdateCeiling.LATEST))).toVersion());
        assertEquals("1.2.5", singleEdit(engine(discovery).plan(scope(toml), options(UpdateCeiling.PATCH))).toVersion());
    }

    @Test
    void aliasEditCarriesFanOutAndWarning() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.google.guava:guava", "central", "30.0-jre", "30.1.1-jre", "33.0-jre");
        UpdatePlan plan = engine(discovery).plan(
                scope("""
                        [versions]
                        guava = "30.0-jre"

                        [dependencies]
                        "com.google.guava:guava" = { versionRef = "guava" }
                        """),
                options(UpdateCeiling.LATEST));

        UpdateEdit edit = singleEdit(plan);
        assertEquals(OutdatedSurface.VERSION_ALIAS, edit.surface());
        assertEquals(List.of("[dependencies].com.google.guava:guava"), edit.fanOut());
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().getFirst().contains("Alias `guava`"));
        assertTrue(plan.warnings().getFirst().contains("[dependencies].com.google.guava:guava"));
    }

    @Test
    void selectorsScopeThePlan() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.example:lib", "central", "1.0.0", "1.1.0")
                .listing("com.example:other", "central", "2.0.0", "2.1.0");
        UpdatePlan plan = engine(discovery).plan(
                scope("""
                        [dependencies]
                        "com.example:lib" = "1.0.0"
                        "com.example:other" = "2.0.0"
                        """),
                new UpdateOptions(UpdateCeiling.DEFAULT, false, false, List.of("com.example:other")));

        assertEquals("com.example:other", singleEdit(plan).identifier());
    }

    @Test
    void applyPreservesMetadataAndUpdatesAliasNotLiteral() {
        String body = """
                [versions]
                guava = "30.0-jre"

                [dependencies]
                "com.google.guava:guava" = { versionRef = "guava" }
                "org.apache.commons:commons-lang3" = { version = "3.10", exclude = ["org.foo:bar"] }
                """;
        AuthoredManifest config = manifest(body);
        UpdatePlan plan = new UpdatePlan(
                List.of(
                        new UpdateEdit(
                                OutdatedSurface.VERSION_ALIAS,
                                "guava",
                                "[versions]",
                                "30.0-jre",
                                "33.0-jre",
                                UpdateClass.MAJOR,
                                List.of("[dependencies].com.google.guava:guava")),
                        new UpdateEdit(
                                OutdatedSurface.DEPENDENCY,
                                "org.apache.commons:commons-lang3",
                                "[dependencies]",
                                "3.10",
                                "3.20",
                                UpdateClass.MINOR,
                                List.of())),
                List.of(),
                List.of());

        AuthoredManifest applied = engine(new FakeVersionDiscovery()).apply(config, plan);
        String edited = new ManifestSourceEditor()
                .edit(LOADER.document(source(body)), applied)
                .source();

        // The alias moves, the versionRef declaration that reads it is untouched, and the literal
        // dependency keeps its exclusions on the same physical line.
        assertTrue(edited.contains("guava = \"33.0-jre\""), edited);
        assertTrue(edited.contains("\"com.google.guava:guava\" = { versionRef = \"guava\" }"), edited);
        assertTrue(
                edited.contains(
                        "\"org.apache.commons:commons-lang3\" = { version = \"3.20\", exclude = [\"org.foo:bar\"] }"),
                edited);
    }

    private UpdateEngine engine(FakeVersionDiscovery discovery) {
        return new UpdateEngine(discovery);
    }

    private UpdateOptions options(UpdateCeiling ceiling) {
        return new UpdateOptions(ceiling, false, false, List.of());
    }

    private static UpdatePlanningScope scope(String body) {
        return UpdatePlanningScope.standalone(manifest(body), discovery(body));
    }

    private static AuthoredManifest manifest(String body) {
        return LOADER.document(source(body)).authored();
    }

    private static ProjectConfig discovery(String body) {
        return LOADER.load(source(body));
    }

    private static String source(String body) {
        return """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s
                """.formatted(body);
    }

    private static UpdateEdit singleEdit(UpdatePlan plan) {
        assertEquals(1, plan.edits().size(), "expected exactly one edit");
        return plan.edits().getFirst();
    }
}
