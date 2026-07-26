package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.publish.PublishPomGenerator;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.publish.WorkspaceMemberPomLockProjection;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorkspaceOptionalDependencyParityTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void directlyOptionalPackageRemainsRequiredWhenAlsoReachedThroughRequiredRoot() throws IOException {
        addArtifact("com.example", "shared", "1.0.0", pom("com.example", "shared", "1.0.0"));
        addArtifact(
                "com.example",
                "required-root",
                "1.0.0",
                dependencyPom("required-root", "shared"));
        workspace("""
                [workspace]
                name = "optional-required-reachability"
                members = ["modules/core", "apps/app"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("modules/core", "core", """

                [api.dependencies]
                "com.example:shared" = { version = "1.0.0", optional = true }
                "com.example:required-root" = "1.0.0"
                """);
        member("apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);

        Path cache = tempDir.resolve("cache");
        service.resolve(tempDir, cache, false, false);
        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        Workspace workspace = new WorkspaceDiscoveryService().discover(tempDir).orElseThrow();
        var app = new WorkspaceClasspathService()
                .classpathsFor(workspace, lockfile, cache, "apps/app");

        assertContains(app.compile(), "required-root-1.0.0.jar");
        assertContains(app.compile(), "shared-1.0.0.jar");

        LockPackage shared = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().artifactId().equals("shared"))
                .findFirst()
                .orElseThrow();
        LockMemberGraphIndex memberGraphs =
                new LockMemberGraphIndex(lockfile.memberGraphs(), lockfile.packages());
        assertTrue(memberGraphs.declaredOptionalFor("modules/core", shared));
        assertFalse(memberGraphs.optionalOnlyFor("modules/core", shared));

        WorkspaceMember coreMember = workspace.members().stream()
                .filter(member -> member.path().equals("modules/core"))
                .findFirst()
                .orElseThrow();
        ZoltLockfile projected = new WorkspaceMemberPomLockProjection()
                .project(coreMember.path(), coreMember.config(), lockfile);
        String pomXml = new PublishPomGenerator()
                .generate(coreMember.config(), projected);
        assertTrue(pomXml.contains("<artifactId>shared</artifactId>"));
        assertTrue(pomXml.contains("<optional>true</optional>"));

        addArtifact("com.acme", "core", "0.1.0", pomXml);
        ProjectConfig consumer = new ZoltTomlParser().parse("""
                [project]
                name = "published-consumer"
                version = "1.0.0"
                group = "com.consumer"
                java = "21"

                [repositories]
                test = "%s"

                [dependencies]
                "com.acme:core" = "0.1.0"
                """.formatted(baseUri));
        ZoltLockfile consumerLock = new ResolveService()
                .resolveLockfile(consumer, tempDir.resolve("consumer-cache"), false)
                .lockfile();
        Set<String> consumerArtifacts = artifactIds(consumerLock);
        assertTrue(consumerArtifacts.contains("required-root"));
        assertTrue(consumerArtifacts.contains("shared"));
    }

    @Test
    void pathSpecificExclusionKeepsOptionalOnlyLeafBehindWorkspaceBoundaryInBothOrders()
            throws IOException {
        addArtifact("com.example", "leaf", "1.0.0", pom("com.example", "leaf", "1.0.0"));
        addArtifact(
                "com.example",
                "shared",
                "1.0.0",
                dependencyPom("shared", "leaf"));
        addArtifact(
                "com.example",
                "optional-root",
                "1.0.0",
                dependencyPom("optional-root", "shared"));
        addArtifact(
                "com.example",
                "required-root",
                "1.0.0",
                dependencyPom("required-root", "shared"));

        for (boolean optionalFirst : List.of(true, false)) {
            Path root = tempDir.resolve(
                    optionalFirst ? "optional-first" : "required-first");
            writeOptionalExclusionWorkspace(root, optionalFirst);
            Path cache = root.resolve("cache");
            service.resolve(root, cache, false, false);

            ZoltLockfile lockfile =
                    lockfileReader.read(root.resolve("zolt.lock"));
            Workspace workspace = new WorkspaceDiscoveryService()
                    .discover(root)
                    .orElseThrow();
            var app = new WorkspaceClasspathService()
                    .classpathsFor(workspace, lockfile, cache, "apps/app");
            for (var classpath : List.of(app.compile(), app.runtime())) {
                assertContains(classpath, "required-root-1.0.0.jar");
                assertContains(classpath, "shared-1.0.0.jar");
                assertAbsent(classpath, "optional-root");
                assertAbsent(classpath, "leaf");
            }

            WorkspaceMember coreMember = workspace.members().stream()
                    .filter(member -> member.path().equals("modules/core"))
                    .findFirst()
                    .orElseThrow();
            ZoltLockfile projected = new WorkspaceMemberPomLockProjection()
                    .project(coreMember.path(), coreMember.config(), lockfile);
            String pomXml = new PublishPomGenerator()
                    .generate(coreMember.config(), projected);
            assertTrue(pomXml.contains("<artifactId>optional-root</artifactId>"));
            assertTrue(pomXml.contains("<optional>true</optional>"));
            assertTrue(pomXml.contains("<artifactId>required-root</artifactId>"));
            assertTrue(pomXml.contains("<artifactId>leaf</artifactId>"));

            addArtifact("com.acme", "core", "0.1.0", pomXml);
            ProjectConfig consumer = new ZoltTomlParser().parse("""
                    [project]
                    name = "published-consumer"
                    version = "1.0.0"
                    group = "com.consumer"
                    java = "21"

                    [repositories]
                    test = "%s"

                    [dependencies]
                    "com.acme:core" = "0.1.0"
                    """.formatted(baseUri));
            ZoltLockfile publishedConsumer = new ResolveService()
                    .resolveLockfile(
                            consumer,
                            root.resolve("published-consumer-cache"),
                            false)
                    .lockfile();
            Set<String> publishedArtifacts = publishedConsumer.packages().stream()
                    .map(lockPackage -> lockPackage.packageId().artifactId())
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(publishedArtifacts.contains("required-root"));
            assertTrue(publishedArtifacts.contains("shared"));
            assertFalse(publishedArtifacts.contains("optional-root"));
            assertFalse(publishedArtifacts.contains("leaf"));

            WorkspaceMember appMember = workspace.members().stream()
                    .filter(member -> member.path().equals("apps/app"))
                    .findFirst()
                    .orElseThrow();
            WorkspaceMemberPolicyResolver policyResolver =
                    new WorkspaceMemberPolicyResolver();
            WorkspaceMemberSbomLockProjection sbomProjection =
                    new WorkspaceMemberSbomLockProjection();
            ZoltLockfile coreSbom = sbomProjection.project(
                    coreMember.path(),
                    policyResolver.merge(workspace, coreMember),
                    lockfile,
                    workspace,
                    policyResolver);
            Set<String> coreSbomArtifacts = artifactIds(coreSbom);
            assertTrue(coreSbomArtifacts.contains("optional-root"));
            assertTrue(coreSbomArtifacts.contains("leaf"));

            ZoltLockfile appSbom = sbomProjection.project(
                    appMember.path(),
                    policyResolver.merge(workspace, appMember),
                    lockfile,
                    workspace,
                    policyResolver);
            Set<String> appSbomArtifacts = artifactIds(appSbom);
            assertTrue(appSbomArtifacts.contains("required-root"));
            assertTrue(appSbomArtifacts.contains("shared"));
            assertFalse(appSbomArtifacts.contains("optional-root"));
            assertFalse(appSbomArtifacts.contains("leaf"));
            assertFalse(appSbom.packages().stream()
                    .flatMap(lockPackage -> lockPackage.dependencies().stream())
                    .anyMatch(edge -> edge.contains(":optional-root:")
                            || edge.contains(":leaf:")));
        }
    }

    @Test
    void optionalDependenciesStayLocalButRemainPublishedAsOptional() throws IOException {
        addArtifact("com.example", "optional-api", "1.0.0", pom("com.example", "optional-api", "1.0.0"));
        addArtifact("com.example", "optional-impl", "1.0.0", pom("com.example", "optional-impl", "1.0.0"));
        addArtifact("com.example", "optional-runtime", "1.0.0", pom("com.example", "optional-runtime", "1.0.0"));
        workspace("""
                [workspace]
                name = "optional-parity"
                members = ["modules/feature-api", "modules/feature-impl", "modules/core", "apps/app"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("modules/feature-api", "feature-api", "");
        member("modules/feature-impl", "feature-impl", "");
        member("modules/core", "core", """

                [api.dependencies]
                "com.example:optional-api" = { version = "1.0.0", optional = true }
                "com.acme:feature-api" = { workspace = "modules/feature-api", optional = true }

                [dependencies]
                "com.example:optional-impl" = { version = "1.0.0", optional = true }
                "com.acme:feature-impl" = { workspace = "modules/feature-impl", optional = true }

                [runtime.dependencies]
                "com.example:optional-runtime" = { version = "1.0.0", optional = true }
                """);
        member("apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);

        Path cache = tempDir.resolve("cache");
        service.resolve(tempDir, cache, false, false);
        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        Workspace workspace = new WorkspaceDiscoveryService()
                .discover(tempDir)
                .orElseThrow();
        WorkspaceClasspathService classpaths = new WorkspaceClasspathService();

        var core = classpaths.classpathsFor(
                workspace, lockfile, cache, "modules/core");
        assertContains(core.compile(), "optional-api-1.0.0.jar");
        assertContains(core.compile(), "optional-impl-1.0.0.jar");
        assertContains(core.compile(), "modules/feature-api/target/classes");
        assertContains(core.compile(), "modules/feature-impl/target/classes");
        assertContains(core.runtime(), "optional-runtime-1.0.0.jar");

        var app = classpaths.classpathsFor(
                workspace, lockfile, cache, "apps/app");
        assertAbsent(app.compile(), "optional-api");
        assertAbsent(app.compile(), "optional-impl");
        assertAbsent(app.compile(), "feature-api");
        assertAbsent(app.compile(), "feature-impl");
        assertAbsent(app.runtime(), "optional-api");
        assertAbsent(app.runtime(), "optional-impl");
        assertAbsent(app.runtime(), "optional-runtime");
        assertAbsent(app.runtime(), "feature-api");
        assertAbsent(app.runtime(), "feature-impl");

        Map<String, List<ResolvedClasspathPackage>> packageInputs =
                classpaths.classpathPackagesForMembers(
                        workspace, lockfile, cache, List.of("apps/app"));
        assertFalse(packageInputs.get("apps/app").stream()
                .anyMatch(candidate -> candidate.resolvedPackage()
                        .packageId()
                        .artifactId()
                        .startsWith("optional-")));

        WorkspaceMember coreMember = workspace.members().stream()
                .filter(member -> member.path().equals("modules/core"))
                .findFirst()
                .orElseThrow();
        ZoltLockfile projected = new WorkspaceMemberPomLockProjection()
                .project(coreMember.path(), coreMember.config(), lockfile);
        String pomXml = new PublishPomGenerator()
                .generate(coreMember.config(), projected);
        assertTrue(pomXml.contains("<artifactId>optional-api</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>optional-impl</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>optional-runtime</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>feature-api</artifactId>"));
        assertTrue(pomXml.contains("<artifactId>feature-impl</artifactId>"));
        assertTrue(
                count(pomXml, "<optional>true</optional>") >= 5,
                pomXml);
    }

    private static void assertContains(
            Classpath classpath,
            String fragment) {
        assertTrue(classpath.entries().stream()
                .map(Path::toString)
                .anyMatch(value -> value.contains(fragment)));
    }

    private static void assertAbsent(
            Classpath classpath,
            String fragment) {
        assertFalse(classpath.entries().stream()
                .map(Path::toString)
                .anyMatch(value -> value.contains(fragment)));
    }

    private static int count(
            String value,
            String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static Set<String> artifactIds(ZoltLockfile lockfile) {
        return lockfile.packages().stream()
                .map(LockPackage::packageId)
                .map(packageId -> packageId.artifactId())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String dependencyPom(
            String artifact,
            String dependency) {
        return """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>%s</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(artifact, dependency);
    }

    private void writeOptionalExclusionWorkspace(
            Path root,
            boolean optionalFirst) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "path-aware-optional-%s"
                members = ["modules/core", "apps/app"]

                [repositories]
                test = "%s"
                """.formatted(optionalFirst ? "first" : "last", baseUri));
        writeMember(root, "modules/core", "core", optionalFirst
                ? """

                  [api.dependencies]
                  "com.example:optional-root" = { version = "1.0.0", optional = true }
                  "com.example:required-root" = { version = "1.0.0", exclusions = [{ group = "com.example", artifact = "leaf" }] }
                  """
                : """

                  [api.dependencies]
                  "com.example:required-root" = { version = "1.0.0", exclusions = [{ group = "com.example", artifact = "leaf" }] }
                  "com.example:optional-root" = { version = "1.0.0", optional = true }
                  """);
        writeMember(root, "apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
    }

    private static void writeMember(
            Path root,
            String path,
            String name,
            String extraToml) throws IOException {
        Path member = root.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "21"
                %s""".formatted(name, extraToml));
    }

}
