package sh.zolt.cli.command.config;

import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
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
import sh.zolt.manifest.effective.ValueOrigin;
import sh.zolt.manifest.effective.WorkspaceContext;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Renders the two {@code zolt config show} views (design §20.2).
 *
 * <p>The authored view reports exactly what one manifest declares, with nothing expanded and nothing
 * inherited. The effective view reports the composed project and labels every value {@code authored},
 * {@code inherited}, or {@code built-in}, naming the manifest that supplied it. Neither view reads
 * machine-local user-global configuration.
 */
final class ConfigShowFormatter {
    private static final String INDENT = "  ";

    /** The authored values of one manifest, with no workspace inheritance materialized. */
    String manifest(String manifestPath, AuthoredManifest authored) {
        StringBuilder out = new StringBuilder();
        out.append("Manifest ").append(manifestPath).append('\n');
        authored.workspace().ifPresent(workspace -> workspaceSection(out, workspace));
        authored.project().ifPresent(project -> {
            section(out, "Project");
            identity(out, project.identity());
            project.metadata().main().ifPresent(main -> field(out, "main", main.value()));
        });
        toolchains(out, authored.toolchains());
        authored.versions().ifPresent(versions -> versions(out, versions));
        authored.repositories().ifPresent(repositories -> repositories(out, repositories));
        authored.credentials().ifPresent(credentials -> credentials(out, credentials));
        authored.platforms().ifPresent(platforms -> platforms(out, platforms));
        authored.build().coverage().ifPresent(coverage -> coverage(out, coverage));
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
        workspace.projectDefaults().ifPresent(defaults -> {
            section(out, "Workspace project defaults");
            defaults.group().ifPresent(value -> field(out, "group", value.value()));
            defaults.version().ifPresent(value -> field(out, "version", value.value()));
            defaults.javaRelease().ifPresent(
                    value -> field(out, "java", Integer.toString(value.value())));
            defaults.license().ifPresent(value -> field(out, "license", license(value)));
        });
        toolchains(out, authored.toolchains());
        authored.versions().ifPresent(versions -> versions(out, versions));
        repositories(out, authored.repositories().orElseGet(AuthoredDependencyRepositories::defaults));
        authored.credentials().ifPresent(credentials -> credentials(out, credentials));
        authored.platforms().ifPresent(platforms -> platforms(out, platforms));
        authored.build().coverage().ifPresent(coverage -> coverage(out, coverage));
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
        identity.license().ifPresent(value -> origin(out, "license", value, ConfigShowFormatter::license));

        EffectiveSharedConfiguration shared = effective.project().shared();
        effectiveToolchains(out, shared.toolchains());
        effectiveRepositories(out, shared.repositories());
        effectiveMap(out, "Versions", shared.versions(), VersionAliasValue::value);
        effectiveMap(out, "Credentials", shared.credentials(), ConfigShowFormatter::credential);
        effectivePlatforms(out, shared.platforms());
        effectiveCoverage(out, shared.coverage());
        return out.toString();
    }

    private static void workspaceSection(StringBuilder out, AuthoredWorkspace workspace) {
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
        workspace.projectDefaults().ifPresent(defaults -> {
            section(out, "Workspace project defaults");
            defaults.group().ifPresent(value -> field(out, "group", value.value()));
            defaults.version().ifPresent(value -> field(out, "version", value.value()));
            defaults.javaRelease().ifPresent(
                    value -> field(out, "java", Integer.toString(value.value())));
            defaults.license().ifPresent(value -> field(out, "license", license(value)));
        });
    }

    private static void workspaceContext(
            StringBuilder out, WorkspaceContext context, Optional<String> selectionSource) {
        field(out, "workspace", context.name().value().value());
        field(out, "member", context.memberPath().value());
        selectionSource.ifPresent(source -> field(out, "selection", source));
    }

    private static void identity(StringBuilder out, AuthoredProjectIdentity identity) {
        field(out, "name", identity.name().value());
        identity.version().ifPresent(value -> field(out, "version", value.value()));
        identity.group().ifPresent(value -> field(out, "group", value.value()));
        identity.javaRelease().ifPresent(value -> field(out, "java", Integer.toString(value.value())));
        identity.license().ifPresent(value -> field(out, "license", license(value)));
    }

    private static void toolchains(StringBuilder out, AuthoredToolchains toolchains) {
        if (toolchains.zolt().isEmpty() && toolchains.mainJava().isEmpty()
                && toolchains.testJava().isEmpty()) {
            return;
        }
        section(out, "Toolchains");
        toolchains.zolt().map(ZoltVersionPin::value).ifPresent(value -> field(out, "zolt", value));
        toolchains.mainJava().ifPresent(java -> javaToolchain(out, "java", java));
        toolchains.testJava().ifPresent(java -> testJavaToolchain(out, java));
    }

    private static void javaToolchain(StringBuilder out, String label, AuthoredJavaToolchain java) {
        field(out, label, describe(
                java.version().map(JavaFeatureRelease::value).map(release -> Integer.toString(release)),
                java.distribution().map(JavaDistribution::id),
                java.features().map(ConfigShowFormatter::features),
                java.policy().map(ToolchainPolicy::id)));
    }

    private static void testJavaToolchain(StringBuilder out, AuthoredJavaTestToolchain java) {
        field(out, "java.test", describe(
                java.version().map(JavaFeatureRelease::value).map(release -> Integer.toString(release)),
                java.distribution().map(JavaDistribution::id),
                Optional.empty(),
                java.policy().map(ToolchainPolicy::id)));
    }

