package sh.zolt.manifest.effective;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.BuiltInCommandCatalog;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredTask;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

final class EffectiveWorkspacePolicyComposerTest {
    private static final EffectiveManifestComposer COMPOSER = new EffectiveManifestComposer();
    private static final WorkspaceMemberPath INHERITED = path("modules/inherited");
    private static final WorkspaceMemberPath REPLACED = path("modules/replaced");
    private static final WorkspaceMemberPath TEST_OVERRIDE = path("modules/test-override");
    private static final BuiltInCommandCatalog BUILT_INS =
            BuiltInCommandCatalog.fromStrings(List.of("build"));

    @Test
    void appliesRootAndMemberToolchainRequestsAtWholeRequestBoundaries() {
        AuthoredToolchains rootToolchains = new AuthoredToolchains(
                Optional.of(new ZoltVersionPin("0.1.0")),
                Optional.of(new AuthoredJavaToolchain(
                        Optional.empty(),
                        Optional.of(JavaDistribution.GRAALVM_COMMUNITY),
                        Optional.of(Set.of(JavaFeature.NATIVE_IMAGE)),
                        Optional.of(ToolchainPolicy.REQUIRE_MANAGED))),
                Optional.of(new AuthoredJavaTestToolchain(
                        Optional.of(new JavaFeatureRelease(25)),
                        Optional.empty(),
                        Optional.empty())));
        AuthoredManifest root = root(rootToolchains, AuthoredBuildConfiguration.empty(), Optional.empty());
        AuthoredManifest inherited = member("inherited", AuthoredToolchains.empty(),
                AuthoredBuildConfiguration.empty(), Optional.empty());
        AuthoredManifest replaced = member(
                "replaced",
                new AuthoredToolchains(
                        Optional.empty(),
                        Optional.of(new AuthoredJavaToolchain(
                                Optional.empty(),
                                Optional.of(JavaDistribution.TEMURIN),
                                Optional.empty(),
                                Optional.empty())),
                        Optional.empty()),
                AuthoredBuildConfiguration.empty(),
                Optional.empty());
        AuthoredManifest testOverride = member(
                "test-override",
                new AuthoredToolchains(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new AuthoredJavaTestToolchain(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(ToolchainPolicy.ALLOW_SYSTEM)))),
                AuthoredBuildConfiguration.empty(),
                Optional.empty());

        EffectiveWorkspace effective = COMPOSER.composeWorkspace(
                root,
                Map.of(
                        INHERITED, inherited,
                        REPLACED, replaced,
                        TEST_OVERRIDE, testOverride));

