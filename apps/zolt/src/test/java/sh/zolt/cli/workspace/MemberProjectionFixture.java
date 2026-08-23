package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.cli.ContentAddressedLockTestSupport;

/**
 * A three-member workspace, really resolved, built so that consuming the WHOLE root lock from one
 * member is impossible to miss.
 *
 * <p>{@code apps/api} depends on {@code libs/core} as a workspace provider and on {@code api-only} of
 * its own; {@code libs/core} pulls {@code sibling-only}, which therefore reaches {@code apps/api}
 * transitively at runtime. {@code libs/unrelated} is outside {@code apps/api}'s closure entirely and
 * pulls {@code unrelated-only} — the canary. Every report a member-directory command produces must
 * contain the first three and must not contain the fourth.
 *
 * <p>Each external POM declares a DIFFERENT license, so a leak is legible as a license that should
 * not be there rather than as a coordinate a reader has to reason about: {@code unrelated-only} is
 * the only {@link #LEAKED_LICENSE} in the workspace.
 *
 * <p>The lock is produced by a real {@code zolt resolve --workspace} against a live fixture
 * repository, not hand-written, so these tests describe what the resolver actually records —
 * member-qualified roots, member graphs, and all.
 */
final class MemberProjectionFixture implements AutoCloseable {
    static final String API_MEMBER = "apps/api";
    static final String CORE_MEMBER = "libs/core";
    static final String UNRELATED_MEMBER = "libs/unrelated";

    static final String API_ONLY = "com.example:api-only";
    static final String SIBLING_ONLY = "com.example:sibling-only";
    static final String UNRELATED_ONLY = "com.example:unrelated-only";
    static final String PROVIDER = "com.example:core";

    /** Declared only by {@code unrelated-only}, so it can appear only through a whole-lock leak. */
    static final String LEAKED_LICENSE = "GPL-3.0-only";
    static final String API_ONLY_LICENSE = "Apache-2.0";
    static final String SIBLING_ONLY_LICENSE = "MIT";

    /** A coordinate no member declares, for the planted member-local lock to name. */
    static final String POISON = "poison";

    private final CliTestRepository repository;
    private final Path workspaceDir;
    private final Path cacheRoot;
    private final boolean ownsRepository;

    private MemberProjectionFixture(
            CliTestRepository repository,
            Path workspaceDir,
            Path cacheRoot,
            boolean ownsRepository) {
        this.repository = repository;
        this.workspaceDir = workspaceDir;
        this.cacheRoot = cacheRoot;
        this.ownsRepository = ownsRepository;
    }

    static MemberProjectionFixture create(Path tempDir) throws IOException {
        return create(tempDir, false);
    }

    /**
     * The same workspace with {@code apps/api} and {@code libs/core} publishable to the fixture
     * repository, so every {@code zolt publish} mode — plain dry run, plain upload, Central dry run,
     * Central upload, SBOM attachment, release-policy preflight — has something real to plan and push.
     */
    static MemberProjectionFixture createPublishable(Path tempDir) throws IOException {
        return create(tempDir, true);
    }

    /**
     * Two fixtures of the same workspace against ONE repository, for a caller comparing their outputs.
     * A second repository would listen on a second port, which changes the workspace config and so the
     * workspace resolution fingerprint the lock records — a difference that has nothing to do with what
     * the caller is measuring. The caller owns the repository's lifecycle.
     */
    static MemberProjectionFixture createPublishable(Path tempDir, CliTestRepository repository)
            throws IOException {
        return create(tempDir, true, repository, false);
    }

    private static MemberProjectionFixture create(Path tempDir, boolean publishable) throws IOException {
        return create(tempDir, publishable, CliTestRepository.start(), true);
    }

    private static MemberProjectionFixture create(
            Path tempDir,
            boolean publishable,
            CliTestRepository repository,
            boolean ownsRepository) throws IOException {
        repository.addArtifact("com.example", "api-only", "1.0.0", pom("api-only", API_ONLY_LICENSE));
        repository.addArtifact("com.example", "sibling-only", "1.0.0", pom("sibling-only", SIBLING_ONLY_LICENSE));
        repository.addArtifact("com.example", "unrelated-only", "1.0.0", pom("unrelated-only", LEAKED_LICENSE));

        // A unique directory per fixture: the matrix builds two of the same workspace side by side and
        // compares their output, so they must not share a tree.
        Path workspaceDir = Files.createTempDirectory(tempDir, "workspace");
        Path cacheRoot = workspaceDir.resolveSibling(workspaceDir.getFileName() + "-cache");
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "family"

                [workspace.members]
                include = ["apps/api", "libs/core", "libs/unrelated"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = %s

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(Runtime.version().feature(), repository.baseUri()));

        String publish = publishable ? publishable(repository.baseUri().toString()) : "";
        member(workspaceDir, API_MEMBER, "api", """
                [dependencies]
                "%s" = { workspace = true }
                "%s" = "1.0.0"
                """.formatted(PROVIDER, API_ONLY) + publish);
        member(workspaceDir, CORE_MEMBER, "core", """
                [dependencies]
                "%s" = "1.0.0"
                """.formatted(SIBLING_ONLY) + publish);
        member(workspaceDir, UNRELATED_MEMBER, "unrelated", """
                [dependencies]
                "%s" = "1.0.0"
                """.formatted(UNRELATED_ONLY));

        MemberProjectionFixture fixture =
                new MemberProjectionFixture(repository, workspaceDir, cacheRoot, ownsRepository);
        CommandResult resolved = execute("resolve", "--workspace",
                "--cwd", workspaceDir.toString(), "--cache-root", cacheRoot.toString());
        assertEquals(0, resolved.exitCode(), resolved.stdout() + resolved.stderr());
        return fixture;
    }