    private static void versions(StringBuilder out, AuthoredVersionAliases versions) {
        if (versions.entries().isEmpty()) {
            return;
        }
        section(out, "Versions");
        versions.entries().forEach((id, value) -> field(out, id.value(), value.value()));
    }

    private static void repositories(StringBuilder out, AuthoredDependencyRepositories repositories) {
        section(out, "Repositories");
        field(out, "central", repositories.centralRepository()
                .map(repository -> repository.url().value())
                .orElse("disabled"));
        repositories.named().forEach((id, repository) -> field(out, id.value(), repository(repository)));
    }

    private static void credentials(StringBuilder out, AuthoredCredentials credentials) {
        if (credentials.entries().isEmpty()) {
            return;
        }
        section(out, "Credentials");
        credentials.entries().forEach((id, credential) -> field(out, id.value(), credential(credential)));
    }

    private static void platforms(StringBuilder out, AuthoredPlatforms platforms) {
        if (platforms.entries().isEmpty()) {
            return;
        }
        section(out, "Platforms");
        platforms.entries().forEach(
                (coordinate, selector) -> field(out, coordinate.value(), platform(selector)));
    }

    private static void coverage(StringBuilder out, AuthoredCoverage coverage) {
        section(out, "Coverage");
        coverage.line().ifPresent(value -> field(out, "line", percentage(value)));
        coverage.branch().ifPresent(value -> field(out, "branch", percentage(value)));
        coverage.instruction().ifPresent(value -> field(out, "instruction", percentage(value)));
        coverage.method().ifPresent(value -> field(out, "method", percentage(value)));
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
                    origin(out, "java.features", requested.features(), ConfigShowFormatter::features);
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
                (id, repository) -> origin(out, id.value(), repository, ConfigShowFormatter::repository));
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
                origin(out, coordinate.value(), selector, ConfigShowFormatter::platform));
    }

    private static void effectiveCoverage(StringBuilder out, EffectiveCoverage coverage) {
        if (coverage.line().isEmpty() && coverage.branch().isEmpty()
                && coverage.instruction().isEmpty() && coverage.method().isEmpty()) {
            return;
        }
        section(out, "Coverage");
        coverage.line().ifPresent(value -> origin(out, "line", value, ConfigShowFormatter::percentage));
        coverage.branch().ifPresent(value -> origin(out, "branch", value, ConfigShowFormatter::percentage));
        coverage.instruction().ifPresent(
                value -> origin(out, "instruction", value, ConfigShowFormatter::percentage));
        coverage.method().ifPresent(value -> origin(out, "method", value, ConfigShowFormatter::percentage));
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

    private static <T> void origin(
            StringBuilder out, String label, EffectiveValue<T> value, Function<T, String> render) {
        out.append(INDENT)
                .append(label)
                .append(": ")
                .append(render.apply(value.value()))
                .append(" (")
                .append(originLabel(value.origin()))
                .append(value.source().map(source -> ": " + source(source)).orElse(""))
                .append(")\n");
    }

    private static String originLabel(ValueOrigin origin) {
        return switch (origin) {
            case AUTHORED -> "authored";
            case INHERITED -> "inherited";
            case BUILT_IN -> "built-in";
        };
    }

    private static String source(ManifestSource source) {
        return source.manifestPath() + " " + String.join(".", source.fieldPath());
    }

    private static String describe(
            Optional<String> version,
            Optional<String> distribution,
            Optional<String> features,
            Optional<String> policy) {
        List<String> parts = new java.util.ArrayList<>();
        version.ifPresent(value -> parts.add("version " + value));
        distribution.ifPresent(value -> parts.add("distribution " + value));
        features.ifPresent(value -> parts.add("features " + value));
        policy.ifPresent(value -> parts.add("policy " + value));
        return parts.isEmpty() ? "declared" : String.join(", ", parts);
    }

    private static String repository(DependencyRepository repository) {
        return repository.url().value()
                + repository.credentials().map(id -> " (credentials " + id.value() + ")").orElse("");
    }

    private static String credential(RepositoryCredential credential) {
        return switch (credential) {
            case RepositoryCredential.BearerToken bearer ->
                    "bearer token from $" + bearer.tokenEnvironment().value();
            case RepositoryCredential.Basic basic -> "basic from $"
                    + basic.usernameEnvironment().value() + " and $" + basic.passwordEnvironment().value();
        };
    }

    private static String platform(PlatformSelector selector) {
        return switch (selector) {
            case PlatformSelector.FixedVersion fixed -> fixed.value();
            case PlatformSelector.VersionReference reference -> "versionRef " + reference.alias().value();
        };
    }

    private static String license(ProjectLicense license) {
        return switch (license) {
            case ProjectLicense.Identifier identifier -> identifier.id();
            case ProjectLicense.Metadata metadata -> metadata.id()
                    .or(metadata::name)
                    .orElse("custom");
        };
    }

    private static String features(Set<JavaFeature> features) {
        return features.isEmpty() ? "none" : join(features.stream().map(JavaFeature::id).toList());
    }

    private static String percentage(CoveragePercentage percentage) {
        double value = percentage.value();
        return value == Math.rint(value)
                ? Long.toString((long) value)
                : Double.toString(value);
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private static void section(StringBuilder out, String heading) {
        out.append('\n').append(heading).append('\n');
    }

    private static void field(StringBuilder out, String label, String value) {
        out.append(INDENT).append(label).append(": ").append(value).append('\n');
    }
}