        EffectiveToolchains inheritedTools = tools(effective, INHERITED);
        assertInherited(inheritedTools.zolt().orElseThrow(), "toolchain", "zolt", "version");
        EffectiveJavaRuntime.Requested inheritedMain = assertInstanceOf(
                EffectiveJavaRuntime.Requested.class,
                inheritedTools.mainJava().orElseThrow());
        assertEquals(JavaDistribution.GRAALVM_COMMUNITY, inheritedMain.distribution().value());
        assertInherited(inheritedMain.distribution(), "toolchain", "java", "distribution");
        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), inheritedMain.features().value());
        assertEquals(ToolchainPolicy.REQUIRE_MANAGED, inheritedMain.policy().value());
        EffectiveTestJavaRuntime.Requested inheritedTest = assertInstanceOf(
                EffectiveTestJavaRuntime.Requested.class,
                inheritedTools.testJava().orElseThrow());
        assertEquals(25, inheritedTest.version().value().value());
        assertInherited(inheritedTest.version(), "toolchain", "java", "test", "version");
        assertSame(inheritedMain.distribution(), inheritedTest.distribution());

        EffectiveJavaRuntime.Requested replacedMain = assertInstanceOf(
                EffectiveJavaRuntime.Requested.class,
                tools(effective, REPLACED).mainJava().orElseThrow());
        assertEquals(JavaDistribution.TEMURIN, replacedMain.distribution().value());
        assertEquals(ValueOrigin.AUTHORED, replacedMain.distribution().origin());
        assertEquals(Set.of(), replacedMain.features().value());
        assertEquals(ValueOrigin.BUILT_IN, replacedMain.features().origin());
        assertEquals(ToolchainPolicy.PREFER_MANAGED, replacedMain.policy().value());
        assertEquals(ValueOrigin.BUILT_IN, replacedMain.policy().origin());

        EffectiveToolchains overriddenTestTools = tools(effective, TEST_OVERRIDE);
        EffectiveJavaRuntime.Requested overriddenMain = assertInstanceOf(
                EffectiveJavaRuntime.Requested.class,
                overriddenTestTools.mainJava().orElseThrow());
        EffectiveTestJavaRuntime.Requested overriddenTest = assertInstanceOf(
                EffectiveTestJavaRuntime.Requested.class,
                overriddenTestTools.testJava().orElseThrow());
        assertSame(overriddenMain.version(), overriddenTest.version());
        assertSame(overriddenMain.distribution(), overriddenTest.distribution());
        assertEquals(ToolchainPolicy.ALLOW_SYSTEM, overriddenTest.policy().value());
        assertEquals(ValueOrigin.AUTHORED, overriddenTest.policy().origin());
    }

    @Test
    void usesCoverageMaximumAndMergesCollisionFreeCommandNamespaces() {
        AuthoredCommands rootCommands = new AuthoredCommands(
                Map.of(new LocalId("audit"), task("audit.sh")),
                Map.of(),
                BUILT_INS);
        AuthoredCommands memberCommands = new AuthoredCommands(
                Map.of(new LocalId("lint"), task("lint.sh")),
                Map.of(new LocalId("fast"), new AuthoredAlias(List.of("build", "--quick"))),
                BUILT_INS);
        AuthoredManifest root = root(
                AuthoredToolchains.empty(),
                coverage(90, 80, null, null),
                Optional.of(rootCommands));
        AuthoredManifest member = member(
                "member",
                AuthoredToolchains.empty(),
                coverage(95, null, null, 70),
                Optional.of(memberCommands));

        EffectiveSharedConfiguration shared = COMPOSER.composeWorkspace(
                        root, Map.of(path("modules/member"), member))
                .members()
                .get(path("modules/member"))
                .project()
                .shared();

        assertEquals(95.0, shared.coverage().line().orElseThrow().value().value());
        assertEquals(ValueOrigin.AUTHORED, shared.coverage().line().orElseThrow().origin());
        assertEquals(80.0, shared.coverage().branch().orElseThrow().value().value());
        assertInherited(shared.coverage().branch().orElseThrow(), "coverage", "branch");
        assertEquals(70.0, shared.coverage().method().orElseThrow().value().value());
        assertInherited(shared.commands().tasks().get(new LocalId("audit")), "tasks", "audit");
        assertEquals(ValueOrigin.AUTHORED,
                shared.commands().tasks().get(new LocalId("lint")).origin());
        assertEquals(ValueOrigin.AUTHORED,
                shared.commands().aliases().get(new LocalId("fast")).origin());
    }

    @Test
    void rejectsLowerCoverageAndAnyRootMemberCommandIdCollision() {
        AuthoredCommands rootCommands = new AuthoredCommands(
                Map.of(new LocalId("audit"), task("audit.sh")),
                Map.of(),
                BUILT_INS);
        AuthoredManifest root = root(
                AuthoredToolchains.empty(),
                coverage(90, null, null, null),
                Optional.of(rootCommands));
        AuthoredManifest lower = member(
                "lower",
                AuthoredToolchains.empty(),
                coverage(89, null, null, null),
                Optional.empty());
        assertMessage(
                () -> COMPOSER.composeWorkspace(root, Map.of(path("modules/lower"), lower)),
                "cannot lower workspace minimum");

        AuthoredCommands collision = new AuthoredCommands(
                Map.of(),
                Map.of(new LocalId("audit"), new AuthoredAlias(List.of("build"))),
                BUILT_INS);
        AuthoredManifest colliding = member(
                "colliding",
                AuthoredToolchains.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.of(collision));
        assertMessage(
                () -> COMPOSER.composeWorkspace(
                        root, Map.of(path("modules/colliding"), colliding)),
                "cannot be redeclared by a member task or alias");
    }

    private static AuthoredManifest root(
            AuthoredToolchains toolchains,
            AuthoredBuildConfiguration build,
            Optional<AuthoredCommands> commands) {
        WorkspaceManifestFixture fixture = new WorkspaceManifestFixture()
                .virtualRoot(workspace())
                .toolchains(toolchains)
                .build(build);
        commands.ifPresent(fixture::commands);
        return fixture.create();
    }

    private static AuthoredManifest member(
            String name,
            AuthoredToolchains toolchains,
            AuthoredBuildConfiguration build,
            Optional<AuthoredCommands> commands) {
        WorkspaceManifestFixture fixture = new WorkspaceManifestFixture()
                .identity(WorkspaceManifestFixture.sparseIdentity(name))
                .toolchains(toolchains)
                .build(build);
        commands.ifPresent(fixture::commands);
        return fixture.create();
    }

    private static AuthoredWorkspace workspace() {
        return new AuthoredWorkspace(
                new LocalId("platform"),
                new AuthoredWorkspaceMembers(
                        List.of(new WorkspaceMemberPattern("modules/*")),
                        List.of(),
                        Optional.empty()),
                Optional.of(new AuthoredWorkspaceProjectDefaults(
                        Optional.of(new ProjectGroup("com.example")),
                        Optional.of(new ProjectVersion("1.0.0")),
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.empty())));
    }

    private static AuthoredBuildConfiguration coverage(
            Integer line,
            Integer branch,
            Integer instruction,
            Integer method) {
        return new AuthoredBuildConfiguration(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredCoverage(
                        percentage(line),
                        percentage(branch),
                        percentage(instruction),
                        percentage(method))));
    }

    private static Optional<CoveragePercentage> percentage(Integer value) {
        return Optional.ofNullable(value).map(CoveragePercentage::new);
    }

    private static AuthoredTask task(String executable) {
        return new AuthoredTask(Optional.empty(), List.of(executable), Optional.empty(), Map.of());
    }

    private static EffectiveToolchains tools(
            EffectiveWorkspace workspace,
            WorkspaceMemberPath path) {
        return workspace.members().get(path).project().shared().toolchains();
    }

    private static WorkspaceMemberPath path(String value) {
        return new WorkspaceMemberPath(value);
    }

    private static void assertInherited(EffectiveValue<?> value, String... fieldPath) {
        assertEquals(ValueOrigin.INHERITED, value.origin());
        assertEquals(
                new ManifestSource("zolt.toml", List.of(fieldPath)),
                value.source().orElseThrow());
    }

    private static void assertMessage(
            org.junit.jupiter.api.function.Executable action,
            String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
