package sh.zolt.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredGroovyToolchain;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredKotlinToolchain;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceMembers;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.GroovyToolchainVersion;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;
import sh.zolt.project.toolchain.KotlinToolchainVersion;

final class EffectiveProjectConfigAdapterGroovyTest {
    private static final EffectiveManifestComposer COMPOSER = new EffectiveManifestComposer();
    private static final EffectiveProjectConfigAdapter ADAPTER =
            new EffectiveProjectConfigAdapter();
    private static final WorkspaceMemberPath MEMBER = new WorkspaceMemberPath("modules/app");

    @Test
    void carriesTheStandaloneAuthoredGroovyVersion() {
        EffectiveManifest effective = COMPOSER.composeStandalone(
                projectManifest("app", groovyToolchains("4.0.22")));

        assertEquals("4.0.22", adapt(effective).compilerSettings().groovyVersion());
    }

    @Test
    void carriesTheStandaloneAuthoredKotlinVersion() {
        EffectiveManifest effective = COMPOSER.composeStandalone(
                projectManifest("app", kotlinToolchains("2.2.0")));

        assertEquals("2.2.0", adapt(effective).compilerSettings().kotlinVersion());
        assertTrue(adapt(effective).compilerSettings().kotlinPlugins().isEmpty());
    }

    @Test
    void carriesCompilerPluginSelectionThroughWorkspaceInheritance() {
        AuthoredManifest root = workspaceRoot(kotlinToolchains(
                "2.2.0",
                Set.of(
                        KotlinCompilerPlugin.SERIALIZATION,
                        KotlinCompilerPlugin.SPRING)));
        AuthoredManifest member = projectManifest("app", AuthoredToolchains.empty());

        ProjectConfig config = adapt(COMPOSER.composeWorkspaceMember(root, MEMBER, member));

        assertEquals(
                Set.of(
                        KotlinCompilerPlugin.SERIALIZATION,
                        KotlinCompilerPlugin.SPRING),
                config.compilerSettings().kotlinPlugins());
    }

    @Test
    void preservesLegacyCompilerDefaultsWhenLanguageToolchainsAreAbsent() {
        EffectiveManifest effective = COMPOSER.composeStandalone(
                projectManifest("app", AuthoredToolchains.empty()));

        assertEquals(CompilerSettings.defaults(), adapt(effective).compilerSettings());
    }

    @Test
    void carriesTheGroovyVersionInheritedFromAWorkspaceRoot() {
        AuthoredManifest root = workspaceRoot(groovyToolchains("4.0.22"));
        AuthoredManifest member = projectManifest("app", AuthoredToolchains.empty());

        EffectiveManifest effective = COMPOSER.composeWorkspaceMember(root, MEMBER, member);

        assertEquals("4.0.22", adapt(effective).compilerSettings().groovyVersion());
    }

    @Test
    void carriesTheKotlinVersionInheritedFromAWorkspaceRoot() {
        AuthoredManifest root = workspaceRoot(kotlinToolchains("2.2.0"));
        AuthoredManifest member = projectManifest("app", AuthoredToolchains.empty());

        EffectiveManifest effective = COMPOSER.composeWorkspaceMember(root, MEMBER, member);

        assertEquals("2.2.0", adapt(effective).compilerSettings().kotlinVersion());
    }

    @Test
    void carriesAMemberGroovyTableInsteadOfTheWorkspaceRootTable() {
        AuthoredManifest root = workspaceRoot(groovyToolchains("4.0.22"));
        AuthoredManifest member = projectManifest("app", groovyToolchains("4.0.23"));

        EffectiveManifest effective = COMPOSER.composeWorkspaceMember(root, MEMBER, member);

        assertEquals("4.0.23", adapt(effective).compilerSettings().groovyVersion());
    }

    @Test
    void carriesAMemberKotlinTableInsteadOfTheWorkspaceRootTable() {
        AuthoredManifest root = workspaceRoot(kotlinToolchains("2.2.0"));
        AuthoredManifest member = projectManifest("app", kotlinToolchains("2.2.10"));

        EffectiveManifest effective = COMPOSER.composeWorkspaceMember(root, MEMBER, member);

        assertEquals("2.2.10", adapt(effective).compilerSettings().kotlinVersion());
    }

