package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.project.toolchain.KotlinToolchainVersion;

final class AuthoredKotlinToolchainManifestTest {
    @Test
    void standaloneBomRejectsKotlinWhileAVirtualRootMayShareIt() {
        AuthoredToolchains toolchains = new AuthoredToolchains(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredKotlinToolchain(
                        new KotlinToolchainVersion("2.2.0"))));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> manifest(
                        Optional.empty(),
                        Optional.of(project()),
                        toolchains,
                        bomPackaging()));
        assertEquals(
                "A BOM cannot author project-local Kotlin compiler toolchain.",
                failure.getMessage());

        AuthoredManifest virtualRoot = manifest(
                Optional.of(workspace()),
                Optional.empty(),
                toolchains,
                AuthoredPackaging.empty());
        assertEquals(
                new KotlinToolchainVersion("2.2.0"),
                virtualRoot.toolchains().kotlin().orElseThrow().version());
    }

    private static AuthoredProject project() {
        return new AuthoredProject(
                new AuthoredProjectIdentity(
                        new ProjectName("catalog"),
                        Optional.of(new ProjectVersion("1.0.0")),
                        Optional.of(new ProjectGroup("com.example")),
                        Optional.empty(),
                        Optional.empty()),
                AuthoredProjectMetadata.empty());
    }

    private static AuthoredWorkspace workspace() {
        return new AuthoredWorkspace(
                new LocalId("root"),
                new AuthoredWorkspaceMembers(
                        List.of(new WorkspaceMemberPattern("modules/*")),
                        List.of(),
                        Optional.empty()),
                Optional.empty());
    }

    private static AuthoredPackaging bomPackaging() {
        return new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredBom(
                        Optional.empty(), Optional.of(Map.of()), Optional.empty())));
    }

    private static AuthoredManifest manifest(
            Optional<AuthoredWorkspace> workspace,
            Optional<AuthoredProject> project,
            AuthoredToolchains toolchains,
            AuthoredPackaging packaging) {
        return new AuthoredManifest(
                workspace,
                project,
                toolchains,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.empty(),
                packaging,
                Optional.empty(),
                Optional.empty());
    }
}
