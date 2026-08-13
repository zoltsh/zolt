package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code zolt publish --dry-run --central} from inside a workspace member. Members never get a
 * member-level {@code zolt.lock}, so this plans through the workspace planner against the aggregated
 * root lock and renders the same Central readiness checklist a standalone project gets. Everything is
 * offline: the fixture repository serves the resolve step and must see no request afterwards.
 */
final class PublishCommandMemberCentralDryRunTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberDirectoryRendersTheCentralChecklistFromTheAggregatedLockWithoutNetwork() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path memberDir = workspace(repository, "core", readyMemberConfig("core", repository));
            Path cacheRoot = tempDir.resolve("cache");
            assertEquals(0, resolveWorkspace(memberDir, cacheRoot).exitCode());
            // A --workspace resolve writes only the root lock; the member deliberately has none.
            assertFalse(Files.exists(memberDir.resolve("zolt.lock")));
            // Package the member for real through the workspace, so its evidence matches the same
            // per-member package lock the publish dry run plans against.
            CommandResult packaged = execute("package", "--workspace", "--member", "modules/core",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());
            assertEquals(0, packaged.exitCode(), packaged.stdout() + packaged.stderr());
            repository.clearAuthorizations();

            CommandResult result = execute("publish", "--dry-run", "--central",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("Maven Central readiness:"), result.stdout());
            assertTrue(result.stdout().contains("- [x] release version"), result.stdout());
            assertTrue(result.stdout().contains("- [x] sources jar"), result.stdout());
            assertTrue(result.stdout().contains("- [x] javadoc jar"), result.stdout());
            assertTrue(result.stdout().contains("- [x] gpg signatures"), result.stdout());
            assertTrue(result.stdout().contains("- [x] checksums"), result.stdout());
            assertTrue(result.stdout().contains("Central status: ready"), result.stdout());
            // The member's own coordinates, not the workspace root's.
            assertTrue(result.stdout().contains("com.example:core:0.1.0"), result.stdout());
            assertTrue(result.stdout().contains("Central bundle:"), result.stdout());
            assertEquals(java.util.Map.of(), repository.authorizations(),
                    "the Central dry run must not touch the network");
        }
    }

    @Test
    void memberWithIncompleteReadinessShowsTheUncheckedBoxes() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path memberDir = workspace(repository, "bare", bareMemberConfig("bare", repository));
            Path cacheRoot = tempDir.resolve("cache");
            assertEquals(0, resolveWorkspace(memberDir, cacheRoot).exitCode());
            repository.clearAuthorizations();

            CommandResult result = execute("publish", "--dry-run", "--central",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("- [x] release version"), result.stdout());
            assertTrue(result.stdout().contains("- [ ] project name"), result.stdout());
            assertTrue(result.stdout().contains("Next: Add [package.metadata].name."), result.stdout());
            assertTrue(result.stdout().contains("- [ ] license name and url"), result.stdout());
            assertTrue(result.stdout().contains("- [ ] developer information"), result.stdout());
            assertTrue(result.stdout().contains("- [ ] sources jar"), result.stdout());
            assertTrue(result.stdout().contains("- [ ] javadoc jar"), result.stdout());
            assertTrue(result.stdout().contains("- [ ] gpg signatures"), result.stdout());
            assertTrue(result.stdout().contains("Central status: not ready"), result.stdout());
            assertEquals(java.util.Map.of(), repository.authorizations(),
                    "the Central dry run must not touch the network");
        }
    }

    /**
     * The member route plans from the workspace lock, so it owes the same guarantee {@code --workspace}
     * gives: {@code --offline} means no repository is contacted. With no root lock at all the planner has
     * to resolve, and offline resolution must fail from the cache rather than reach for the network.
     */
    @Test
    void offlineMemberDryRunWithNoRootLockFailsWithoutTouchingTheNetwork() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path memberDir = workspace(repository, "core", readyMemberConfig("core", repository));
            Path cacheRoot = tempDir.resolve("cache");
            // Deliberately no `resolve --workspace`: the root lock is absent, which is what forces the
            // planner to resolve rather than read.
            assertFalse(Files.exists(memberDir.getParent().getParent().resolve("zolt.lock")));
            repository.clearAuthorizations();

            CommandResult result = execute("publish", "--dry-run", "--central", "--offline",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
            assertEquals(java.util.Map.of(), repository.authorizations(),
                    "--offline must reach no repository, even with no workspace lock to plan from");
        }
    }

    /**
     * A stale root lock is refused, not silently planned against — the same contract
     * {@code zolt publish --workspace} enforces through the shared
     * {@code CommandLockfiles.requireFreshWorkspaceLockfile} gate. The gate verifies rather than
     * regenerates, so the refusal is identical online and offline; both modes are pinned here.
     */
    @Test
    void staleRootLockRefusesTheMemberDryRunOnlineAndOffline() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path memberDir = workspace(repository, "core", readyMemberConfig("core", repository));
            Path workspaceDir = memberDir.getParent().getParent();
            Path cacheRoot = tempDir.resolve("cache");
            assertEquals(0, resolveWorkspace(memberDir, cacheRoot).exitCode());
            String freshLockfile = Files.readString(workspaceDir.resolve("zolt.lock"));
            // Change a resolution input so the root lock no longer matches the workspace config. The
            // repository URL is declared in both files and they must agree, so both move together.
            String changed = repository.baseUri() + "./";
            Files.writeString(workspaceDir.resolve("zolt.toml"), """
                    [workspace]
                    name = "family"
                    members = ["modules/core"]

                    [repositories]
                    test = "%s"
                    """.formatted(changed));
            Files.writeString(memberDir.resolve("zolt.toml"), readyMemberConfig("core", changed));

            CommandResult online = execute("publish", "--dry-run", "--central",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());
            CommandResult offline = execute("publish", "--dry-run", "--central", "--offline",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());
            // The same fixture through the --workspace entry path, to pin that the two agree.
            CommandResult family = execute("publish", "--dry-run", "--workspace", "--all",
                    "--cwd", memberDir.toString(),
                    "--cache-root", cacheRoot.toString());

            assertEquals(1, online.exitCode(), online.stdout() + online.stderr());
            assertTrue(online.stderr().contains("Workspace zolt.lock is out of date"), online.stderr());
            assertEquals(1, offline.exitCode(), offline.stdout() + offline.stderr());
            assertTrue(offline.stderr().contains("Workspace zolt.lock is out of date"), offline.stderr());
            assertEquals(1, family.exitCode(), family.stdout() + family.stderr());
            assertTrue(family.stderr().contains("Workspace zolt.lock is out of date"), family.stderr());
            // The gate never rewrites the lock it refused.
            assertEquals(freshLockfile, Files.readString(workspaceDir.resolve("zolt.lock")));
        }
    }

    /**
     * Membership is settled before the workspace-lock gate, not after. That gate applies to every
     * directory beneath a workspace root, so asking it first refused a standalone project that merely
     * sits inside a workspace tree — over a lock governing members it is not one of. Here the root lock
     * is stale and the nested non-member's own lock is fresh: the standalone path must run untouched.
     */
    @Test
    void aStaleWorkspaceLockNeverRefusesANestedNonMemberProject() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path memberDir = workspace(repository, "core", readyMemberConfig("core", repository));
            Path workspaceDir = memberDir.getParent().getParent();
            Path cacheRoot = tempDir.resolve("cache");
            assertEquals(0, resolveWorkspace(memberDir, cacheRoot).exitCode());

            // A standalone project under the workspace tree that the workspace never declares. It owns
            // its own zolt.lock, resolved and packaged on the standalone path.
            Path outsider = workspaceDir.resolve("modules/outsider");
            Files.createDirectories(outsider);
            Files.writeString(outsider.resolve("zolt.toml"), readyMemberConfig("outsider", repository));
            Path source = outsider.resolve("src/main/java/com/example/outsider/Main.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package com.example.outsider;

                    public final class Main {
                    }
                    """);
            CommandResult packaged = execute("package",
                    "--cwd", outsider.toString(), "--cache-root", cacheRoot.toString());
            assertEquals(0, packaged.exitCode(), packaged.stdout() + packaged.stderr());
            assertTrue(Files.exists(outsider.resolve("zolt.lock")));

            // Make the WORKSPACE root lock stale; the outsider's own lock is untouched by this.
            String changed = repository.baseUri() + "./";
            Files.writeString(workspaceDir.resolve("zolt.toml"), """
                    [workspace]
                    name = "family"
                    members = ["modules/core"]

                    [repositories]
                    test = "%s"
                    """.formatted(changed));
            Files.writeString(memberDir.resolve("zolt.toml"), readyMemberConfig("core", changed));
            repository.clearAuthorizations();

            CommandResult result = execute("publish", "--dry-run", "--central",
                    "--cwd", outsider.toString(), "--cache-root", cacheRoot.toString());

            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertFalse(result.stderr().contains("Workspace zolt.lock is out of date"), result.stderr());
            // Planned as itself, from its own lock — not as a member of the family.
            assertTrue(result.stdout().contains("com.example:outsider:0.1.0"), result.stdout());
            assertEquals(java.util.Map.of(), repository.authorizations(),
                    "the standalone dry run must not touch the network");
        }
    }

    @Test
    void nonMemberDirectoryInsideAWorkspaceTreeKeepsTheStandaloneLockPath() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            Path memberDir = workspace(repository, "core", readyMemberConfig("core", repository));
            Path workspaceDir = memberDir.getParent().getParent();
            // A directory under the workspace tree that is NOT a declared member stays standalone, so it
            // still needs its own zolt.lock rather than borrowing the workspace one.
            Path outsider = workspaceDir.resolve("modules/outsider");
            Files.createDirectories(outsider);
            Files.writeString(outsider.resolve("zolt.toml"), readyMemberConfig("outsider", repository));

            CommandResult result = execute("publish", "--dry-run", "--central", "--cwd", outsider.toString());

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("Could not read zolt.lock"), result.stderr());
            assertTrue(result.stderr().contains(outsider.resolve("zolt.lock").toString()), result.stderr());
        }
    }

    private Path workspace(CliTestRepository repository, String name, String memberToml) throws IOException {
        repository.addArtifact("com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
        Path workspaceDir = tempDir.resolve("workspace-" + name);
        Path memberDir = workspaceDir.resolve("modules/" + name);
        Files.createDirectories(memberDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "family"
                members = ["modules/%s"]

                [repositories]
                test = "%s"
                """.formatted(name, repository.baseUri()));
        Files.writeString(memberDir.resolve("zolt.toml"), memberToml);
        Path source = memberDir.resolve("src/main/java/com/example/" + name + "/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.%s;

                public final class Main {
                }
                """.formatted(name));
        return memberDir;
    }

    private static CommandResult resolveWorkspace(Path memberDir, Path cacheRoot) {
        return execute("resolve", "--workspace",
                "--cwd", memberDir.toString(),
                "--cache-root", cacheRoot.toString());
    }

    private static String readyMemberConfig(String name, CliTestRepository repository) {
        return readyMemberConfig(name, repository.baseUri().toString());
    }

    private static String readyMemberConfig(String name, String repositoryUri) {
        return memberConfig(name) + """

                [repositories]
                test = "%s"

                [dependencies]
                "com.example:app" = "1.0.0"

                [package]
                sources = true
                javadoc = true

                [package.metadata]
                name = "Family Core"
                description = "A Central-ready workspace member."
                url = "https://example.com/family-core"
                license = "Apache-2.0"
                licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                scm = "https://github.com/example/family"
                scmConnection = "scm:git:https://github.com/example/family.git"

                [package.metadata.developer.ada]
                name = "Ada Lovelace"
                email = "ada@example.com"

                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"

                [publish.signing]
                enabled = true
                keyId = "ABCDEF0123456789"
                """.formatted(repositoryUri);
    }

    private static String bareMemberConfig(String name, CliTestRepository repository) {
        return memberConfig(name) + """

                [repositories]
                test = "%s"

                [dependencies]
                "com.example:app" = "1.0.0"

                [publish]
                releaseRepository = "company-releases"

                [publish.repositories.company-releases]
                url = "https://repo.example.test/releases"
                """.formatted(repository.baseUri());
    }
}
