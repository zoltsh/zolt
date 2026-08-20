package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FinalManifestFieldHandlesTest {
    private final ManifestSchemaRegistry registry = FinalManifestSchema.registry();

    @Test
    void identityHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestIdentityFields.fields(),
                List.of(
                        field("workspace.name", 1_010),
                        field("workspace.members.default", 1_110),
                        field("workspace.members.include", 1_120),
                        field("workspace.members.exclude", 1_130),
                        field("workspace.project.group", 1_210),
                        field("workspace.project.version", 1_220),
                        field("workspace.project.java", 1_230),
                        field("workspace.project.license", 1_240),
                        field("project.name", 2_010),
                        field("project.version", 2_020),
                        field("project.group", 2_030),
                        field("project.java", 2_040),
                        field("project.main", 2_050),
                        field("project.description", 2_060),
                        field("project.url", 2_070),
                        field("project.issues", 2_080),
                        field("project.license", 2_090),
                        field("project.scm.url", 2_110),
                        field("project.scm.connection", 2_120),
                        field("project.scm.developerConnection", 2_130),
                        field("project.scm.tag", 2_140),
                        field("project.developers.<id>.name", 2_210),
                        field("project.developers.<id>.email", 2_220),
                        field("project.developers.<id>.organization", 2_230),
                        field("project.developers.<id>.url", 2_240)));
    }

    @Test
    void toolchainHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestToolchainFields.fields(),
                List.of(
                        field("toolchain.zolt.version", 3_010),
                        field("toolchain.java.version", 3_110),
                        field("toolchain.java.distribution", 3_120),
                        field("toolchain.java.features", 3_130),
                        field("toolchain.java.policy", 3_140),
                        field("toolchain.java.test.version", 3_210),
                        field("toolchain.java.test.distribution", 3_220),
                        field("toolchain.java.test.policy", 3_230)));
    }

    @Test
    void sharedHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestSharedFields.fields(),
                List.of(
                        field("versions.<id>", 4_010),
                        field("repositories.central", 4_110),
                        field("repositories.order", 4_120),
                        field("repositories.<id>.url", 4_210),
                        field("repositories.<id>.credentials", 4_220),
                        field("credentials.<id>.tokenEnv", 4_310),
                        field("credentials.<id>.usernameEnv", 4_320),
                        field("credentials.<id>.passwordEnv", 4_330),
                        field("platforms.<coordinate>", 4_410)));
    }

    @Test
    void handleCatalogsCoverTheCompleteRegisteredPrefixWithoutDuplicates() {
        List<ManifestField> handles = handles();
        List<ManifestField> registered = registry.fields().stream()
                .filter(field -> field.canonicalOrder() < 5_000)
                .toList();

        assertEquals(42, handles.size());
        assertEquals(42, handles.stream().map(ManifestField::path).distinct().count());
        assertEquals(42, handles.stream().map(ManifestField::canonicalOrder).distinct().count());
        assertEquals(handles, registered);
        for (int index = 0; index < handles.size(); index++) {
            assertSame(handles.get(index), registered.get(index));
        }
    }

    private void assertCatalog(
            List<ManifestField> handles,
            List<Map.Entry<String, Integer>> expected) {
        assertEquals(
                expected,
                handles.stream()
                        .map(field -> field(field.path().toString(), field.canonicalOrder()))
                        .toList());
        handles.forEach(field -> assertSame(field, registry.field(field.path()).orElseThrow()));
    }

    private static List<ManifestField> handles() {
        ArrayList<ManifestField> handles = new ArrayList<>();
        handles.addAll(FinalManifestIdentityFields.fields());
        handles.addAll(FinalManifestToolchainFields.fields());
        handles.addAll(FinalManifestSharedFields.fields());
        return List.copyOf(handles);
    }

    private static Map.Entry<String, Integer> field(String path, int canonicalOrder) {
        return Map.entry(path, canonicalOrder);
    }
}
