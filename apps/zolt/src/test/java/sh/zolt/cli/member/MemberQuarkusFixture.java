package sh.zolt.cli.member;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import sh.zolt.cli.ContentAddressedLockTestSupport;

/**
 * A two-member workspace whose {@code apps/api} is Quarkus-enabled, with a hand-written root lock
 * carrying a runtime extension, its {@code quarkus-deployment} artifact, and a sibling-only package
 * that belongs to no lane of {@code apps/api}.
 *
 * <p>The lock is written rather than resolved because the shape under test is a scope the resolver
 * only produces against real Quarkus extension metadata; what matters here is which packages a member
 * plan selects out of the one root lock, not how they got into it.
 */
final class MemberQuarkusFixture {
    static final String QUARKUS_MEMBER = "apps/api";
    static final String OTHER_MEMBER = "libs/unrelated";
    static final String SIBLING_ONLY_JAR = "sibling-only-1.0.0.jar";
    static final String RUNTIME_JAR = "quarkus-rest-3.33.0.jar";
    static final String DEPLOYMENT_JAR = "quarkus-rest-deployment-3.33.0.jar";

    private final Path workspaceDir;
    private final Path cacheRoot;

    private MemberQuarkusFixture(Path workspaceDir, Path cacheRoot) {
        this.workspaceDir = workspaceDir;
        this.cacheRoot = cacheRoot;
    }

    Path workspaceDir() {
        return workspaceDir;
    }

    Path cacheRoot() {
        return cacheRoot;
    }

    Path quarkusMember() {
        return workspaceDir.resolve(QUARKUS_MEMBER);
    }

    Path rootLock() {
        return workspaceDir.resolve("zolt.lock");
    }

    Path memberLock() {
        return quarkusMember().resolve("zolt.lock");
    }

    /** As {@link MemberProjectionFixture#plantPoisonedMemberLock}: valid, current, fingerprint-matching. */
    String plantPoisonedMemberLock() throws IOException {
        ContentAddressedLockTestSupport.write(memberLock(), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:poison"
                version = "9.9.9"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:poison"
                version = "9.9.9"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/poison/9.9.9/poison-9.9.9.jar"
                dependencies = []
                """);
        return Files.readString(memberLock());
    }

    static MemberQuarkusFixture create(Path tempDir) throws IOException {
        Path workspaceDir = Files.createTempDirectory(tempDir, "quarkus-workspace");
        Path cacheRoot = workspaceDir.resolveSibling(workspaceDir.getFileName() + "-cache");
        Path quarkusMember = workspaceDir.resolve(QUARKUS_MEMBER);
        Path otherMember = workspaceDir.resolve(OTHER_MEMBER);
        Files.createDirectories(quarkusMember);
        Files.createDirectories(otherMember);
        emptyJar(cacheRoot.resolve("io/quarkus/quarkus-rest/3.33.0/" + RUNTIME_JAR));
        emptyJar(cacheRoot.resolve("io/quarkus/quarkus-rest-deployment/3.33.0/" + DEPLOYMENT_JAR));
        emptyJar(cacheRoot.resolve("com/example/sibling-only/1.0.0/" + SIBLING_ONLY_JAR));

        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "family"

                [workspace.members]
                include = ["apps/api", "libs/unrelated"]

                [workspace.project]
                group = "com.example"
                version = "1.0.0"
                java = %s
                """.formatted(Runtime.version().feature()));
        Files.writeString(quarkusMember.resolve("zolt.toml"), """
                [project]
                name = "api"
                main = "com.example.api.Main"

                [package]
                mode = "quarkus"
                """);
        Files.writeString(otherMember.resolve("zolt.toml"), """
                [project]
                name = "unrelated"
                """);
        ContentAddressedLockTestSupport.write(workspaceDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "apps/api"
                id = "io.quarkus:quarkus-rest"
                version = "3.33.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "libs/unrelated"
                id = "com.example:sibling-only"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "io.quarkus:quarkus-rest"
                version = "3.33.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "io/quarkus/quarkus-rest/3.33.0/%s"
                dependencies = []
                members = ["apps/api"]

                [[package]]
                id = "io.quarkus:quarkus-rest-deployment"
                version = "3.33.0"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                jar = "io/quarkus/quarkus-rest-deployment/3.33.0/%s"
                dependencies = []
                members = ["apps/api"]

                [[package]]
                id = "com.example:sibling-only"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/sibling-only/1.0.0/%s"
                dependencies = []
                members = ["libs/unrelated"]
                """.formatted(RUNTIME_JAR, DEPLOYMENT_JAR, SIBLING_ONLY_JAR));
        return new MemberQuarkusFixture(workspaceDir, cacheRoot);
    }

    private static void emptyJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.flush();
        }
    }
}
