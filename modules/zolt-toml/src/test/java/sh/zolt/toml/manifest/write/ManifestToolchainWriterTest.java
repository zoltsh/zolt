package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

final class ManifestToolchainWriterTest {
    @Test
    void emitsTheVirtualWorkspaceGoldenToolchainExactly() {
        AuthoredJavaToolchain main = new AuthoredJavaToolchain(
                Optional.empty(),
                Optional.of(JavaDistribution.GRAALVM_COMMUNITY),
                Optional.of(Set.of(JavaFeature.NATIVE_IMAGE)),
                Optional.of(ToolchainPolicy.REQUIRE_MANAGED));

        assertEquals(
                """
                [toolchain.java]
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"
                """,
                write(new AuthoredToolchains(
                        Optional.empty(), Optional.of(main), Optional.empty())));
    }

    @Test
    void emitsAllAuthoredToolchainFieldsInExactSchemaOrder() {
        AuthoredToolchains toolchains = new AuthoredToolchains(
                Optional.of(new ZoltVersionPin("0.1.0-rc.1")),
                Optional.of(new AuthoredJavaToolchain(
                        Optional.of(new JavaFeatureRelease(21)),
                        Optional.of(JavaDistribution.GRAALVM_COMMUNITY),
                        Optional.of(Set.of(JavaFeature.NATIVE_IMAGE)),
                        Optional.of(ToolchainPolicy.REQUIRE_MANAGED))),
                Optional.of(new AuthoredJavaTestToolchain(
                        Optional.of(new JavaFeatureRelease(17)),
                        Optional.of(JavaDistribution.TEMURIN),
                        Optional.of(ToolchainPolicy.ALLOW_SYSTEM))));

        String output = write(toolchains);

        assertEquals(
                """
                [toolchain.zolt]
                version = "0.1.0-rc.1"

                [toolchain.java]
                version = 21
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"

                [toolchain.java.test]
                version = 17
                distribution = "temurin"
                policy = "allow-system"
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        assertEquals(toolchains, decodeToolchains(output));
    }

    @Test
    void canonicalizesSparseFieldsByOmittingAnEmptyFeatureList() {
        AuthoredToolchains toolchains = new AuthoredToolchains(
                Optional.empty(),
                Optional.of(new AuthoredJavaToolchain(
                        Optional.empty(),
                        Optional.of(JavaDistribution.TEMURIN),
                        Optional.of(Set.of()),
                        Optional.empty())),
                Optional.of(new AuthoredJavaTestToolchain(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ToolchainPolicy.PREFER_MANAGED))));

        String output = write(toolchains);

        assertEquals(
                """
                [toolchain.java]
                distribution = "temurin"

                [toolchain.java.test]
                policy = "prefer-managed"
                """,
                output);
        assertFalse(output.contains("[toolchain.zolt]"));
        assertFalse(output.contains("version ="));
        assertFalse(output.contains("features ="));
        AuthoredToolchains decoded = decodeToolchains(output);
        assertEquals(Optional.empty(), decoded.mainJava().orElseThrow().features());
        assertEquals(
                JavaDistribution.TEMURIN,
                decoded.mainJava().orElseThrow().distribution().orElseThrow());
        assertEquals(toolchains.testJava(), decoded.testJava());
    }

    @Test
    void emitsNoTablesForAnEmptyAggregateOrAnImpliedMainRequest() {
        assertEquals("", write(AuthoredToolchains.empty()));

        AuthoredJavaTestToolchain test = new AuthoredJavaTestToolchain(
                Optional.of(new JavaFeatureRelease(17)),
                Optional.empty(),
                Optional.empty());
        String testOnly = write(new AuthoredToolchains(
                Optional.empty(), Optional.empty(), Optional.of(test)));

        assertEquals(
                """
                [toolchain.java.test]
                version = 17
                """,
                testOnly);
        assertFalse(testOnly.contains("[toolchain.java]\n"));
        assertEquals(
                Optional.empty(),
                decodeToolchains(testOnly).mainJava());
    }

    private static String write(AuthoredToolchains toolchains) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestToolchainWriter().write(emitter, toolchains);
        return emitter.finish();
    }

    private static AuthoredToolchains decodeToolchains(String source) {
        return decodeAuthoredManifest("[project]\nname = \"round-trip\"\n\n" + source)
                .toolchains();
    }
}
