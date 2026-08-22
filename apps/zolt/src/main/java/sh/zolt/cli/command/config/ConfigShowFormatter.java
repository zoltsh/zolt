package sh.zolt.cli.command.config;

import static sh.zolt.cli.command.config.ConfigShowValues.field;
import static sh.zolt.cli.command.config.ConfigShowValues.join;
import static sh.zolt.cli.command.config.ConfigShowValues.origin;
import static sh.zolt.cli.command.config.ConfigShowValues.section;

import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.manifest.effective.EffectiveCoverage;
import sh.zolt.manifest.effective.EffectiveDependencyRepositories;
import sh.zolt.manifest.effective.EffectiveJavaRuntime;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.manifest.effective.EffectiveProjectIdentity;
import sh.zolt.manifest.effective.EffectiveSharedConfiguration;
import sh.zolt.manifest.effective.EffectiveTestJavaRuntime;
import sh.zolt.manifest.effective.EffectiveToolchains;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.manifest.effective.WorkspaceContext;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.ToolchainPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Renders the two {@code zolt config show} views (design §20.2).
 *
 * <p>The authored view reports exactly what one manifest declares, with nothing expanded and nothing
 * inherited; {@link ConfigShowAuthoredView} owns those renderers. The effective view reports the
 * composed project and labels every value {@code authored}, {@code inherited}, or {@code built-in},
 * naming the manifest that supplied it. Neither view reads machine-local user-global configuration.
 */
final class ConfigShowFormatter {
    /** The authored values of one manifest, with no workspace inheritance materialized. */
    String manifest(String manifestPath, AuthoredManifest authored) {
        StringBuilder out = new StringBuilder();
        out.append("Manifest ").append(manifestPath).append('\n');
        authored.workspace().ifPresent(workspace -> ConfigShowAuthoredView.workspace(out, workspace));
        authored.project().ifPresent(project -> {
            section(out, "Project");
            ConfigShowAuthoredView.identity(out, project.identity());
            project.metadata().main().ifPresent(main -> field(out, "main", main.value()));
        });
        ConfigShowAuthoredView.toolchains(out, authored.toolchains());
        sharedAuthoredDomains(out, authored, authored.repositories());
        return out.toString();
    }

    /**
     * The effective view of a virtual workspace root, which has no project to compose. Every shared
     * value here is authored by this one manifest, so the report states that once instead of repeating
     * it on every line, and adds the membership the root actually selects.
     */
    String effectiveWorkspace(
            String manifestPath,
            AuthoredManifest authored,
            String selectionSource,
            List<WorkspaceMemberPath> members) {
        AuthoredWorkspace workspace = authored.workspace().orElseThrow();
        StringBuilder out = new StringBuilder();
        out.append("Effective workspace ").append(workspace.name().value()).append('\n');
        field(out, "manifest", manifestPath);
        field(out, "selection", selectionSource);
        field(out, "selected", join(members.stream().map(WorkspaceMemberPath::value).toList()));
        field(out, "shared values", "authored by this workspace root");
        workspace.projectDefaults().ifPresent(
                defaults -> ConfigShowAuthoredView.projectDefaults(out, defaults));
        ConfigShowAuthoredView.toolchains(out, authored.toolchains());
        sharedAuthoredDomains(
                out,
                authored,
                Optional.of(authored.repositories().orElseGet(AuthoredDependencyRepositories::defaults)));
        return out.toString();
    }

    /** The composed project with the origin of every value (design §20.2). */
    String effective(
            String manifestPath,
            EffectiveManifest effective,
            Optional<String> selectionSource) {
        StringBuilder out = new StringBuilder();
        EffectiveProjectIdentity identity = effective.project().identity();
        out.append("Effective project ").append(identity.name().value().value()).append('\n');
        field(out, "manifest", manifestPath);
        effective.workspace().ifPresent(context -> workspaceContext(out, context, selectionSource));

        section(out, "Identity");
        origin(out, "name", identity.name(), value -> value.value());
        origin(out, "version", identity.version(), value -> value.value());
        origin(out, "group", identity.group(), value -> value.value());
        identity.javaRelease().ifPresent(
                value -> origin(out, "java", value, release -> Integer.toString(release.value())));
        identity.license().ifPresent(value -> origin(out, "license", value, ConfigShowValues::license));

        EffectiveSharedConfiguration shared = effective.project().shared();
        effectiveToolchains(out, shared.toolchains());
        effectiveRepositories(out, shared.repositories());
        effectiveMap(out, "Versions", shared.versions(), VersionAliasValue::value);
        effectiveMap(out, "Credentials", shared.credentials(), ConfigShowValues::credential);
        effectivePlatforms(out, shared.platforms());
        effectiveCoverage(out, shared.coverage());
        return out.toString();
    }