    CliTestRepository repository() {
        return repository;
    }

    Path workspaceDir() {
        return workspaceDir;
    }

    Path cacheRoot() {
        return cacheRoot;
    }

    Path apiDir() {
        return workspaceDir.resolve(API_MEMBER);
    }

    Path coreDir() {
        return workspaceDir.resolve(CORE_MEMBER);
    }

    Path unrelatedDir() {
        return workspaceDir.resolve(UNRELATED_MEMBER);
    }

    Path rootLock() {
        return workspaceDir.resolve("zolt.lock");
    }

    /** Where a member-local lock would live. The workspace never creates one. */
    Path memberLock() {
        return apiDir().resolve("zolt.lock");
    }

    /**
     * Plants a VALID, current-schema, fingerprint-matching {@code apps/api/zolt.lock} naming a package
     * no member depends on. Fingerprint-matching is the dangerous shape: a standalone freshness gate
     * accepts it silently, so a command that reads it produces a plausible wrong answer rather than an
     * error. Returns the planted bytes so a caller can prove they were neither consumed nor rewritten.
     */
    String plantPoisonedMemberLock() throws IOException {
        ContentAddressedLockTestSupport.write(memberLock(), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:%s"
                version = "9.9.9"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:%s"
                version = "9.9.9"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/%s/9.9.9/%s-9.9.9.jar"
                pom = "com/example/%s/9.9.9/%s-9.9.9.pom"
                dependencies = []
                """.formatted(POISON, POISON, POISON, POISON, POISON, POISON));
        return Files.readString(memberLock());
    }

    /** Runs a command in the {@code apps/api} member directory against this fixture's cache. */
    CommandResult api(String... arguments) {
        return in(apiDir(), arguments);
    }

    /** Runs a command in the workspace root directory. */
    CommandResult root(String... arguments) {
        return in(workspaceDir, arguments);
    }

    CommandResult in(Path directory, String... arguments) {
        String[] full = new String[arguments.length + 4];
        System.arraycopy(arguments, 0, full, 0, arguments.length);
        full[arguments.length] = "--cwd";
        full[arguments.length + 1] = directory.toString();
        full[arguments.length + 2] = "--cache-root";
        full[arguments.length + 3] = cacheRoot.toString();
        return execute(full);
    }

    @Override
    public void close() {
        if (ownsRepository) {
            repository.close();
        }
    }

    /**
     * A publishable member: release-shaped metadata plus a release repository pointing at the fixture
     * server, so a dry run plans a real POM and a live upload really uploads. Signing is deliberately
     * absent — these tests are about which lock the plan came from, and requiring a GPG key would make
     * them depend on the developer's keyring.
     */
    private static String publishable(String repositoryUrl) {
        return """

                [package]
                sources = true
                javadoc = true

                [publish]
                release = "company-releases"

                [publish.repositories.company-releases]
                url = "%s"
                """.formatted(repositoryUrl);
    }

    /**
     * Adds release-context metadata to a member, keeping TOML's rule that a table's own keys precede
     * the next table header: the scalars land under {@code [project]}, the sub-tables at the end.
     */
    static void addReleaseMetadata(Path memberDir) throws IOException {
        Path manifest = memberDir.resolve("zolt.toml");
        String source = Files.readString(manifest);
        int afterName = source.indexOf('\n', source.indexOf("name = ")) + 1;
        Files.writeString(manifest, source.substring(0, afterName) + """
                description = "A publishable workspace member."
                url = "https://example.com/family"
                issues = "https://example.com/family/issues"
                license = "Apache-2.0"
                """ + source.substring(afterName) + """

                [project.scm]
                url = "https://github.com/example/family"
                connection = "scm:git:https://github.com/example/family.git"

                [project.developers.ada]
                name = "Ada Lovelace"
                email = "ada@example.com"
                """);
    }

    private static void member(Path workspaceDir, String path, String name, String body)
            throws IOException {
        Path directory = workspaceDir.resolve(path);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                """.formatted(name) + body);
        Path source = directory.resolve("src/main/java/com/example/" + name + "/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.%s;

                public final class Main {
                }
                """.formatted(name));
    }

    private static String pom(String artifactId, String license) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <licenses>
                    <license>
                      <name>%s</name>
                      <url>https://example.test/%s</url>
                    </license>
                  </licenses>
                </project>
                """.formatted(artifactId, license, license);
    }
}