    @Test
    void leavesCompilerVersionsBlankForABomWithoutJavaEvenWhenTheRootCarriesThem() {
        AuthoredManifest root = workspaceRoot(languageToolchains("4.0.22", "2.2.0"));
        AuthoredManifest bom = manifest(
                Optional.empty(),
                Optional.of(project("catalog", true)),
                AuthoredToolchains.empty(),
                bomPackaging());

        EffectiveManifest effective = COMPOSER.composeWorkspaceMember(root, MEMBER, bom);
        ProjectConfig config = adapt(effective);

        assertTrue(effective.project().shared().toolchains().mainJava().isEmpty());
        assertTrue(effective.project().shared().toolchains().testJava().isEmpty());
        assertTrue(effective.project().shared().toolchains().groovy().isEmpty());
        assertTrue(effective.project().shared().toolchains().kotlin().isEmpty());
        assertEquals("", config.compilerSettings().groovyVersion());
        assertEquals("", config.compilerSettings().kotlinVersion());
        assertTrue(config.compilerSettings().kotlinPlugins().isEmpty());
    }

    private static ProjectConfig adapt(EffectiveManifest manifest) {
        return ADAPTER.adapt(manifest);
    }

    private static AuthoredManifest projectManifest(
            String name,
            AuthoredToolchains toolchains) {
        return manifest(
                Optional.empty(),
                Optional.of(project(name, false)),
                toolchains,
                AuthoredPackaging.empty());
    }

    private static AuthoredManifest workspaceRoot(AuthoredToolchains toolchains) {
        AuthoredWorkspace workspace = new AuthoredWorkspace(
                new LocalId("root"),
                new AuthoredWorkspaceMembers(
                        List.of(new WorkspaceMemberPattern("modules/*")),
                        List.of(),
                        Optional.empty()),
                Optional.empty());
        return manifest(
                Optional.of(workspace),
                Optional.empty(),
                toolchains,
                AuthoredPackaging.empty());
    }

    private static AuthoredProject project(String name, boolean bom) {
        return new AuthoredProject(
                new AuthoredProjectIdentity(
                        new ProjectName(name),
                        Optional.of(new ProjectVersion("1.0.0")),
                        Optional.of(new ProjectGroup("com.example")),
                        bom
                                ? Optional.empty()
                                : Optional.of(new JavaFeatureRelease(21)),
                        Optional.empty()),
                AuthoredProjectMetadata.empty());
    }

    private static AuthoredToolchains groovyToolchains(String version) {
        return new AuthoredToolchains(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredGroovyToolchain(
                        new GroovyToolchainVersion(version))));
    }

    private static AuthoredToolchains kotlinToolchains(String version) {
        return kotlinToolchains(version, Set.of());
    }

    private static AuthoredToolchains kotlinToolchains(
            String version,
            Set<KotlinCompilerPlugin> plugins) {
        return new AuthoredToolchains(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredKotlinToolchain(
                        new KotlinToolchainVersion(version), plugins)));
    }

    private static AuthoredToolchains languageToolchains(
            String groovyVersion,
            String kotlinVersion) {
        return new AuthoredToolchains(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredGroovyToolchain(
                        new GroovyToolchainVersion(groovyVersion))),
                Optional.of(new AuthoredKotlinToolchain(
                        new KotlinToolchainVersion(kotlinVersion))));
    }

    private static AuthoredPackaging bomPackaging() {
        return new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredBom(
                        Optional.empty(), Optional.of(Map.of()), Optional.empty())));
    }

    private static AuthoredManifest manifest(
            Optional<AuthoredWorkspace> workspace,
            Optional<AuthoredProject> project,
            AuthoredToolchains toolchains,
            AuthoredPackaging packaging) {
        return new AuthoredManifest(
                workspace,
                project,
                toolchains,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.empty(),
                packaging,
                Optional.empty(),
                Optional.empty());
    }
}
