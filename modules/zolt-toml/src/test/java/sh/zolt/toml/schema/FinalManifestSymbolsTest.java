package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FinalManifestSymbolsTest {
    @Test
    void freezesEveryFinalSymbolFamily() {
        List<ManifestSymbolFamily> families = FinalManifestSchema.registry().symbols().families();
        assertEquals(List.of(
                "package-mode",
                "package-duplicate-policy",
                "toolchain-distribution",
                "toolchain-policy",
                "toolchain-feature",
                "conflict-policy",
                "unknown-license-policy",
                "resource-filter-target",
                "resource-missing-policy",
                "generated-tool-kind",
                "generated-preset-kind",
                "generated-step-kind",
                "generated-language",
                "generated-lane",
                "generated-cache-policy",
                "signing-method",
                "central-mode",
                "compiler-jdk-api-mode",
                "test-runtime-event-outcome",
                "built-in-command"), families.stream().map(ManifestSymbolFamily::name).toList());

        Map<String, List<String>> actual = new LinkedHashMap<>();
        families.forEach(family -> actual.put(family.name(), family.values()));

        assertEquals(Map.ofEntries(
                Map.entry(
                        "package-mode",
                        List.of("jar", "uber-jar", "war", "spring-boot", "spring-boot-war", "quarkus")),
                Map.entry("package-duplicate-policy", List.of("fail", "first-wins")),
                Map.entry("toolchain-distribution", List.of("temurin", "graalvm-community")),
                Map.entry("toolchain-policy", List.of("prefer-managed", "require-managed", "allow-system")),
                Map.entry("toolchain-feature", List.of("native-image")),
                Map.entry("conflict-policy", List.of("resolve", "warn", "fail")),
                Map.entry("unknown-license-policy", List.of("allow", "warn", "fail")),
                Map.entry("resource-filter-target", List.of("main", "test")),
                Map.entry("resource-missing-policy", List.of("fail", "keep")),
                Map.entry("generated-tool-kind", List.of("openapi", "protobuf", "jvm", "process")),
                Map.entry("generated-preset-kind", List.of("openapi")),
                Map.entry("generated-step-kind", List.of("openapi", "protobuf", "exec", "declared-root")),
                Map.entry("generated-language", List.of("java")),
                Map.entry(
                        "generated-lane",
                        List.of("java-sources", "test-sources", "resources", "test-resources", "intermediate")),
                Map.entry("generated-cache-policy", List.of("content", "none")),
                Map.entry("signing-method", List.of("gpg")),
                Map.entry("central-mode", List.of("manual", "automatic")),
                Map.entry("compiler-jdk-api-mode", List.of("release", "host")),
                Map.entry("test-runtime-event-outcome", List.of("passed", "skipped", "failed")),
                Map.entry(
                        "built-in-command",
                        List.of(
                                "add",
                                "aliases",
                                "bom",
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
                                "platforms",
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
                                "versions",
                                "why",
                                "workspace"))), actual);
        assertEquals(
                Set.copyOf(actual.get("built-in-command")),
                FinalManifestSymbols.builtInCommandNames());
        assertEquals(
                actual.get("built-in-command"),
                List.copyOf(FinalManifestSymbols.builtInCommandNames()));
    }
}