    /**
     * The named maps and coverage floors both authored reports render identically. Repositories differ
     * only in whether an absent {@code [repositories]} table is reported at all, so the caller decides.
     */
    private static void sharedAuthoredDomains(
            StringBuilder out,
            AuthoredManifest authored,
            Optional<AuthoredDependencyRepositories> repositories) {
        authored.versions().ifPresent(versions -> ConfigShowAuthoredView.versions(out, versions));
        repositories.ifPresent(value -> ConfigShowAuthoredView.repositories(out, value));
        authored.credentials().ifPresent(
                credentials -> ConfigShowAuthoredView.credentials(out, credentials));
        authored.platforms().ifPresent(platforms -> ConfigShowAuthoredView.platforms(out, platforms));
        authored.build().coverage().ifPresent(
                coverage -> ConfigShowAuthoredView.coverage(out, coverage));
    }

    private static void workspaceContext(
            StringBuilder out, WorkspaceContext context, Optional<String> selectionSource) {
        field(out, "workspace", context.name().value().value());
        field(out, "member", context.memberPath().value());
        selectionSource.ifPresent(source -> field(out, "selection", source));
    }

    private static void effectiveToolchains(StringBuilder out, EffectiveToolchains toolchains) {
        section(out, "Toolchains");
        toolchains.zolt().ifPresent(value -> origin(out, "zolt", value, ZoltVersionPin::value));
        toolchains.mainJava().ifPresent(runtime -> {
            switch (runtime) {
                case EffectiveJavaRuntime.System system -> origin(
                        out,
                        "java",
                        system.requiredRelease(),
                        release -> "system (release " + release.value() + ")");
                case EffectiveJavaRuntime.Requested requested -> {
                    origin(out, "java.version", requested.version(),
                            release -> Integer.toString(release.value()));
                    origin(out, "java.distribution", requested.distribution(), JavaDistribution::id);
                    origin(out, "java.features", requested.features(), ConfigShowValues::features);
                    origin(out, "java.policy", requested.policy(), ToolchainPolicy::id);
                }
            }
        });
        toolchains.testJava().ifPresent(runtime -> {
            switch (runtime) {
                case EffectiveTestJavaRuntime.SameAsMain ignored -> field(out, "java.test", "same as main");
                case EffectiveTestJavaRuntime.Requested requested -> {
                    origin(out, "java.test.version", requested.version(),
                            release -> Integer.toString(release.value()));
                    origin(out, "java.test.distribution", requested.distribution(), JavaDistribution::id);
                    origin(out, "java.test.policy", requested.policy(), ToolchainPolicy::id);
                }
            }
        });
    }

    private static void effectiveRepositories(
            StringBuilder out, EffectiveDependencyRepositories repositories) {
        section(out, "Repositories");
        origin(out, "central", repositories.central(), central -> central.repository()
                .map(repository -> repository.url().value())
                .orElse("disabled"));
        repositories.named().forEach(
                (id, repository) -> origin(out, id.value(), repository, ConfigShowValues::repository));
        origin(out, "order", repositories.lookupOrder(),
                order -> join(order.stream().map(LocalId::value).toList()));
    }

    private static void effectivePlatforms(
            StringBuilder out, Map<DependencyCoordinate, EffectiveValue<PlatformSelector>> platforms) {
        if (platforms.isEmpty()) {
            return;
        }
        section(out, "Platforms");
        platforms.forEach((coordinate, selector) ->
                origin(out, coordinate.value(), selector, ConfigShowValues::platform));
    }

    private static void effectiveCoverage(StringBuilder out, EffectiveCoverage coverage) {
        if (coverage.line().isEmpty() && coverage.branch().isEmpty()
                && coverage.instruction().isEmpty() && coverage.method().isEmpty()) {
            return;
        }
        section(out, "Coverage");
        coverage.line().ifPresent(value -> origin(out, "line", value, ConfigShowValues::percentage));
        coverage.branch().ifPresent(value -> origin(out, "branch", value, ConfigShowValues::percentage));
        coverage.instruction().ifPresent(
                value -> origin(out, "instruction", value, ConfigShowValues::percentage));
        coverage.method().ifPresent(value -> origin(out, "method", value, ConfigShowValues::percentage));
    }

    private static <T> void effectiveMap(
            StringBuilder out,
            String heading,
            Map<LocalId, EffectiveValue<T>> entries,
            Function<T, String> render) {
        if (entries.isEmpty()) {
            return;
        }
        section(out, heading);
        entries.forEach((id, value) -> origin(out, id.value(), value, render));
    }
}
