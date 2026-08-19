package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class FinalManifestFieldsTest extends FinalManifestSchemaTestSupport {
    @Test
    void registersEveryFrozenFieldInCanonicalOrder() {
        assertEquals(
                List.of(
                        "workspace.name",
                        "workspace.members.default",
                        "workspace.members.include",
                        "workspace.members.exclude",
                        "workspace.project.group",
                        "workspace.project.version",
                        "workspace.project.java",
                        "workspace.project.license",
                        "project.name",
                        "project.version",
                        "project.group",
                        "project.java",
                        "project.main",
                        "project.description",
                        "project.url",
                        "project.issues",
                        "project.license",
                        "project.scm.url",
                        "project.scm.connection",
                        "project.scm.developerConnection",
                        "project.scm.tag",
                        "project.developers.<id>.name",
                        "project.developers.<id>.email",
                        "project.developers.<id>.organization",
                        "project.developers.<id>.url",
                        "toolchain.zolt.version",
                        "toolchain.java.version",
                        "toolchain.java.distribution",
                        "toolchain.java.features",
                        "toolchain.java.policy",
                        "toolchain.java.test.version",
                        "toolchain.java.test.distribution",
                        "toolchain.java.test.policy",
                        "versions.<id>",
                        "repositories.central",
                        "repositories.order",
                        "repositories.<id>.url",
                        "repositories.<id>.credentials",
                        "credentials.<id>.tokenEnv",
                        "credentials.<id>.usernameEnv",
                        "credentials.<id>.passwordEnv",
                        "platforms.<coordinate>",
                        "dependencies.<coordinate>",
                        "dependencies.api.<coordinate>",
                        "dependencies.runtime.<coordinate>",
                        "dependencies.provided.<coordinate>",
                        "dependencies.dev.<coordinate>",
                        "dependencies.test.<coordinate>",
                        "dependencies.processor.<coordinate>",
                        "dependencies.test-processor.<coordinate>",
                        "dependencies.constraints.<coordinate>",
                        "dependencies.policy.conflicts",
                        "dependencies.policy.deny",
                        "dependencies.policy.licenses.allow",
                        "dependencies.policy.licenses.deny",
                        "dependencies.policy.licenses.unknown",
                        "dependencies.license-exceptions.<coordinate>.allow",
                        "dependencies.license-exceptions.<coordinate>.version",
                        "dependencies.license-exceptions.<coordinate>.reason",
                        "build.sources",
                        "build.output.root",
                        "build.output.main",
                        "build.output.test",
                        "build.output.integration",
                        "build.metadata.buildInfo",
                        "build.metadata.git",
                        "build.metadata.reproducible",
                        "compiler.encoding",
                        "compiler.jdkApi",
                        "compiler.args",
                        "compiler.test.jdkApi",
                        "compiler.test.args",
                        "compiler.generated.main",
                        "compiler.generated.test",
                        "resources.main",
                        "resources.test",
                        "resources.filter.targets",
                        "resources.filter.include",
                        "resources.filter.missing",
                        "resources.tokens.<id>",
                        "generated.tools.<id>.kind",
                        "generated.tools.<id>.coordinate",
                        "generated.tools.<id>.version",
                        "generated.tools.<id>.versionRef",
                        "generated.tools.<id>.protocCoordinate",
                        "generated.tools.<id>.protocVersion",
                        "generated.tools.<id>.protocVersionRef",
                        "generated.tools.<id>.grpcCoordinate",
                        "generated.tools.<id>.grpcVersion",
                        "generated.tools.<id>.grpcVersionRef",
                        "generated.tools.<id>.coordinates",
                        "generated.tools.<id>.mainClass",
                        "generated.tools.<id>.binary",
                        "generated.tools.<id>.versionCommand",
                        "generated.tools.<id>.versionExpect",
                        "generated.tools.<id>.allowUnpinnedTool",
                        "generated.presets.<id>.kind",
                        "generated.presets.<id>.generator",
                        "generated.presets.<id>.library",
                        "generated.presets.<id>.apiPackage",
                        "generated.presets.<id>.modelPackage",
                        "generated.presets.<id>.invokerPackage",
                        "generated.presets.<id>.config",
                        "generated.presets.<id>.templateDir",
                        "generated.presets.<id>.validateSpec",
                        "generated.presets.<id>.options",
                        "generated.presets.<id>.additionalProperties",
                        "generated.presets.<id>.configOptions",
                        "generated.presets.<id>.globalProperties",
                        "generated.presets.<id>.typeMappings",
                        "generated.presets.<id>.importMappings",
                        "generated.main.<id>.kind",
                        "generated.main.<id>.language",
                        "generated.main.<id>.tool",
                        "generated.main.<id>.mainClass",
                        "generated.main.<id>.args",
                        "generated.main.<id>.input",
                        "generated.main.<id>.inputs",
                        "generated.main.<id>.output",
                        "generated.main.<id>.produces",
                        "generated.main.<id>.into",
                        "generated.main.<id>.preset",
                        "generated.main.<id>.generator",
                        "generated.main.<id>.library",
                        "generated.main.<id>.apiPackage",
                        "generated.main.<id>.modelPackage",
                        "generated.main.<id>.invokerPackage",
                        "generated.main.<id>.config",
                        "generated.main.<id>.templateDir",
                        "generated.main.<id>.validateSpec",
                        "generated.main.<id>.options",
                        "generated.main.<id>.additionalProperties",
                        "generated.main.<id>.configOptions",
                        "generated.main.<id>.globalProperties",
                        "generated.main.<id>.typeMappings",
                        "generated.main.<id>.importMappings",
                        "generated.main.<id>.javaPackage",
                        "generated.main.<id>.grpc",
                        "generated.main.<id>.cache",
                        "generated.main.<id>.cwd",
                        "generated.main.<id>.env",
                        "generated.main.<id>.secretEnv",
                        "generated.main.<id>.inheritEnv",
                        "generated.main.<id>.timeoutSeconds",
                        "generated.main.<id>.required",
                        "generated.main.<id>.clean",
                        "generated.test.<id>.kind",
                        "generated.test.<id>.language",
                        "generated.test.<id>.tool",
                        "generated.test.<id>.mainClass",
                        "generated.test.<id>.args",
                        "generated.test.<id>.input",
                        "generated.test.<id>.inputs",
                        "generated.test.<id>.output",
                        "generated.test.<id>.produces",
                        "generated.test.<id>.into",
                        "generated.test.<id>.preset",
                        "generated.test.<id>.generator",
                        "generated.test.<id>.library",
                        "generated.test.<id>.apiPackage",
                        "generated.test.<id>.modelPackage",
                        "generated.test.<id>.invokerPackage",
                        "generated.test.<id>.config",
                        "generated.test.<id>.templateDir",
                        "generated.test.<id>.validateSpec",
                        "generated.test.<id>.options",
                        "generated.test.<id>.additionalProperties",
                        "generated.test.<id>.configOptions",
                        "generated.test.<id>.globalProperties",
                        "generated.test.<id>.typeMappings",
                        "generated.test.<id>.importMappings",
                        "generated.test.<id>.javaPackage",
                        "generated.test.<id>.grpc",
                        "generated.test.<id>.cache",
                        "generated.test.<id>.cwd",
                        "generated.test.<id>.env",
                        "generated.test.<id>.secretEnv",
                        "generated.test.<id>.inheritEnv",
                        "generated.test.<id>.timeoutSeconds",
                        "generated.test.<id>.required",
                        "generated.test.<id>.clean",
                        "test.sources.java",
                        "test.sources.groovy",
                        "test.runtime.jvmArgs",
                        "test.runtime.properties",
                        "test.runtime.env",
                        "test.runtime.events",
                        "test.integration.sources",
                        "test.integration.resources",
                        "test.suites.<id>.classes",
                        "test.suites.<id>.excludeClasses",
                        "test.suites.<id>.tags",
                        "test.suites.<id>.excludeTags",
                        "test.suites.<id>.workers",
                        "test.suites.<id>.locks",
                        "coverage.line",
                        "coverage.branch",
                        "coverage.instruction",
                        "coverage.method",
                        "package.mode",
                        "package.sources",
                        "package.javadoc",
                        "package.testJar",
                        "package.duplicates",
                        "package.manifest.<attribute>",
                        "bom.members",
                        "bom.exclude",
                        "bom.versions.<coordinate>",
                        "bom.imports.<coordinate>",
                        "framework.spring-boot.native",
                        "native.name",
                        "native.output",
                        "native.args",
                        "publish.release",
                        "publish.snapshot",
                        "publish.repositories.<id>.url",
                        "publish.repositories.<id>.credentials",
                        "publish.signing.method",
                        "publish.signing.keyId",
                        "publish.signing.passphraseEnv",
                        "publish.central.tokenEnv",
                        "publish.central.mode",
                        "publish.central.name",
                        "publish.central.url",
                        "tasks.<id>.description",
                        "tasks.<id>.run",
                        "tasks.<id>.cwd",
                        "tasks.<id>.env",
                        "aliases.<id>"),
                fieldPaths());
    }

    @Test
    void recordsExactValueKinds() {
        Map<String, ManifestValueKind> valueKinds = registry.fields().stream()
                .collect(Collectors.toMap(field -> field.path().toString(), ManifestField::valueKind));

        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.default"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.include"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("workspace.members.exclude"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("workspace.project.java"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("project.java"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("workspace.project.license"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("project.license"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.zolt.version"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("toolchain.java.version"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.java.distribution"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("toolchain.java.features"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.java.policy"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("toolchain.java.test.version"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.java.test.distribution"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("toolchain.java.test.policy"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("versions.<id>"));
        assertEquals(
                ManifestValueKind.BOOLEAN_OR_STRING_OR_INLINE_TABLE,
                valueKinds.get("repositories.central"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("repositories.order"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("repositories.<id>.url"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("repositories.<id>.credentials"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("credentials.<id>.tokenEnv"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("credentials.<id>.usernameEnv"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("credentials.<id>.passwordEnv"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("platforms.<coordinate>"));
        assertEquals(ManifestValueKind.STRING_OR_INLINE_TABLE, valueKinds.get("dependencies.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.api.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.runtime.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.provided.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.dev.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.test.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.processor.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.test-processor.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("dependencies.constraints.<coordinate>"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("dependencies.policy.conflicts"));
        assertEquals(ManifestValueKind.INLINE_TABLE_ARRAY, valueKinds.get("dependencies.policy.deny"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("dependencies.policy.licenses.allow"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("dependencies.policy.licenses.deny"));
        assertEquals(
                ManifestValueKind.STRING,
                valueKinds.get("dependencies.policy.licenses.unknown"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("dependencies.license-exceptions.<coordinate>.allow"));
        assertEquals(
                ManifestValueKind.STRING,
                valueKinds.get("dependencies.license-exceptions.<coordinate>.version"));
        assertEquals(
                ManifestValueKind.STRING,
                valueKinds.get("dependencies.license-exceptions.<coordinate>.reason"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("build.sources"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("build.output.root"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("build.output.main"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("build.output.test"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("build.output.integration"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("build.metadata.buildInfo"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("build.metadata.git"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("build.metadata.reproducible"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.encoding"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.jdkApi"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("compiler.args"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.test.jdkApi"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("compiler.test.args"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.generated.main"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("compiler.generated.test"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("resources.main"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("resources.test"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("resources.filter.targets"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("resources.filter.include"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("resources.filter.missing"));
        assertEquals(ManifestValueKind.INLINE_TABLE, valueKinds.get("resources.tokens.<id>"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.sources.java"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.sources.groovy"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.runtime.jvmArgs"));
        assertEquals(ManifestValueKind.INLINE_TABLE, valueKinds.get("test.runtime.properties"));
        assertEquals(ManifestValueKind.INLINE_TABLE, valueKinds.get("test.runtime.env"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.runtime.events"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.integration.sources"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.integration.resources"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.suites.<id>.classes"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("test.suites.<id>.excludeClasses"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("test.suites.<id>.tags"));
        assertEquals(
                ManifestValueKind.STRING_ARRAY,
                valueKinds.get("test.suites.<id>.excludeTags"));
        assertEquals(ManifestValueKind.INTEGER, valueKinds.get("test.suites.<id>.workers"));
        assertEquals(ManifestValueKind.INLINE_TABLE_ARRAY, valueKinds.get("test.suites.<id>.locks"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.line"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.branch"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.instruction"));
        assertEquals(ManifestValueKind.NUMBER, valueKinds.get("coverage.method"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("package.mode"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("package.sources"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("package.javadoc"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("package.testJar"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("package.duplicates"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("package.manifest.<attribute>"));
        assertEquals(ManifestValueKind.BOOLEAN_OR_STRING_ARRAY, valueKinds.get("bom.members"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("bom.exclude"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("bom.versions.<coordinate>"));
        assertEquals(
                ManifestValueKind.STRING_OR_INLINE_TABLE,
                valueKinds.get("bom.imports.<coordinate>"));
        assertEquals(ManifestValueKind.BOOLEAN, valueKinds.get("framework.spring-boot.native"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("native.name"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("native.output"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("native.args"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.release"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.snapshot"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.repositories.<id>.url"));
        assertEquals(
                ManifestValueKind.STRING,
                valueKinds.get("publish.repositories.<id>.credentials"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.signing.method"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.signing.keyId"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.signing.passphraseEnv"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.central.tokenEnv"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.central.mode"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.central.name"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("publish.central.url"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("tasks.<id>.description"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("tasks.<id>.run"));
        assertEquals(ManifestValueKind.STRING, valueKinds.get("tasks.<id>.cwd"));
        assertEquals(ManifestValueKind.INLINE_TABLE, valueKinds.get("tasks.<id>.env"));
        assertEquals(ManifestValueKind.STRING_ARRAY, valueKinds.get("aliases.<id>"));
    }
}
