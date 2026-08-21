package sh.zolt.toml.schema;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Closed symbolic values accepted by the final 0.1.0 manifest language. */
public final class FinalManifestSymbols {
    private static final ManifestSymbolFamily BUILT_IN_COMMANDS = family(
            "built-in-command",
            "add",
            "aliases",
            "build",
            "cache",
            "check",
            "classpath",
            "clean",
            "config",
            "conflicts",
            "coverage",
            "doctor",
            "exec",
            "explain",
            "help",
            "ide",
            "init",
            "integration-test",
            "licenses",
            "native",
            "native-smoke",
            "outdated",
            "package",
            "plan",
            "platform",
            "policy",
            "publish",
            "quarkus",
            "release-archive",
            "release-index",
            "release-verify",
            "remove",
            "resolve",
            "run",
            "run-package",
            "sbom",
            "self",
            "self-check",
            "self-parity",
            "shims",
            "task",
            "tasks",
            "test",
            "toolchain",
            "tree",
            "update",
            "version",
            "why",
            "workspace");
    private static final Set<String> BUILT_IN_COMMAND_NAMES = Collections.unmodifiableSet(
            new LinkedHashSet<>(BUILT_IN_COMMANDS.values()));

    private static final ManifestSymbolRegistry REGISTRY = new ManifestSymbolRegistry(List.of(
            family("package-mode", "jar", "uber-jar", "war", "spring-boot", "spring-boot-war", "quarkus"),
            family("package-duplicate-policy", "fail", "first-wins"),
            family("toolchain-distribution", "temurin", "graalvm-community"),
            family("toolchain-policy", "prefer-managed", "require-managed", "allow-system"),
            family("toolchain-feature", "native-image"),
            family("conflict-policy", "resolve", "warn", "fail"),
            family("unknown-license-policy", "allow", "warn", "fail"),
            family("resource-filter-target", "main", "test"),
            family("resource-missing-policy", "fail", "keep"),
            family("generated-tool-kind", "openapi", "protobuf", "jvm", "process"),
            family("generated-preset-kind", "openapi"),
            family("generated-step-kind", "openapi", "protobuf", "exec", "declared-root"),
            family("generated-language", "java"),
            family("generated-lane", "java-sources", "test-sources", "resources", "test-resources", "intermediate"),
            family("generated-cache-policy", "content", "none"),
            family("signing-method", "gpg"),
            family("central-mode", "manual", "automatic"),
            family("compiler-jdk-api-mode", "release", "host"),
            family("test-runtime-event-outcome", "passed", "skipped", "failed"),
            BUILT_IN_COMMANDS));

    private FinalManifestSymbols() {
    }

    public static ManifestSymbolRegistry registry() {
        return REGISTRY;
    }

    public static Set<String> builtInCommandNames() {
        return BUILT_IN_COMMAND_NAMES;
    }

    private static ManifestSymbolFamily family(String name, String... values) {
        return new ManifestSymbolFamily(name, List.of(values));
    }
}
