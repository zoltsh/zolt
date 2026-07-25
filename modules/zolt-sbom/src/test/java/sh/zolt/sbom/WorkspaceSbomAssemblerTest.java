package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.ZoltLockfile;

final class WorkspaceSbomAssemblerTest extends SbomTestSupport {
    private final WorkspaceSbomAssembler assembler = new WorkspaceSbomAssembler();
    private final CycloneDxSbomWriter writer = new CycloneDxSbomWriter();

    @Test
    void aggregatesWorkspaceIntoOneBomWithMemberAndExternalEdges() {
        List<SbomWorkspaceMember> members = List.of(
                member("apps/app", "com.example", "app", "1.0.0"),
                member("modules/lib-a", "com.example", "lib-a", "1.0.0"));
        ZoltLockfile lockfile = lockfile(
                Optional.of("sha256:ws-fingerprint"),
                workspacePackage("com.example", "lib-a", "1.0.0", "modules/lib-a", List.of("apps/app")),
                externalWithMembers("org.ext", "ext-lib", "2.0.0", DependencyScope.COMPILE, SHA_A,
                        List.of(), List.of("modules/lib-a")));

        SbomModel model = assembler.assemble(
                "demo-ws", members, lockfile, SbomScopeSelection.requiredOnly(),
                Optional.empty(), TOOL_VERSION, LicenseIndex.empty());

        assertEquals("""
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.5",
                  "serialNumber": "urn:uuid:c889683b-abc6-55ed-b65f-271a2ffb6856",
                  "version": 1,
                  "metadata": {
                    "tools": [
                      {
                        "name": "zolt",
                        "version": "0.1.0-TEST"
                      }
                    ],
                    "component": {
                      "type": "application",
                      "bom-ref": "workspace:demo-ws",
                      "name": "demo-ws"
                    }
                  },
                  "components": [
                    {
                      "type": "library",
                      "bom-ref": "pkg:maven/com.example/app@1.0.0?type=jar",
                      "group": "com.example",
                      "name": "app",
                      "version": "1.0.0",
                      "purl": "pkg:maven/com.example/app@1.0.0?type=jar",
                      "scope": "required"
                    },
                    {
                      "type": "library",
                      "bom-ref": "pkg:maven/com.example/lib-a@1.0.0?type=jar",
                      "group": "com.example",
                      "name": "lib-a",
                      "version": "1.0.0",
                      "purl": "pkg:maven/com.example/lib-a@1.0.0?type=jar",
                      "scope": "required"
                    },
                    {
                      "type": "library",
                      "bom-ref": "pkg:maven/org.ext/ext-lib@2.0.0?type=jar",
                      "group": "org.ext",
                      "name": "ext-lib",
                      "version": "2.0.0",
                      "purl": "pkg:maven/org.ext/ext-lib@2.0.0?type=jar",
                      "scope": "required",
                      "hashes": [
                        {
                          "alg": "SHA-256",
                          "content": "1111111111111111111111111111111111111111111111111111111111111111"
                        }
                      ]
                    }
                  ],
                  "dependencies": [
                    {
                      "ref": "pkg:maven/com.example/app@1.0.0?type=jar",
                      "dependsOn": ["pkg:maven/com.example/lib-a@1.0.0?type=jar"]
                    },
                    {
                      "ref": "pkg:maven/com.example/lib-a@1.0.0?type=jar",
                      "dependsOn": ["pkg:maven/org.ext/ext-lib@2.0.0?type=jar"]
                    },
                    {
                      "ref": "pkg:maven/org.ext/ext-lib@2.0.0?type=jar",
                      "dependsOn": []
                    },
                    {
                      "ref": "workspace:demo-ws",
                      "dependsOn": ["pkg:maven/com.example/app@1.0.0?type=jar", "pkg:maven/com.example/lib-a@1.0.0?type=jar"]
                    }
                  ]
                }
                """, writer.write(model));
    }

    @Test
    void defaultScopeOmitsTestAndProcessorMemberRelationships() {
        List<SbomWorkspaceMember> members = List.of(
                member("apps/app", "com.example", "app", "1.0.0"),
                member("modules/test-support", "com.example", "test-support", "1.0.0"),
                member("modules/code-generator", "com.example", "code-generator", "1.0.0"));
        ZoltLockfile lockfile = lockfile(
                Optional.empty(),
                workspacePackage(
                        "com.example",
                        "test-support",
                        "1.0.0",
                        "modules/test-support",
                        "apps/app",
                        DependencyScope.TEST),
                workspacePackage(
                        "com.example",
                        "code-generator",
                        "1.0.0",
                        "modules/code-generator",
                        "apps/app",
                        DependencyScope.PROCESSOR));

        SbomModel required = assembler.assemble(
                "demo-ws",
                members,
                lockfile,
                SbomScopeSelection.requiredOnly(),
                Optional.empty(),
                TOOL_VERSION,
                LicenseIndex.empty());
        SbomModel withOptionalScopes = assembler.assemble(
                "demo-ws",
                members,
                lockfile,
                new SbomScopeSelection(false, false, true, true),
                Optional.empty(),
                TOOL_VERSION,
                LicenseIndex.empty());
        String appRef = "pkg:maven/com.example/app@1.0.0?type=jar";

        assertEquals(List.of(), dependsOn(required, appRef));
        assertEquals(
                List.of(
                        "pkg:maven/com.example/code-generator@1.0.0?type=jar",
                        "pkg:maven/com.example/test-support@1.0.0?type=jar"),
                dependsOn(withOptionalScopes, appRef));
    }

