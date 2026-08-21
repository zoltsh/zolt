package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestResourceFields;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestSharedFields;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaMatch;
import sh.zolt.toml.schema.ManifestSection;

final class ManifestDecodeIndexTest {
    @Test
    void readsStaticFieldsOnlyThroughExactRegisteredHandles() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                """);

        ValidatedManifestField field = index.field(FinalManifestIdentityFields.PROJECT_NAME)
                .orElseThrow();
        assertSame(FinalManifestIdentityFields.PROJECT_NAME, field.schema().descriptor());
        assertEquals("project.name", field.path().toString());
        assertFalse(index.field(FinalManifestIdentityFields.PROJECT_VERSION).isPresent());

        assertThrows(
                IllegalArgumentException.class,
                () -> index.field(cloneField(FinalManifestIdentityFields.PROJECT_NAME)));
    }

    @Test
    void keepsDynamicEntriesInSourceOrderAndDerivesTheirKeysFromBindings() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [versions]
                zeta = "2.0"
                alpha = "1.0"
                """);

        List<ManifestDecodeIndex.Entry> entries =
                index.entries(FinalManifestSharedFields.VERSIONS_ENTRY);

        assertEquals(List.of("zeta", "alpha"), entries.stream()
                .map(ManifestDecodeIndex.Entry::key)
                .toList());
        assertEquals(List.of("versions.zeta", "versions.alpha"), entries.stream()
                .map(entry -> entry.field().path().toString())
                .toList());
        assertThrows(UnsupportedOperationException.class, entries::clear);
    }

    @Test
    void keepsNamedSectionsInSourceOrderAndDerivesTheirKeysFromBindings() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [repositories.zeta]
                url = "https://zeta.example"

                [repositories.alpha]
                url = "https://alpha.example"
                """);

        List<ManifestDecodeIndex.SectionEntry> entries =
                index.sectionEntries(FinalManifestPaths.REPOSITORY);

        assertEquals(List.of("zeta", "alpha"), entries.stream()
                .map(ManifestDecodeIndex.SectionEntry::key)
                .toList());
        assertEquals(List.of("repositories.zeta", "repositories.alpha"), entries.stream()
                .map(entry -> entry.section().path().toString())
                .toList());
        assertTrue(entries.stream().allMatch(entry ->
                entry.section().source().origin() == ManifestShapeOrigin.EXPLICIT_TABLE));
        assertThrows(UnsupportedOperationException.class, entries::clear);

        ManifestPath clone = new ManifestPath(FinalManifestPaths.REPOSITORY.segments());
        assertThrows(IllegalArgumentException.class, () -> index.sectionEntries(clone));
    }

    @Test
    void keepsInlineEntriesAndNamedSectionsInAuthoredOrderAcrossSharedSourceSpans() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                resources = { tokens = { zeta = { value = "z" }, alpha = { value = "a" } } }
                test = { suites = { zeta = { tags = ["z"] }, alpha = { tags = ["a"] } } }
                compiler.generated.test = "generated/test"
                compiler.test.args = []
                compiler.args = []
                [versions]
                release = "1.0"
                """);

        List<ManifestDecodeIndex.Entry> tokens =
                index.entries(FinalManifestResourceFields.RESOURCES_TOKENS_ENTRY);
        assertEquals(
                List.of("zeta", "alpha"),
                tokens.stream().map(ManifestDecodeIndex.Entry::key).toList());
        assertTrue(tokens.stream().allMatch(entry ->
                entry.field().source().origin() == ManifestShapeOrigin.INLINE_PARENT));

        List<ManifestDecodeIndex.SectionEntry> suites =
                index.sectionEntries(FinalManifestPaths.TEST_SUITE);
        assertEquals(
                List.of("zeta", "alpha"),
                suites.stream().map(ManifestDecodeIndex.SectionEntry::key).toList());
        assertTrue(suites.stream().allMatch(entry ->
                entry.section().source().origin() == ManifestShapeOrigin.INLINE_PARENT));
        assertEquals(
                "compiler.args",
                index.firstDirectField(
                                FinalManifestPaths.COMPILER_GENERATED,
                                FinalManifestPaths.COMPILER_TEST,
                                FinalManifestPaths.COMPILER)
                        .orElseThrow().path().toString());
        assertTrue(index.firstDirectField(FinalManifestPaths.TEST_SUITES).isEmpty());
        assertTrue(index.firstDirectField(FinalManifestPaths.VERSIONS).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> index.firstDirectField(FinalManifestPaths.TEST_SUITE));
        ManifestPath clone = new ManifestPath(FinalManifestPaths.COMPILER.segments());
        assertThrows(IllegalArgumentException.class, () -> index.firstDirectField(
                FinalManifestPaths.COMPILER, clone));
    }

    @Test
    void locatesNamedSectionChildrenWithoutLiteralPathsAndRejectsMismatchedRows() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [repositories.primary]
                credentials = "company"
                url = "https://repo.example"

                [repositories.backup]
                credentials = "company"
                """);
        List<ManifestDecodeIndex.SectionEntry> repositories =
                index.sectionEntries(FinalManifestPaths.REPOSITORY);
        ManifestDecodeIndex.SectionEntry primary = repositories.get(0);
        ManifestDecodeIndex.SectionEntry backup = repositories.get(1);

        assertEquals(
                "repositories.primary.url",
                index.field(primary, FinalManifestSharedFields.REPOSITORY_URL)
                        .orElseThrow()
                        .path()
                        .toString());
        assertEquals(
                "repositories.primary.url",
                index.firstField(primary).orElseThrow().path().toString());
        assertTrue(index.field(backup, FinalManifestSharedFields.REPOSITORY_URL).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> index.field(primary, FinalManifestSharedFields.CREDENTIAL_TOKEN_ENV));
        ManifestDecodeIndex.SectionEntry forged =
                new ManifestDecodeIndex.SectionEntry("forged", primary.section());
        assertThrows(IllegalArgumentException.class, () -> index.field(
                forged, FinalManifestSharedFields.REPOSITORY_URL));
        assertThrows(IllegalArgumentException.class, () -> index.firstField(forged));

        ManifestDecodeIndex other = ManifestSemanticTestSupport.index("""
                [repositories.primary]
                url = "https://other.example"
                """);
        ManifestDecodeIndex.SectionEntry foreign =
                other.sectionEntries(FinalManifestPaths.REPOSITORY).getFirst();
        assertThrows(IllegalArgumentException.class, () -> index.field(
                foreign, FinalManifestSharedFields.REPOSITORY_URL));
        assertThrows(IllegalArgumentException.class, () -> index.firstField(foreign));
    }

    @Test
    void rejectsStaticAndDynamicAccessorMixups() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                [versions]
                release = "1.0"
                """);

        assertThrows(
                IllegalArgumentException.class,
                () -> index.field(FinalManifestSharedFields.VERSIONS_ENTRY));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.entries(FinalManifestIdentityFields.PROJECT_NAME));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.sectionEntries(FinalManifestPaths.VERSIONS));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.section(FinalManifestPaths.REPOSITORY));
    }

    @Test
    void preservesExplicitEmptySectionEvidenceAndRequiresExactPathHandles() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("[versions]\n");

        ValidatedManifestSection section = index.section(FinalManifestPaths.VERSIONS)
                .orElseThrow();
        assertEquals(ManifestShapeOrigin.EXPLICIT_TABLE, section.source().origin());
        assertTrue(ManifestSemanticTestSupport.index("")
                .section(FinalManifestPaths.VERSIONS)
                .isEmpty());

        ManifestPath clone = new ManifestPath(FinalManifestPaths.VERSIONS.segments());
        assertThrows(IllegalArgumentException.class, () -> index.section(clone));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.section(FinalManifestPaths.REPOSITORY));
    }

    @Test
    void rejectsForgedValidatedMatchesAndDuplicateStaticCardinality() {
        ManifestDecodeIndex valid = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                """);
        ValidatedManifestField field = valid.field(FinalManifestIdentityFields.PROJECT_NAME)
                .orElseThrow();
        ManifestField clone = cloneField(FinalManifestIdentityFields.PROJECT_NAME);
        ValidatedManifestField forged = new ValidatedManifestField(
                field.path(),
                new ManifestSchemaMatch<>(clone, field.schema().bindings()),
                field.rawValue(),
                field.source());

        assertThrows(
                IllegalStateException.class,
                () -> new ManifestDecodeIndex(new ValidatedManifestShape(List.of(), List.of(forged))));
        assertThrows(
                IllegalStateException.class,
                () -> new ManifestDecodeIndex(
                        new ValidatedManifestShape(List.of(), List.of(field, field))));

        ManifestDecodeIndex sectionIndex = ManifestSemanticTestSupport.index("[versions]\n");
        ValidatedManifestSection section =
                sectionIndex.section(FinalManifestPaths.VERSIONS).orElseThrow();
        ManifestSection registered = FinalManifestSchema.registry()
                .section(FinalManifestPaths.VERSIONS)
                .orElseThrow();
        ManifestSection sectionClone = new ManifestSection(
                new ManifestPath(registered.path().segments()),
                registered.kind(),
                registered.canonicalOrder(),
                registered.reservedChildren(),
                registered.dynamicKeyGrammars());
        ValidatedManifestSection forgedSection = new ValidatedManifestSection(
                section.path(),
                Optional.of(new ManifestSchemaMatch<>(sectionClone, section.schema()
                        .orElseThrow()
                        .bindings())),
                section.source());

        assertThrows(
                IllegalStateException.class,
                () -> new ManifestDecodeIndex(
                        new ValidatedManifestShape(List.of(forgedSection), List.of())));
        assertThrows(
                IllegalStateException.class,
                () -> new ManifestDecodeIndex(
                        new ValidatedManifestShape(List.of(section, section), List.of())));

        ManifestDecodeIndex namedIndex = ManifestSemanticTestSupport.index("""
                [repositories.company]
                url = "https://repo.example"
                """);
        ValidatedManifestSection named = namedIndex
                .sectionEntries(FinalManifestPaths.REPOSITORY)
                .getFirst()
                .section();
        ValidatedManifestSection forgedNamedBinding = new ValidatedManifestSection(
                named.path(),
                Optional.of(new ManifestSchemaMatch<>(
                        named.schema().orElseThrow().descriptor(),
                        Map.of("id", "forged"))),
                named.source());
        assertThrows(
                IllegalStateException.class,
                () -> new ManifestDecodeIndex(
                        new ValidatedManifestShape(List.of(named, named), List.of())));
        assertThrows(
                IllegalStateException.class,
                () -> new ManifestDecodeIndex(
                        new ValidatedManifestShape(List.of(forgedNamedBinding), List.of())));
    }

    private static ManifestField cloneField(ManifestField field) {
        return new ManifestField(
                new ManifestPath(field.path().segments()),
                field.valueKind(),
                field.formatting(),
                field.mutation(),
                field.canonicalOrder(),
                field.symbolFamily(),
                field.validation(),
                field.dynamicKeyGrammars(),
                field.objectShape());
    }
}
