package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.toml.ZoltTomlParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkspaceCanonicalBuildPolicyTest {
    @Test
    void springBootNativeMembersKeepTheCanonicalPipelineForAotRepair() {
        WorkspaceMember member = new WorkspaceMember(
                "apps/api",
                Path.of("apps/api"),
                new ZoltTomlParser().parse("""
                        [project]
                        name = "api"
                        version = "0.1.0"
                        group = "com.acme"
                        java = "21"

                        [framework.springBoot.native]
                        enabled = true
                        """));

        assertTrue(WorkspaceCanonicalBuildPolicy.hasFrameworkOutputs(member));
    }

    @Test
    void plainMembersCanUseCleanMemberFinalization() {
        WorkspaceMember member = new WorkspaceMember(
                "apps/api",
                Path.of("apps/api"),
                new ZoltTomlParser().parse("""
                        [project]
                        name = "api"
                        version = "0.1.0"
                        group = "com.acme"
                        java = "21"
                        """));

        assertFalse(WorkspaceCanonicalBuildPolicy.hasGeneratedInputs(
                member,
                emptyClasspaths()));
        assertFalse(WorkspaceCanonicalBuildPolicy.hasFrameworkOutputs(member));
    }

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty);
    }
}