    @Test
    void memberQualifiedGraphKeepsExcludedLeafUnreachableFromThatMember() {
        List<SbomWorkspaceMember> members = List.of(
                member("modules/core", "com.example", "core", "1.0.0"),
                member("modules/worker", "com.example", "worker", "1.0.0"));
        LockPackage leaf = externalWithMembers(
                "org.ext",
                "leaf",
                "1.0.0",
                DependencyScope.COMPILE,
                SHA_A,
                List.of(),
                List.of("modules/worker"));
        LockPackage root = externalWithMembers(
                "org.ext",
                "root",
                "1.0.0",
                DependencyScope.COMPILE,
                SHA_B,
                List.of("org.ext:leaf:1.0.0:jar:compile"),
                List.of("modules/core", "modules/worker"));
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:member-graphs"),
                List.of(),
                List.of(root, leaf),
                List.of(),
                List.of(),
                List.of(
                        new LockMemberGraph(
                                "modules/core",
                                root.packageId(),
                                root.version(),
                                LockArtifactVariant.defaultVariant(),
                                root.scope(),
                                List.of(),
                                List.of()),
                        new LockMemberGraph(
                                "modules/worker",
                                root.packageId(),
                                root.version(),
                                LockArtifactVariant.defaultVariant(),
                                root.scope(),
                                root.dependencies(),
                                List.of())));

        SbomModel model = assembler.assemble(
                "member-graphs",
                members,
                lockfile,
                SbomScopeSelection.requiredOnly(),
                Optional.empty(),
                TOOL_VERSION,
                LicenseIndex.empty());

        String rootPurl = "pkg:maven/org.ext/root@1.0.0?type=jar";
        String coreContext = rootPurl + "#zolt-context=modules%2Fcore";
        String workerContext = rootPurl + "#zolt-context=modules%2Fworker";
        String leafRef = "pkg:maven/org.ext/leaf@1.0.0?type=jar";
        assertEquals(
                List.of(coreContext),
                dependsOn(model, "pkg:maven/com.example/core@1.0.0?type=jar"));
        assertEquals(
                List.of(leafRef, workerContext),
                dependsOn(model, "pkg:maven/com.example/worker@1.0.0?type=jar"));
        assertEquals(List.of(), dependsOn(model, coreContext));
        assertEquals(List.of(leafRef), dependsOn(model, workerContext));
    }

    @Test
    void includesTypedArtifactsAlongsideThePlainJarWithExactHashAndGraph() {
        List<SbomWorkspaceMember> members =
                List.of(member("modules/core", "com.example", "core", "1.0.0"));
        LockPackage leaf = externalWithMembers(
                "org.ext",
                "leaf",
                "1.0.0",
                DependencyScope.COMPILE,
                SHA_C,
                List.of(),
                List.of("modules/core"));
        LockPackage plain = externalWithMembers(
                "org.ext",
                "native",
                "1.0.0",
                DependencyScope.COMPILE,
                SHA_B,
                List.of(),
                List.of("modules/core"));
        String base = "org/ext/native/1.0.0/native-1.0.0";
        LockPackage typed = new LockPackage(
                new PackageId("org.ext", "native"),
                "1.0.0",
                "maven-central",
                DependencyScope.COMPILE,
                false,
                Optional.empty(),
                Optional.of(base + ".pom"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(base + "-bundle.zip"),
                Optional.of("zip"),
                Optional.of(SHA_A),
                Optional.empty(),
                Optional.empty(),
                List.of("org.ext:leaf:1.0.0:jar:compile"),
                List.of("modules/core"),
                List.of(),
                List.of(),
                List.of());
        ZoltLockfile lockfile =
                lockfile(Optional.of("sha256:typed-workspace"), plain, typed, leaf);

        SbomModel model = assembler.assemble(
                "typed-workspace",
                members,
                lockfile,
                SbomScopeSelection.requiredOnly(),
                Optional.empty(),
                TOOL_VERSION,
                LicenseIndex.empty());

        String jarRef = "pkg:maven/org.ext/native@1.0.0?type=jar";
        String zipRef = "pkg:maven/org.ext/native@1.0.0?classifier=bundle&type=zip";
        assertTrue(model.components().stream().anyMatch(component ->
                component.bomRef().equals(jarRef)
                        && component.hashes().equals(List.of(new SbomHash("SHA-256", SHA_B)))));
        assertTrue(model.components().stream().anyMatch(component ->
                component.bomRef().equals(zipRef)
                        && component.hashes().equals(List.of(new SbomHash("SHA-256", SHA_A)))));
        assertEquals(
                List.of("pkg:maven/org.ext/leaf@1.0.0?type=jar"),
                dependsOn(model, zipRef));
        assertEquals(
                List.of(
                        "pkg:maven/org.ext/leaf@1.0.0?type=jar",
                        zipRef,
                        jarRef),
                dependsOn(model, "pkg:maven/com.example/core@1.0.0?type=jar"));
        assertEquals(writer.write(model), writer.write(assembler.assemble(
                "typed-workspace",
                members,
                lockfile,
                SbomScopeSelection.requiredOnly(),
                Optional.empty(),
                TOOL_VERSION,
                LicenseIndex.empty())));
    }

    private static LockPackage workspacePackage(
            String group,
            String name,
            String version,
            String workspace,
            String member,
            DependencyScope scope) {
        return new LockPackage(
                new PackageId(group, name),
                version,
                "workspace",
                scope,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(workspace),
                Optional.of("target/classes"),
                List.of(),
                List.of(member));
    }

    private static List<String> dependsOn(SbomModel model, String ref) {
        return model.dependencies().stream()
                .filter(dependency -> dependency.ref().equals(ref))
                .map(SbomDependency::dependsOn)
                .findFirst()
                .orElseThrow();
    }
}
