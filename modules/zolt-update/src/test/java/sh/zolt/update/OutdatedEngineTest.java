package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.WorkspaceConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class OutdatedEngineTest {

    @Test
    void reportsLiteralDependencyUpdate() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.example:lib", "central", "1.2.3", "1.2.5", "1.5.0");
        OutdatedReport report = engine(discovery).report(
                scopes("""
                        [dependencies]
                        "com.example:lib" = "1.2.3"
                        """),
                OutdatedOptions.defaults());

        OutdatedEntry entry = single(report);
        assertEquals(OutdatedSurface.DEPENDENCY, entry.surface());
        assertEquals("com.example:lib", entry.identifier());
        assertEquals("[dependencies]", entry.section());
        assertEquals(OutdatedStatus.UPDATE_AVAILABLE, entry.status());
        assertEquals("1.2.5", entry.candidates().patch().orElseThrow());
        assertEquals("1.5.0", entry.candidates().minor().orElseThrow());
        assertEquals("1.5.0", entry.candidates().selectedInMajor().orElseThrow());
        assertEquals(UpdateClass.MINOR, entry.candidates().selectedInMajorClass().orElseThrow());
        assertEquals(Optional.of("central"), entry.sourceRepository());
    }

    @Test
    void versionRefDependencyReportsUnderAliasNotAsLiteral() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.google.guava:guava", "central", "33.4.0-jre", "33.4.8-jre", "34.0.0-jre");
        OutdatedReport report = engine(discovery).report(
                scopes("""
                        [versions]
                        guava = "33.4.0-jre"

                        [dependencies]
                        "com.google.guava:guava" = { versionRef = "guava" }
                        """),
                OutdatedOptions.defaults());

        OutdatedEntry entry = single(report);
        assertEquals(OutdatedSurface.VERSION_ALIAS, entry.surface());
        assertEquals("guava", entry.identifier());
        assertEquals(List.of("[dependencies].com.google.guava:guava"), entry.governs());
        assertEquals("33.4.8-jre", entry.candidates().selectedInMajor().orElseThrow());
        assertEquals(UpdateClass.PATCH, entry.candidates().selectedInMajorClass().orElseThrow());
        assertEquals("34.0.0-jre", entry.candidates().selectedLatest().orElseThrow());
        assertEquals(UpdateClass.MAJOR, entry.candidates().selectedLatestClass().orElseThrow());
    }

    @Test
    void snapshotLiteralsAreIgnored() {
        OutdatedReport report = engine(new FakeVersionDiscovery()).report(
                scopes("""
                        [dependencies]
                        "com.example:snap" = "1.0.0-SNAPSHOT"
                        """),
                new OutdatedOptions(false, true, false, List.of()));

        assertFalse(report.hasEntries());
    }

    @Test
    void unresolvedDiscoveryIsUnknown() {
        OutdatedReport report = engine(new FakeVersionDiscovery()).report(
                scopes("""
                        [dependencies]
                        "com.example:lib" = "1.2.3"
                        """),
                OutdatedOptions.defaults());

        assertEquals(OutdatedStatus.UNKNOWN, single(report).status());
    }

    @Test
    void upToDateHiddenByDefaultShownWithIncludeUpToDate() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery().listing("com.example:lib", "central", "1.2.3");
        String toml = """
                [dependencies]
                "com.example:lib" = "1.2.3"
                """;

        assertFalse(engine(discovery).report(scopes(toml), OutdatedOptions.defaults()).hasEntries());

        OutdatedReport shown = engine(discovery)
                .report(scopes(toml), new OutdatedOptions(false, true, false, List.of()));
        assertEquals(OutdatedStatus.CURRENT, single(shown).status());
    }

    @Test
    void prereleasesWidenOnlyWithFlag() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.example:lib", "central", "1.2.3", "1.3.0-rc1");
        String toml = """
                [dependencies]
                "com.example:lib" = "1.2.3"
                """;

        assertFalse(engine(discovery).report(scopes(toml), OutdatedOptions.defaults()).hasEntries());

        OutdatedReport widened = engine(discovery)
                .report(scopes(toml), new OutdatedOptions(true, false, false, List.of()));
        assertEquals("1.3.0-rc1", single(widened).candidates().selectedLatest().orElseThrow());
    }

    @Test
    void aliasCandidatesIntersectGovernedCoordinates() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.a:one", "central", "1.0.0", "1.1.0", "2.0.0")
                .listing("com.b:two", "central", "1.0.0", "1.1.0");
        OutdatedReport report = engine(discovery).report(
                scopes("""
                        [versions]
                        shared = "1.0.0"

                        [dependencies]
                        "com.a:one" = { versionRef = "shared" }
                        "com.b:two" = { versionRef = "shared" }
                        """),
                OutdatedOptions.defaults());

        OutdatedEntry entry = single(report);
        assertEquals("1.1.0", entry.candidates().selectedLatest().orElseThrow());
        assertTrue(entry.candidates().major().filter("1.1.0"::equals).isPresent());
    }

    @Test
    void selectorsScopeTheReport() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.example:lib", "central", "1.0.0", "1.1.0")
                .listing("com.example:other", "central", "2.0.0", "2.1.0");
        OutdatedReport report = engine(discovery).report(
                scopes("""
                        [dependencies]
                        "com.example:lib" = "1.0.0"
                        "com.example:other" = "2.0.0"
                        """),
                new OutdatedOptions(false, false, false, List.of("com.example:other")));

        assertEquals("com.example:other", single(report).identifier());
    }

    @Test
    void platformsAreReportedAsTheirOwnSurface() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("io.example:bom", "central", "1.0.0", "1.2.0");
        OutdatedReport report = engine(discovery).report(
                scopes("""
                        [platforms]
                        "io.example:bom" = "1.0.0"
                        """),
                OutdatedOptions.defaults());

        OutdatedEntry entry = single(report);
        assertEquals(OutdatedSurface.PLATFORM, entry.surface());
        assertEquals("[platforms]", entry.section());
        assertEquals("1.2.0", entry.candidates().selectedLatest().orElseThrow());
    }

    @Test
    void workspaceRootPlatformUsesRootManifestIdentityAndDefaultCentralRepository() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("org.junit:junit-bom", "central", "5.10.2", "5.11.4");
        WorkspaceConfig workspace = new WorkspaceConfig(
                "demo",
                List.of("apps/api"),
                List.of(),
                Map.of(),
                Map.of("org.junit:junit-bom", "5.10.2"));
        OutdatedReport report = engine(discovery).report(
                List.of(new WorkspaceOutdatedScope(
                        "workspace-root",
                        "zolt-workspace.toml",
                        "zolt.lock",
                        workspace,
                        Optional.empty())),
                OutdatedOptions.defaults());

        OutdatedEntry entry = single(report);
        assertEquals(OutdatedSurface.PLATFORM, entry.surface());
        assertEquals("5.11.4", entry.candidates().selectedLatest().orElseThrow());
        assertEquals("zolt-workspace.toml", entry.target().manifestPath());
        assertEquals(
                UpdateTargetId.create(
                        "zolt-workspace.toml",
                        OutdatedSurface.PLATFORM,
                        "[platforms]",
                        "org.junit:junit-bom"),
                entry.target().targetId());
    }

    @Test
    void workspaceSharedCoordinatesAreAnnotated() {
        FakeVersionDiscovery discovery = new FakeVersionDiscovery()
                .listing("com.example:lib", "central", "1.0.0", "1.1.0");
        ProjectConfig config = config("""
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        OutdatedReport report = engine(discovery).report(
                List.of(OutdatedScope.of("alpha", config), OutdatedScope.of("zeta", config)),
                OutdatedOptions.defaults());

        assertEquals(List.of("alpha", "zeta"), report.scopes().get(0).entries().get(0).members());
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("com.example:lib is shared by members alpha, zeta")));
    }

    @Test
    void schemaV1PreservesDisplayLabelsAndDescriptiveVersionText() {
        String decomposed = "cafe\u0301";
        OutdatedReport report = engine(new FakeVersionDiscovery()).report(
                List.of(OutdatedScope.of(decomposed, config("""
                        [dependencies]
                        "com.example:lib" = "1.0.0-%s"
                        """.formatted(decomposed)))),
                OutdatedOptions.defaults());

        assertEquals(decomposed, report.scopes().getFirst().label());
        assertEquals("1.0.0-" + decomposed, single(report).currentVersion());
        String json = new OutdatedJsonRenderer().render(report);
        assertTrue(json.contains("\"label\": \"" + decomposed + "\""));
        assertTrue(json.contains("\"current\": \"1.0.0-" + decomposed + "\""));
    }

    private OutdatedEngine engine(FakeVersionDiscovery discovery) {
        return new OutdatedEngine(discovery);
    }

    private List<OutdatedScope> scopes(String body) {
        return List.of(OutdatedScope.of("demo", config(body)));
    }

    private ProjectConfig config(String body) {
        return new ZoltTomlParser().parse(
                """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                %s
                """.formatted(body));
    }

    private static OutdatedEntry single(OutdatedReport report) {
        List<OutdatedEntry> entries = report.scopes().get(0).entries();
        assertEquals(1, entries.size(), "expected exactly one entry");
        return entries.get(0);
    }
}
