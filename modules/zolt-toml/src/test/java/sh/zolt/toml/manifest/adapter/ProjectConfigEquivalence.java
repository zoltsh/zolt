package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectMetadata;

/**
 * Field-by-field {@link ProjectConfig} comparison with per-component failure messages, so a diverging
 * adapter names the exact record component instead of dumping two 34-component records.
 */
final class ProjectConfigEquivalence {
    private ProjectConfigEquivalence() {
    }

    /** Asserts complete equivalence, including the project Java release. */
    static void assertEquivalent(ProjectConfig legacy, ProjectConfig adapted) {
        assertProject(legacy.project(), adapted.project(), true);
        assertRemainder(legacy, adapted);
    }

    /**
     * Asserts equivalence for a BOM, whose final form has no project Java release at all. Design §12.6
     * forbids {@code project.java} on a BOM and forbids inheriting {@code [workspace.project].java},
     * while the legacy dialect required the field, so the adapter reports it absent.
     */
    static void assertBomEquivalent(ProjectConfig legacy, ProjectConfig adapted) {
        assertProject(legacy.project(), adapted.project(), false);
        assertEquals("", adapted.project().java(), "project.java on a final BOM");
        assertRemainder(legacy, adapted);
    }

    private static void assertProject(
            ProjectMetadata legacy,
            ProjectMetadata adapted,
            boolean compareJava) {
        assertEquals(legacy.name(), adapted.name(), "project.name");
        assertEquals(legacy.version(), adapted.version(), "project.version");
        assertEquals(legacy.group(), adapted.group(), "project.group");
        if (compareJava) {
            assertEquals(legacy.java(), adapted.java(), "project.java");
        }
        assertEquals(legacy.main(), adapted.main(), "project.main");
    }

    private static void assertRemainder(ProjectConfig legacy, ProjectConfig adapted) {
        assertEquals(legacy.repositories(), adapted.repositories(), "repositories");
        assertEquals(legacy.repositorySettings(), adapted.repositorySettings(), "repositorySettings");
        assertEquals(
                legacy.repositoryCredentials(), adapted.repositoryCredentials(), "repositoryCredentials");
        assertEquals(legacy.versionAliases(), adapted.versionAliases(), "versionAliases");
        assertEquals(legacy.platforms(), adapted.platforms(), "platforms");
        assertEquals(legacy.apiDependencies(), adapted.apiDependencies(), "apiDependencies");
        assertEquals(
                legacy.managedApiDependencies(),
                adapted.managedApiDependencies(),
                "managedApiDependencies");
        assertEquals(
                legacy.workspaceApiDependencies(),
                adapted.workspaceApiDependencies(),
                "workspaceApiDependencies");
        assertEquals(legacy.dependencies(), adapted.dependencies(), "dependencies");
        assertEquals(legacy.managedDependencies(), adapted.managedDependencies(), "managedDependencies");
        assertEquals(
                legacy.workspaceDependencies(), adapted.workspaceDependencies(), "workspaceDependencies");
        assertEquals(legacy.runtimeDependencies(), adapted.runtimeDependencies(), "runtimeDependencies");
        assertEquals(
                legacy.managedRuntimeDependencies(),
                adapted.managedRuntimeDependencies(),
                "managedRuntimeDependencies");
        assertEquals(legacy.providedDependencies(), adapted.providedDependencies(), "providedDependencies");
        assertEquals(
                legacy.managedProvidedDependencies(),
                adapted.managedProvidedDependencies(),
                "managedProvidedDependencies");
        assertEquals(legacy.devDependencies(), adapted.devDependencies(), "devDependencies");
        assertEquals(
                legacy.managedDevDependencies(),
                adapted.managedDevDependencies(),
                "managedDevDependencies");
        assertEquals(legacy.testDependencies(), adapted.testDependencies(), "testDependencies");
        assertEquals(
                legacy.managedTestDependencies(),
                adapted.managedTestDependencies(),
                "managedTestDependencies");
        assertEquals(
                legacy.workspaceTestDependencies(),
                adapted.workspaceTestDependencies(),
                "workspaceTestDependencies");
        assertEquals(legacy.annotationProcessors(), adapted.annotationProcessors(), "annotationProcessors");
        assertEquals(
                legacy.managedAnnotationProcessors(),
                adapted.managedAnnotationProcessors(),
                "managedAnnotationProcessors");
        assertEquals(
                legacy.workspaceAnnotationProcessors(),
                adapted.workspaceAnnotationProcessors(),
                "workspaceAnnotationProcessors");
        assertEquals(
                legacy.testAnnotationProcessors(),
                adapted.testAnnotationProcessors(),
                "testAnnotationProcessors");
        assertEquals(
                legacy.managedTestAnnotationProcessors(),
                adapted.managedTestAnnotationProcessors(),
                "managedTestAnnotationProcessors");
        assertEquals(
                legacy.workspaceTestAnnotationProcessors(),
                adapted.workspaceTestAnnotationProcessors(),
                "workspaceTestAnnotationProcessors");
        assertEquals(legacy.dependencyPolicy(), adapted.dependencyPolicy(), "dependencyPolicy");
        assertEquals(legacy.build(), adapted.build(), "build");
        assertEquals(legacy.nativeSettings(), adapted.nativeSettings(), "nativeSettings");
        assertEquals(legacy.compilerSettings(), adapted.compilerSettings(), "compilerSettings");
        assertEquals(legacy.packageSettings(), adapted.packageSettings(), "packageSettings");
        assertEquals(legacy.frameworkSettings(), adapted.frameworkSettings(), "frameworkSettings");
        assertEquals(legacy.dependencyMetadata(), adapted.dependencyMetadata(), "dependencyMetadata");
    }
}
