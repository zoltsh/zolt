package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectDeveloper;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredProjectScm;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class ManifestIdentityWriterTest {
    @Test
    void emitsTheRootProjectWorkspaceGoldenIdentityExactly() {
        AuthoredWorkspace workspace = new AuthoredWorkspace(
                new LocalId("platform"),
                new AuthoredWorkspaceMembers(
                        List.of(
                                new WorkspaceMemberPattern("modules/*"),
                                new WorkspaceMemberPattern(".")),
                        List.of(),
                        Optional.of(List.of(new WorkspaceMemberPath(".")))),
                Optional.of(new AuthoredWorkspaceProjectDefaults(
                        Optional.of(new ProjectGroup("com.example")),
                        Optional.of(new ProjectVersion("1.4.0")),
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.empty())));
        AuthoredProject project = project(
                new AuthoredProjectIdentity(
                        new ProjectName("platform-root"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                AuthoredProjectMetadata.empty());

        assertEquals(
                """
                [workspace]
                name = "platform"

                [workspace.members]
                default = ["."]
                include = [".", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "1.4.0"
                java = 21

                [project]
                name = "platform-root"
                """,
                write(Optional.of(workspace), Optional.of(project)));
    }

    @Test
    void emitsRichProjectMetadataLicenseScmAndSortedDevelopers() {
        ProjectLicense.Metadata license = new ProjectLicense.Metadata(
                Optional.of("Apache-2.0 OR MIT"),
                Optional.of("Apache License 2.0 or MIT License"),
                Optional.of("https://example.com/licenses"));
        AuthoredProject project = project(
                new AuthoredProjectIdentity(
                        new ProjectName("example-library"),
                        Optional.of(new ProjectVersion("1.0.0")),
                        Optional.of(new ProjectGroup("com.example")),
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.of(license)),
                new AuthoredProjectMetadata(
                        Optional.of(new JavaBinaryClassName("com.example.Main")),
                        Optional.of("A reusable Java library."),
                        Optional.of("https://example.com/library"),
                        Optional.of("https://github.com/example/library/issues"),
                        Optional.of(new AuthoredProjectScm(
                                Optional.of("https://github.com/example/library"),
                                Optional.of("scm:git:https://github.com/example/library.git"),
                                Optional.of("scm:git:ssh://git@github.com/example/library.git"),
                                Optional.of("v1.0.0"))),
                        Map.of(
                                new LocalId("zed"),
                                new AuthoredProjectDeveloper(
                                        Optional.of("Zed"),
                                        Optional.empty(),
                                        Optional.empty(),
                                        Optional.empty()),
                                new LocalId("ada"),
                                new AuthoredProjectDeveloper(
                                        Optional.of("Ada Lovelace"),
                                        Optional.of("ada@example.com"),
                                        Optional.of("Analytical Engines"),
                                        Optional.of("https://example.com/ada")))));

        String output = write(Optional.empty(), Optional.of(project));

        assertEquals(
                """
                [project]
                name = "example-library"
                version = "1.0.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"
                description = "A reusable Java library."
                url = "https://example.com/library"
                issues = "https://github.com/example/library/issues"
                license = { id = "Apache-2.0 OR MIT", name = "Apache License 2.0 or MIT License", url = "https://example.com/licenses" }

                [project.scm]
                url = "https://github.com/example/library"
                connection = "scm:git:https://github.com/example/library.git"
                developerConnection = "scm:git:ssh://git@github.com/example/library.git"
                tag = "v1.0.0"

                [project.developers.ada]
                name = "Ada Lovelace"
                email = "ada@example.com"
                organization = "Analytical Engines"
                url = "https://example.com/ada"

                [project.developers.zed]
                name = "Zed"
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        assertEquals(project, decodeAuthoredManifest(output).project().orElseThrow());
    }

    @Test
    void preservesSparseProjectOmission() {
        AuthoredProject project = project(
                new AuthoredProjectIdentity(
                        new ProjectName("orders-core"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()),
                AuthoredProjectMetadata.empty());

        assertEquals(
                """
                [project]
                name = "orders-core"
                """,
                write(Optional.empty(), Optional.of(project)));
    }

    @Test
    void emitsLicenseIdentifierUsingTheStringShorthand() {
        AuthoredProject project = project(
                new AuthoredProjectIdentity(
                        new ProjectName("licensed"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new ProjectLicense.Identifier("Apache-2.0"))),
                AuthoredProjectMetadata.empty());

        assertEquals(
                """
                [project]
                name = "licensed"
                license = "Apache-2.0"
                """,
                write(Optional.empty(), Optional.of(project)));
    }

    private static AuthoredProject project(
            AuthoredProjectIdentity identity, AuthoredProjectMetadata metadata) {
        return new AuthoredProject(identity, metadata);
    }

    private static String write(
            Optional<AuthoredWorkspace> workspace, Optional<AuthoredProject> project) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestIdentityWriter().write(emitter, workspace, project);
        return emitter.finish();
    }
}
