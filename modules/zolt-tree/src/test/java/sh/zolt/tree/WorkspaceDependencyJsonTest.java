package sh.zolt.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class WorkspaceDependencyJsonTest extends WorkspaceTreeTestSupport {
    private final WorkspaceDependencyJsonFormatter formatter = new WorkspaceDependencyJsonFormatter();

    @Test
    void emitsSchemaVersionTwoWorkspaceProjection() {
        String output = formatter.tree(WORKSPACE_NAME, MEMBERS, workspaceLockfile());

        assertEquals("""
                {
                  "schemaVersion": 2,
                  "command": "tree",
                  "mode": "workspace",
                  "lockVersion": 5,
                  "workspace": {
                    "name": "demo-workspace",
                    "members": ["apps/api", "modules/core"]
                  },
                  "packages": [
                    {
                      "id": "com.example:core",
                      "version": "0.1.0",
                      "coordinate": "com.example:core:0.1.0",
                      "scope": "compile",
                      "direct": true,
                      "members": ["apps/api"],
                      "dependencies": ["org.example:shared:1.0.0:jar:compile"]
                    },
                    {
                      "id": "org.example:agent",
                      "version": "0.9.0",
                      "coordinate": "org.example:agent:0.9.0:jar|runtime",
                      "variant": "jar|runtime",
                      "scope": "tool-coverage",
                      "direct": false,
                      "members": ["apps/api", "modules/core"],
                      "dependencies": []
                    },
                    {
                      "id": "org.example:bundle",
                      "version": "3.0.0",
                      "coordinate": "org.example:bundle:3.0.0:zip",
                      "variant": "zip",
                      "scope": "runtime",
                      "direct": true,
                      "members": ["apps/api"],
                      "dependencies": []
                    },
                    {
                      "id": "org.example:extra",
                      "version": "2.0.0",
                      "coordinate": "org.example:extra:2.0.0",
                      "scope": "compile",
                      "direct": false,
                      "members": ["apps/api"],
                      "dependencies": []
                    },
                    {
                      "id": "org.example:shared",
                      "version": "1.0.0",
                      "coordinate": "org.example:shared:1.0.0",
                      "scope": "compile",
                      "direct": true,
                      "members": ["apps/api", "modules/core"],
                      "dependencies": ["org.example:extra:2.0.0:jar:compile"]
                    },
                    {
                      "id": "org.example:shared",
                      "version": "1.0.0",
                      "coordinate": "org.example:shared:1.0.0",
                      "scope": "test",
                      "direct": true,
                      "members": ["modules/core"],
                      "dependencies": []
                    }
                  ],
                  "roots": ["com.example:core:0.1.0", "org.example:bundle:3.0.0:zip", "org.example:shared:1.0.0"]
                }
                """, output);
    }

    @Test
    void keepsOneCoordinateSeparatePerScope() {
        String output = formatter.tree(WORKSPACE_NAME, MEMBERS, workspaceLockfile());

        assertEquals(
                2,
                output.split("\"coordinate\": \"org.example:shared:1.0.0\",", -1).length - 1,
                output);
        assertTrue(output.contains("\"scope\": \"compile\",\n      \"direct\": true,\n"
                + "      \"members\": [\"apps/api\", \"modules/core\"]"), output);
        assertTrue(output.contains("\"scope\": \"test\",\n      \"direct\": true,\n"
                + "      \"members\": [\"modules/core\"]"), output);
    }

    @Test
    void dedupesRootsAcrossScopesAndMembers() {
        String output = formatter.tree(WORKSPACE_NAME, MEMBERS, workspaceLockfile());

        assertTrue(output.contains(
                "\"roots\": [\"com.example:core:0.1.0\", "
                        + "\"org.example:bundle:3.0.0:zip\", \"org.example:shared:1.0.0\"]"),
                output);
    }

    @Test
    void unionsMemberSensitiveChildrenOfASharedDependency() {
        // apps/api reaches `extra` through `shared`; modules/core does not. The workspace-level child
        // set is the union, so the edge is still listed exactly once.
        String output = formatter.tree(WORKSPACE_NAME, MEMBERS, workspaceLockfile());

        assertEquals(
                1,
                output.split("\"dependencies\": \\[\"org.example:extra:2.0.0:jar:compile\"\\]", -1).length - 1,
                output);
    }

    @Test
    void emitsTheSameBytesRegardlessOfMemberOrderAndAcrossRuns() {
        ZoltLockfile lockfile = workspaceLockfile();

        String first = formatter.tree(WORKSPACE_NAME, List.of("modules/core", "apps/api"), lockfile);
        String second = formatter.tree(WORKSPACE_NAME, List.of("apps/api", "modules/core"), lockfile);
        String third = new WorkspaceDependencyJsonFormatter()
                .tree(WORKSPACE_NAME, List.of("apps/api", "modules/core"), lockfile);

        assertEquals(first, second);
        assertEquals(first, third);
    }

    @Test
    void everyChildEdgeNamesAListedPackageOccurrence() {
        String output = formatter.tree(WORKSPACE_NAME, MEMBERS, workspaceLockfile());

        for (String edge : edges(output)) {
            assertTrue(
                    identities(workspaceLockfile()).contains(edge),
                    "dangling edge " + edge + " in\n" + output);
        }
    }

    @Test
    void projectsAnEmptyWorkspaceLockWithoutPackages() {
        String output = formatter.tree(
                "empty", List.of(), new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()));

        assertEquals("""
                {
                  "schemaVersion": 2,
                  "command": "tree",
                  "mode": "workspace",
                  "lockVersion": 5,
                  "workspace": {
                    "name": "empty",
                    "members": []
                  },
                  "packages": [],
                  "roots": []
                }
                """, output);
    }

    @Test
    void listsOnlyKnownLockfileScopes() {
        String output = formatter.tree(WORKSPACE_NAME, MEMBERS, workspaceLockfile());

        output.lines()
                .filter(line -> line.trim().startsWith("\"scope\":"))
                .map(line -> line.substring(line.indexOf(": \"") + 3, line.lastIndexOf('"')))
                .forEach(scope -> assertTrue(
                        Stream.of(DependencyScope.values())
                                .anyMatch(known -> known.lockfileName().equals(scope)),
                        "unknown scope " + scope));
    }

    private static List<String> edges(String output) {
        return output.lines()
                .filter(line -> line.contains("\"dependencies\": ["))
                .map(line -> line.substring(line.indexOf('[') + 1, line.lastIndexOf(']')))
                .flatMap(list -> Stream.of(list.split(", ")))
                .filter(token -> !token.isBlank())
                .map(token -> token.replace("\"", ""))
                .toList();
    }

    private static List<String> identities(ZoltLockfile lockfile) {
        return lockfile.packages().stream()
                .map(lockPackage -> LockDependencyEdge.of(lockPackage).encode())
                .toList();
    }
}
