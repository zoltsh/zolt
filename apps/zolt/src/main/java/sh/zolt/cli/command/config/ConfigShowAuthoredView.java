package sh.zolt.cli.command.config;

import static sh.zolt.cli.command.config.ConfigShowValues.credential;
import static sh.zolt.cli.command.config.ConfigShowValues.describe;
import static sh.zolt.cli.command.config.ConfigShowValues.field;
import static sh.zolt.cli.command.config.ConfigShowValues.join;
import static sh.zolt.cli.command.config.ConfigShowValues.license;
import static sh.zolt.cli.command.config.ConfigShowValues.percentage;
import static sh.zolt.cli.command.config.ConfigShowValues.platform;
import static sh.zolt.cli.command.config.ConfigShowValues.repository;
import static sh.zolt.cli.command.config.ConfigShowValues.section;

import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;
import java.util.Optional;

/**
 * Renders the authored domains of one manifest: exactly what it declares, with nothing expanded and
 * nothing inherited (design §20.2).
 *
 * <p>The effective workspace report reuses these renderers because a virtual workspace root has no
 * project to compose against — every shared value it reports is authored by that one manifest.
 */
final class ConfigShowAuthoredView {
    private ConfigShowAuthoredView() {
    }

    static void workspace(StringBuilder out, AuthoredWorkspace workspace) {
        section(out, "Workspace");
        field(out, "name", workspace.name().value());
        field(out, "include", join(workspace.members().include().stream()
                .map(pattern -> pattern.value())
                .toList()));
        if (!workspace.members().exclude().isEmpty()) {
            field(out, "exclude", join(workspace.members().exclude().stream()
                    .map(pattern -> pattern.value())
                    .toList()));
        }
        field(out, "default", workspace.members().defaultMembers()
                .map(paths -> join(paths.stream().map(WorkspaceMemberPath::value).toList()))
                .orElse("implicit-all"));
        workspace.projectDefaults().ifPresent(defaults -> projectDefaults(out, defaults));
    }

    static void projectDefaults(StringBuilder out, AuthoredWorkspaceProjectDefaults defaults) {
        section(out, "Workspace project defaults");
        defaults.group().ifPresent(value -> field(out, "group", value.value()));
        defaults.version().ifPresent(value -> field(out, "version", value.value()));
        defaults.javaRelease().ifPresent(
                value -> field(out, "java", Integer.toString(value.value())));
        defaults.license().ifPresent(value -> field(out, "license", license(value)));
    }

    static void identity(StringBuilder out, AuthoredProjectIdentity identity) {
        field(out, "name", identity.name().value());
        identity.version().ifPresent(value -> field(out, "version", value.value()));
        identity.group().ifPresent(value -> field(out, "group", value.value()));
        identity.javaRelease().ifPresent(value -> field(out, "java", Integer.toString(value.value())));
        identity.license().ifPresent(value -> field(out, "license", license(value)));
    }

    static void toolchains(StringBuilder out, AuthoredToolchains toolchains) {
        if (toolchains.zolt().isEmpty() && toolchains.mainJava().isEmpty()
                && toolchains.testJava().isEmpty()) {
            return;
        }
        section(out, "Toolchains");
        toolchains.zolt().map(ZoltVersionPin::value).ifPresent(value -> field(out, "zolt", value));
        toolchains.mainJava().ifPresent(java -> javaToolchain(out, "java", java));
        toolchains.testJava().ifPresent(java -> testJavaToolchain(out, java));
    }

    static void versions(StringBuilder out, AuthoredVersionAliases versions) {
        if (versions.entries().isEmpty()) {
            return;
        }
        section(out, "Versions");
        versions.entries().forEach((id, value) -> field(out, id.value(), value.value()));
    }

    static void repositories(StringBuilder out, AuthoredDependencyRepositories repositories) {
        section(out, "Repositories");
        field(out, "central", repositories.centralRepository()
                .map(repository -> repository.url().value())
                .orElse("disabled"));
        repositories.named().forEach((id, repository) -> field(out, id.value(), repository(repository)));
    }

    static void credentials(StringBuilder out, AuthoredCredentials credentials) {
        if (credentials.entries().isEmpty()) {
            return;
        }
        section(out, "Credentials");
        credentials.entries().forEach((id, credential) -> field(out, id.value(), credential(credential)));
    }

    static void platforms(StringBuilder out, AuthoredPlatforms platforms) {
        if (platforms.entries().isEmpty()) {
            return;
        }
        section(out, "Platforms");
        platforms.entries().forEach(
                (coordinate, selector) -> field(out, coordinate.value(), platform(selector)));
    }

    static void coverage(StringBuilder out, AuthoredCoverage coverage) {
        section(out, "Coverage");
        coverage.line().ifPresent(value -> field(out, "line", percentage(value)));
        coverage.branch().ifPresent(value -> field(out, "branch", percentage(value)));
        coverage.instruction().ifPresent(value -> field(out, "instruction", percentage(value)));
        coverage.method().ifPresent(value -> field(out, "method", percentage(value)));
    }

    private static void javaToolchain(StringBuilder out, String label, AuthoredJavaToolchain java) {
        field(out, label, describe(
                java.version().map(JavaFeatureRelease::value).map(release -> Integer.toString(release)),
                java.distribution().map(JavaDistribution::id),
                java.features().map(ConfigShowValues::features),
                java.policy().map(ToolchainPolicy::id)));
    }

    private static void testJavaToolchain(StringBuilder out, AuthoredJavaTestToolchain java) {
        field(out, "java.test", describe(
                java.version().map(JavaFeatureRelease::value).map(release -> Integer.toString(release)),
                java.distribution().map(JavaDistribution::id),
                Optional.empty(),
                java.policy().map(ToolchainPolicy::id)));
    }
}
