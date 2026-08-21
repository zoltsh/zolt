package sh.zolt.resolve.lockfile.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.graph.PackageNode;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.graph.ResolutionEdge;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.metadata.platform.ManagedVersion;
import sh.zolt.resolve.traversal.DependencyTraversalDecision;
import sh.zolt.resolve.version.VersionSelectionResult;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LockfileAssemblerTest {
    private static final PackageId APP = new PackageId("com.example", "app");
    private static final PackageId LIB = new PackageId("com.example", "lib");
    private static final PackageNode APP_NODE = new PackageNode(APP, "1.0.0");
    private static final PackageNode LIB_NODE = new PackageNode(LIB, "2.0.0");

    private final LockfileAssembler assembler = new LockfileAssembler(new CoordinateParser());

    @Test
    void assemblesPackagesDependenciesPoliciesAndMetrics() {
        DependencyRequest appRequest = new DependencyRequest(APP, "1.0.0", DependencyScope.COMPILE, RequestOrigin.DIRECT);
        DependencyRequest libRequest = new DependencyRequest(LIB, "2.0.0", DependencyScope.COMPILE, RequestOrigin.TRANSITIVE);
        ResolutionGraph graph = new ResolutionGraph(
                List.of(APP_NODE, LIB_NODE),
                List.of(new ResolutionEdge(
                        APP_NODE,
                        LIB_NODE,
                        libRequest,
                        DependencyTraversalDecision.include("test"))),
                List.of());
        FakeAssemblyContext context = new FakeAssemblyContext(configWithManagedDependencyAndAlias());
        context.managedVersions.put(APP, new ManagedVersion("1.0.0", "com.example:platform:1.0.0"));

        ZoltLockfile lockfile = assembler.assemble(
                context,
                graph,
                new VersionSelectionResult(List.of(APP_NODE, LIB_NODE), List.of()),
                List.of(appRequest));

        assertTrue(lockfile.aliasFingerprint().orElseThrow().startsWith("sha256:"));
        assertEquals(2, lockfile.packages().size());
        LockPackage app = lockfile.packages().getFirst();
        assertEquals(APP, app.packageId());
        assertEquals(List.of("com.example:lib:2.0.0:jar:compile"), app.dependencies());
        assertEquals(List.of(
                "managed-version: com.example:app -> 1.0.0 from com.example:platform:1.0.0"),
                app.policies());
        assertEquals("repo", app.source());
        assertTrue(app.jarSha256().isPresent());
        assertTrue(app.pomSha256().isPresent());
        assertTrue(context.lockfileAssemblyNanos > 0);
    }

    @Test
    void qualifiesEdgeTargetsByVariantAndScope() {
        PackageId netty = new PackageId("io.netty", "netty");
        PackageNode nettyNode = new PackageNode(netty, "4.1.100.Final");
        DependencyRequest appRequest = new DependencyRequest(APP, "1.0.0", DependencyScope.COMPILE, RequestOrigin.DIRECT);
        // The app depends on netty's linux-x86_64 classified jar; the request carries the classifier.
        DependencyRequest classifiedRequest = new DependencyRequest(
                netty,
                "4.1.100.Final",
                DependencyScope.COMPILE,
                RequestOrigin.TRANSITIVE,
                Optional.of(ArtifactDescriptor.jar(
                        new Coordinate("io.netty", "netty", Optional.of("4.1.100.Final")),
                        Optional.of("linux-x86_64"))));
        DependencyRequest plainRequest = new DependencyRequest(LIB, "2.0.0", DependencyScope.COMPILE, RequestOrigin.TRANSITIVE);
        ResolutionGraph graph = new ResolutionGraph(
                List.of(APP_NODE, LIB_NODE, nettyNode),
                List.of(
                        new ResolutionEdge(APP_NODE, nettyNode, classifiedRequest, DependencyTraversalDecision.include("t")),
                        new ResolutionEdge(APP_NODE, LIB_NODE, plainRequest, DependencyTraversalDecision.include("t"))),
                List.of());
        FakeAssemblyContext context = new FakeAssemblyContext(configWithManagedDependencyAndAlias());

        ZoltLockfile lockfile = assembler.assemble(
                context,
                graph,
                new VersionSelectionResult(List.of(APP_NODE, LIB_NODE, nettyNode), List.of()),
                List.of(appRequest));

        LockPackage app = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(APP))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(
                "com.example:lib:2.0.0:jar:compile",
                "io.netty:netty:4.1.100.Final:jar|linux-x86_64:compile"),
                app.dependencies());
    }

    @Test
    void preservesDistinctChildEdgesForNodeReachedAtMultipleScopes() {
        PackageId child = new PackageId("com.example", "child");
        PackageNode childNode = new PackageNode(child, "3.0.0");
        DependencyRequest appRequest = new DependencyRequest(APP, "1.0.0", DependencyScope.COMPILE, RequestOrigin.DIRECT);
        DependencyRequest compileLibRequest = new DependencyRequest(
                LIB, "2.0.0", DependencyScope.COMPILE, RequestOrigin.DIRECT);
        DependencyRequest testLibRequest = new DependencyRequest(
                LIB, "2.0.0", DependencyScope.TEST, RequestOrigin.DIRECT);
        DependencyRequest compileChildRequest = new DependencyRequest(
                child, "3.0.0", DependencyScope.COMPILE, RequestOrigin.TRANSITIVE);
        DependencyRequest testChildRequest = new DependencyRequest(
                child, "3.0.0", DependencyScope.TEST, RequestOrigin.TRANSITIVE);
        // LIB is reached at both compile and test scope. Those are distinct resolved target identities,
        // so both edges must survive rather than one scope silently selecting the other.
        ResolutionGraph graph = new ResolutionGraph(
                List.of(APP_NODE, LIB_NODE, childNode),
                List.of(
                        new ResolutionEdge(
                                LIB_NODE,
                                childNode,
                                compileChildRequest,
                                DependencyScope.COMPILE,
                                DependencyTraversalDecision.include("compile")),
                        new ResolutionEdge(
                                LIB_NODE,
                                childNode,
                                testChildRequest,
                                DependencyScope.TEST,
                                DependencyTraversalDecision.include("test"))),
                List.of());

        ZoltLockfile lockfile = assembler.assemble(
                new FakeAssemblyContext(baseConfig()),
                graph,
                new VersionSelectionResult(List.of(APP_NODE, LIB_NODE, childNode), List.of()),
                List.of(appRequest, compileLibRequest, testLibRequest));

        LockPackage compileLib = lockfile.packages().stream()
                .filter(pkg -> pkg.packageId().equals(LIB))
                .filter(pkg -> pkg.scope() == DependencyScope.COMPILE)
                .findFirst()
                .orElseThrow();
        LockPackage testLib = lockfile.packages().stream()
                .filter(pkg -> pkg.packageId().equals(LIB))
                .filter(pkg -> pkg.scope() == DependencyScope.TEST)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("com.example:child:3.0.0:jar:compile"), compileLib.dependencies());
        assertEquals(List.of("com.example:child:3.0.0:jar:test"), testLib.dependencies());
    }

    @Test
    void singleScopeChildEdgeCarriesItsResolvedScope() {
        PackageId child = new PackageId("com.example", "child");
        PackageNode childNode = new PackageNode(child, "3.0.0");
        DependencyRequest appRequest = new DependencyRequest(APP, "1.0.0", DependencyScope.COMPILE, RequestOrigin.DIRECT);
        DependencyRequest childRequest = new DependencyRequest(
                child, "3.0.0", DependencyScope.COMPILE, RequestOrigin.TRANSITIVE);
        ResolutionGraph graph = new ResolutionGraph(
                List.of(APP_NODE, LIB_NODE, childNode),
                List.of(new ResolutionEdge(
                        LIB_NODE, childNode, childRequest, DependencyTraversalDecision.include("compile"))),
                List.of());

        ZoltLockfile lockfile = assembler.assemble(
                new FakeAssemblyContext(baseConfig()),
                graph,
                new VersionSelectionResult(List.of(APP_NODE, LIB_NODE, childNode), List.of()),
                List.of(appRequest));

        LockPackage lib = lockfile.packages().stream()
                .filter(pkg -> pkg.packageId().equals(LIB))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("com.example:child:3.0.0:jar:compile"), lib.dependencies());
    }

    @Test
    void preservesClassifierJarDescriptor() {
        ArtifactDescriptor descriptor = new ArtifactDescriptor(
                new Coordinate("org.jacoco", "org.jacoco.agent", Optional.of("0.8.14")),
                Optional.of("runtime"),
                "jar");
        DependencyRequest request = new DependencyRequest(
                new PackageId("org.jacoco", "org.jacoco.agent"),
                "0.8.14",
                DependencyScope.TOOL_COVERAGE,
                RequestOrigin.DIRECT,
                Optional.of(descriptor));
        PackageNode node = new PackageNode(
                request.packageId(), request.requestedVersion(), request.artifactVariant());
        FakeAssemblyContext context = new FakeAssemblyContext(baseConfig());

        ZoltLockfile lockfile = assembler.assemble(
                context,
                new ResolutionGraph(List.of(node), List.of(), List.of()),
                new VersionSelectionResult(List.of(node), List.of()),
                List.of(request));

        LockPackage pkg = lockfile.packages().getFirst();
        assertEquals(Optional.of("org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14-runtime.jar"), pkg.jar());
        assertTrue(pkg.artifact().isEmpty());
        assertTrue(pkg.artifactType().isEmpty());
    }

    @Test
    void recordsTypedArtifactsOutsideJarFields() {
        PackageId schema = new PackageId("com.example", "schema");
        ArtifactDescriptor descriptor = new ArtifactDescriptor(
                new Coordinate(schema.groupId(), schema.artifactId(), Optional.of("1.0.0")),
                Optional.empty(),
                "properties");
        DependencyRequest request = new DependencyRequest(
                schema,
                "1.0.0",
                DependencyScope.TOOL_OPENAPI,
                RequestOrigin.DIRECT,
                Optional.of(descriptor));
        PackageNode node = new PackageNode(schema, "1.0.0", request.artifactVariant());

        ZoltLockfile lockfile = assembler.assemble(
                new FakeAssemblyContext(baseConfig()),
                new ResolutionGraph(List.of(node), List.of(), List.of()),
                new VersionSelectionResult(List.of(node), List.of()),
                List.of(request));

        LockPackage pkg = lockfile.packages().getFirst();
        assertTrue(pkg.jar().isEmpty());
        assertTrue(pkg.jarSha256().isEmpty());
        assertEquals(Optional.of("com/example/schema/1.0.0/schema-1.0.0.properties"), pkg.artifact());
        assertEquals(Optional.of("properties"), pkg.artifactType());
        assertTrue(pkg.artifactSha256().isPresent());
        assertTrue(pkg.pomSha256().isPresent());
    }

    @Test
    void assemblesDifferentVersionVariantsWithMatchingArtifactAndPomPaths() {
        DependencyRequest linux = classifiedRequest(
                "1.0.0", "linux-x86_64", DependencyScope.COMPILE);
        DependencyRequest tests = classifiedRequest(
                "2.0.0", "tests", DependencyScope.TEST);
        PackageNode linuxNode = new PackageNode(
                linux.packageId(), "1.0.0", linux.artifactVariant());
        PackageNode testsNode = new PackageNode(
                tests.packageId(), "2.0.0", tests.artifactVariant());

        ZoltLockfile lockfile = assembler.assemble(
                new FakeAssemblyContext(baseConfig()),
                new ResolutionGraph(List.of(linuxNode, testsNode), List.of(), List.of()),
                new VersionSelectionResult(List.of(linuxNode, testsNode), List.of()),
                List.of(linux, tests));

        assertEquals(2, lockfile.packages().size());
        assertVersionPathInvariant(lockfile, "linux-x86_64", "1.0.0");
        assertVersionPathInvariant(lockfile, "tests", "2.0.0");
    }

    @Test
    void aliasFingerprintIncludesPolicyAndGeneratedSourceVersionRefsDeterministically() {
        ZoltLockfile first = assembler.assemble(
                new FakeAssemblyContext(aliasFingerprintConfig("api", "fixtures")),
                new ResolutionGraph(List.of(), List.of(), List.of()),
                new VersionSelectionResult(List.of(), List.of()),
                List.of());
        ZoltLockfile second = assembler.assemble(
                new FakeAssemblyContext(aliasFingerprintConfig("fixtures", "api")),
                new ResolutionGraph(List.of(), List.of(), List.of()),
                new VersionSelectionResult(List.of(), List.of()),
                List.of());

        assertTrue(first.aliasFingerprint().orElseThrow().startsWith("sha256:"));
        assertEquals(first.aliasFingerprint(), second.aliasFingerprint());
    }

    private static ProjectConfig configWithManagedDependencyAndAlias() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                app = "1.0.0"

                [platforms]
                "com.example:platform" = "1.0.0"

                [dependencies]
                "com.example:app" = { managed = true }
                """);
    }

    private static ProjectConfig baseConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
    }

    private static ProjectConfig aliasFingerprintConfig(String firstGeneratedStep, String secondGeneratedStep) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "generated-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                openapi = "7.11.0"
                protoc = "4.28.3"
                grpc = "1.68.1"
                tomcat = "10.1.40"

                [dependencies.constraints]
                "org.apache.tomcat.embed:tomcat-embed-core" = { versionRef = "tomcat" }

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "openapi"

                [generated.tools.protobuf]
                protocVersionRef = "protoc"
                grpcVersionRef = "grpc"

                %s

                %s
                """.formatted(generatedStep(firstGeneratedStep), generatedStep(secondGeneratedStep)));
    }

    private static String generatedStep(String id) {
        return switch (id) {
            case "api" -> """
                    [generated.test.api]
                    kind = "openapi"
                    language = "java"
                    input = "src/test/openapi/api.yaml"
                    output = "target/generated/test-sources/openapi/api"
                    generator = "spring"
                    """;
            case "fixtures" -> """
                    [generated.test.fixtures]
                    kind = "protobuf"
                    language = "java"
                    inputs = ["src/test/proto/fixtures.proto"]
                    output = "target/generated/test-sources/protobuf"
                    """;
            default -> throw new IllegalArgumentException("Unknown generated step " + id);
        };
    }

    private static DependencyRequest classifiedRequest(
            String version, String classifier, DependencyScope scope) {
        PackageId fixture = new PackageId("com.example", "fixture");
        return new DependencyRequest(
                fixture,
                version,
                scope,
                RequestOrigin.DIRECT,
                Optional.of(ArtifactDescriptor.jar(
                        new Coordinate(fixture.groupId(), fixture.artifactId(), Optional.of(version)),
                        Optional.of(classifier))));
    }

    private static void assertVersionPathInvariant(
            ZoltLockfile lockfile, String classifier, String version) {
        LockPackage lockPackage = lockfile.packages().stream()
                .filter(pkg -> LockArtifactVariant.of(pkg).classifier().orElse("").equals(classifier))
                .findFirst()
                .orElseThrow();
        assertEquals(version, lockPackage.version());
        assertTrue(lockPackage.jar().orElseThrow().contains(
                "/%s/fixture-%s-%s.jar".formatted(version, version, classifier)));
        assertTrue(lockPackage.pom().orElseThrow().contains(
                "/%s/fixture-%s.pom".formatted(version, version)));
    }
}
