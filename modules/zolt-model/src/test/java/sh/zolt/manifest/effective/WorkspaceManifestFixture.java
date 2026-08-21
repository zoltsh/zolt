package sh.zolt.manifest.effective;

import java.util.Optional;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/** Mutable test builder for focused workspace effective-composition cases. */
final class WorkspaceManifestFixture {
    private Optional<AuthoredWorkspace> workspace = Optional.empty();
    private Optional<AuthoredProject> project = Optional.of(project(identity("app")));
    private AuthoredToolchains toolchains = AuthoredToolchains.empty();
    private Optional<AuthoredVersionAliases> versions = Optional.empty();
    private Optional<AuthoredDependencyRepositories> repositories = Optional.empty();
    private Optional<AuthoredCredentials> credentials = Optional.empty();
    private Optional<AuthoredPlatforms> platforms = Optional.empty();
    private Optional<AuthoredDependencies> dependencies = Optional.empty();
    private AuthoredBuildConfiguration build = AuthoredBuildConfiguration.empty();
    private AuthoredPackaging packaging = AuthoredPackaging.empty();
    private Optional<AuthoredCommands> commands = Optional.empty();

    WorkspaceManifestFixture workspace(AuthoredWorkspace value) {
        workspace = Optional.of(value);
        return this;
    }

    WorkspaceManifestFixture virtualRoot(AuthoredWorkspace value) {
        workspace = Optional.of(value);
        project = Optional.empty();
        return this;
    }

    WorkspaceManifestFixture identity(AuthoredProjectIdentity value) {
        project = Optional.of(project(value));
        return this;
    }

    WorkspaceManifestFixture toolchains(AuthoredToolchains value) {
        toolchains = value;
        return this;
    }

    WorkspaceManifestFixture versions(AuthoredVersionAliases value) {
        versions = Optional.of(value);
        return this;
    }

    WorkspaceManifestFixture repositories(AuthoredDependencyRepositories value) {
        repositories = Optional.of(value);
        return this;
    }

    WorkspaceManifestFixture credentials(AuthoredCredentials value) {
        credentials = Optional.of(value);
        return this;
    }

    WorkspaceManifestFixture platforms(AuthoredPlatforms value) {
        platforms = Optional.of(value);
        return this;
    }

    WorkspaceManifestFixture dependencies(AuthoredDependencies value) {
        dependencies = Optional.of(value);
        return this;
    }

    WorkspaceManifestFixture build(AuthoredBuildConfiguration value) {
        build = value;
        return this;
    }

    WorkspaceManifestFixture packaging(AuthoredPackaging value) {
        packaging = value;
        return this;
    }

    WorkspaceManifestFixture commands(AuthoredCommands value) {
        commands = Optional.of(value);
        return this;
    }

    AuthoredManifest create() {
        return new AuthoredManifest(
                workspace,
                project,
                toolchains,
                versions,
                repositories,
                credentials,
                platforms,
                dependencies,
                Optional.empty(),
                Optional.empty(),
                build,
                Optional.empty(),
                packaging,
                Optional.empty(),
                commands);
    }

    static AuthoredProjectIdentity identity(String name) {
        return new AuthoredProjectIdentity(
                new ProjectName(name),
                Optional.of(new ProjectVersion("1.0.0")),
                Optional.of(new ProjectGroup("com.example")),
                Optional.of(new JavaFeatureRelease(21)),
                Optional.empty());
    }

    static AuthoredProjectIdentity sparseIdentity(String name) {
        return new AuthoredProjectIdentity(
                new ProjectName(name),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredProject project(AuthoredProjectIdentity identity) {
        return new AuthoredProject(identity, AuthoredProjectMetadata.empty());
    }
}
