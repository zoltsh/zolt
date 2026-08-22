package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.toml.ZoltConfigException;

/**
 * Design §10 "--no-resolve": the flag suppresses artifact refresh and the {@code zolt.lock} rewrite,
 * never semantic validation. A member edit is still composed against the complete effective
 * workspace — root plus every sibling — before a single byte reaches the member manifest.
 */
final class NoResolveWorkspaceCompositionTest {
    private static final ManifestMutationServices MANIFESTS = new ManifestMutationServices();

    @TempDir
    private Path tempDir;

    @Test
    void noResolveRejectsRootOwnedAliasRedeclaration() throws IOException {
        Path member = workspace();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> edit(member, current -> AuthoredManifestMutator.setVersionAlias(
                        current, new LocalId("shared"), new VersionAliasValue("9.9.9"))));

        assertTrue(failure.getMessage().contains("root-owned versions entry `shared`"), failure.getMessage());
        assertManifestUnchanged(member);
    }

    @Test
    void noResolveRejectsRootOwnedPlatformRedeclaration() throws IOException {
        Path member = workspace();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> edit(member, current -> AuthoredManifestMutator.setPlatform(
                        current,
                        new DependencyCoordinate("com.example:platform"),
                        new PlatformSelector.FixedVersion("3.0.0"))));

        assertTrue(
                failure.getMessage().contains("root-owned platforms entry `com.example:platform`"),
                failure.getMessage());
        assertManifestUnchanged(member);
    }

    /**
     * {@code [credentials]} is not a source-editable domain, so the guard is reached with the edited
     * source directly rather than through a mutation command.
     */
    @Test
    void noResolveRejectsRootOwnedCredentialRedeclaration() throws IOException {
        Path member = workspace();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> compose(member, """
                        [project]
                        name = "api"

                        [credentials.internal]
                        usernameEnv = "MEMBER_USER"
                        passwordEnv = "MEMBER_TOKEN"
                        """));

        assertTrue(
                failure.getMessage().contains("root-owned credentials entry `internal`"),
                failure.getMessage());
        assertManifestUnchanged(member);
    }

    @Test
    void noResolveRejectsMissingWorkspaceProvider() throws IOException {
        Path member = workspace();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> edit(member, current -> AuthoredManifestMutator.setDependency(
                        current,
                        new AuthoredDependency(
                                DependencyLane.IMPLEMENTATION,
                                new DependencyCoordinate("com.example:absent"),
                                new DependencySelector.Workspace(),
                                AuthoredDependencyMetadata.none()))));

        assertTrue(failure.getMessage().contains("com.example:absent"), failure.getMessage());
        assertManifestUnchanged(member);
    }

    /** {@code [project].name} is not a source-editable domain; the guard is reached the same way. */
    @Test
    void noResolveRejectsDuplicateEffectiveProjectIdentity() throws IOException {
        Path member = workspace();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> compose(member, """
                        [project]
                        name = "core"
                        """));

        assertTrue(
                failure.getMessage().contains("duplicate effective project identity"),
                failure.getMessage());
        assertManifestUnchanged(member);
    }

    @Test
    void failedNoResolveCompositionWritesNothing() throws IOException {
        Path member = workspace();
        Path root = member.getParent().getParent();
        String memberBefore = Files.readString(member.resolve("zolt.toml"));
        String rootBefore = Files.readString(root.resolve("zolt.toml"));
        String lockBefore = Files.readString(root.resolve("zolt.lock"));

        assertThrows(
                ZoltConfigException.class,
                () -> edit(member, current -> AuthoredManifestMutator.setVersionAlias(
                        current, new LocalId("shared"), new VersionAliasValue("9.9.9"))));

        assertEquals(memberBefore, Files.readString(member.resolve("zolt.toml")));
        assertEquals(rootBefore, Files.readString(root.resolve("zolt.toml")));
        assertEquals(lockBefore, Files.readString(root.resolve("zolt.lock")));
        assertFalse(
                Files.exists(member.resolve("zolt.lock")),
                "a member-local lockfile must never be created by a no-resolve edit");
    }

    @Test
    void noResolveStillCommitsAValidWorkspaceEdit() throws IOException {
        Path member = workspace();
        Path root = member.getParent().getParent();
        String lockBefore = Files.readString(root.resolve("zolt.lock"));

        ManifestEditResult result = edit(member, current -> AuthoredManifestMutator.setVersionAlias(
                current, new LocalId("local"), new VersionAliasValue("4.5.6")));

        assertTrue(result.manifestChanged());
        assertFalse(result.lockfileChanged());
        assertTrue(Files.readString(member.resolve("zolt.toml")).contains("local = \"4.5.6\""));
        assertEquals(lockBefore, Files.readString(root.resolve("zolt.lock")));
    }

    private ManifestEditResult edit(Path projectRoot, UnaryOperator<AuthoredManifest> mutation) {
        return ManifestEditTransaction.execute(
                projectRoot, tempDir.resolve("cache"), true, MANIFESTS, null, mutation);
    }

    /** The guard {@code --no-resolve} now runs, fed an edited source the source editor cannot write. */
    private static void compose(Path member, String editedSource) {
        ManifestMutationScope scope = ManifestMutationScope.discover(
                member, member.getParent().getParent());
        NoResolveWorkspaceComposition.requireComposable(
                scope.workspace(), scope.manifestPath(), editedSource);
    }

    private void assertManifestUnchanged(Path member) throws IOException {
        assertEquals("""
                [project]
                name = "api"
                """, Files.readString(member.resolve("zolt.toml")));
    }

    private Path workspace() throws IOException {
        Path root = Files.createTempDirectory(tempDir, "workspace-");
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21

                [versions]
                shared = "1.2.3"

                [credentials.internal]
                usernameEnv = "ROOT_USER"
                passwordEnv = "ROOT_TOKEN"

                [platforms]
                "com.example:platform" = "2.0.0"
                """);
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        Path member = root.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                """);
        Path sibling = root.resolve("apps/core");
        Files.createDirectories(sibling);
        Files.writeString(sibling.resolve("zolt.toml"), """
                [project]
                name = "core"
                """);
        return member;
    }
}
