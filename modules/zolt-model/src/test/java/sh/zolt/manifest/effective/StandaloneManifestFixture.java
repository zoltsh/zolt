package sh.zolt.manifest.effective;

import java.util.Optional;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredPublishing;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/** Mutable test builder for focused standalone composition cases. */
final class StandaloneManifestFixture {
    private Optional<AuthoredWorkspace> workspace = Optional.empty();
    private AuthoredProjectIdentity identity = new AuthoredProjectIdentity(
            new ProjectName("app"),
            Optional.of(new ProjectVersion("1.0.0")),
            Optional.of(new ProjectGroup("com.example")),
            Optional.of(new JavaFeatureRelease(21)),
            Optional.empty());
    private AuthoredToolchains toolchains = AuthoredToolchains.empty();
    private Optional<AuthoredVersionAliases> versions = Optional.empty();
    private Optional<AuthoredDependencyRepositories> repositories = Optional.empty();
    private Optional<AuthoredCredentials> credentials = Optional.empty();
    private Optional<AuthoredPlatforms> platforms = Optional.empty();
    private Optional<AuthoredDependencies> dependencies = Optional.empty();
    private Optional<AuthoredDependencyConstraints> constraints = Optional.empty();
    private Optional<AuthoredDependencyPolicy> policy = Optional.empty();
    private AuthoredBuildConfiguration build = AuthoredBuildConfiguration.empty();
    private Optional<AuthoredGeneratedSources> generated = Optional.empty();
    private AuthoredPackaging packaging = AuthoredPackaging.empty();
    private Optional<AuthoredPublishing> publishing = Optional.empty();
    private Optional<AuthoredCommands> commands = Optional.empty();

    StandaloneManifestFixture workspace(AuthoredWorkspace value) {
        workspace = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture identity(AuthoredProjectIdentity value) {
        identity = value;
        return this;
    }

    StandaloneManifestFixture toolchains(AuthoredToolchains value) {
        toolchains = value;
        return this;
    }

    StandaloneManifestFixture versions(AuthoredVersionAliases value) {
        versions = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture repositories(AuthoredDependencyRepositories value) {
        repositories = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture credentials(AuthoredCredentials value) {
        credentials = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture platforms(AuthoredPlatforms value) {
        platforms = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture dependencies(AuthoredDependencies value) {
        dependencies = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture constraints(AuthoredDependencyConstraints value) {
        constraints = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture build(AuthoredBuildConfiguration value) {
        build = value;
        return this;
    }

    StandaloneManifestFixture generated(AuthoredGeneratedSources value) {
        generated = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture packaging(AuthoredPackaging value) {
        packaging = value;
        return this;
    }

    StandaloneManifestFixture publishing(AuthoredPublishing value) {
        publishing = Optional.of(value);
        return this;
    }

    StandaloneManifestFixture commands(AuthoredCommands value) {
        commands = Optional.of(value);
        return this;
    }

    AuthoredManifest create() {
        return new AuthoredManifest(
                workspace,
                Optional.of(new AuthoredProject(identity, AuthoredProjectMetadata.empty())),
                toolchains,
                versions,
                repositories,
                credentials,
                platforms,
                dependencies,
                constraints,
                policy,
                build,
                generated,
                packaging,
                publishing,
                commands);
    }
}
