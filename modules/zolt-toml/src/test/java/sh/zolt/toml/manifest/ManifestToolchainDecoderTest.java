package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestToolchainFields;
import sh.zolt.toml.schema.ManifestField;

final class ManifestToolchainDecoderTest {
    @Test
    void decodesAllEightAuthoredToolchainFieldsWithoutApplyingDefaults() {
        AuthoredToolchains toolchains = decode("""
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
                """);

        assertEquals("0.1.0-rc.1", toolchains.zolt().orElseThrow().value());
        AuthoredJavaToolchain main = toolchains.mainJava().orElseThrow();
        assertEquals(21, main.version().orElseThrow().value());
        assertEquals(JavaDistribution.GRAALVM_COMMUNITY, main.distribution().orElseThrow());
        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), main.features().orElseThrow());
        assertEquals(ToolchainPolicy.REQUIRE_MANAGED, main.policy().orElseThrow());
        assertEquals(17, toolchains.testJava().orElseThrow().version().orElseThrow().value());
        assertEquals(
                JavaDistribution.TEMURIN,
                toolchains.testJava().orElseThrow().distribution().orElseThrow());
        assertEquals(
                ToolchainPolicy.ALLOW_SYSTEM,
                toolchains.testJava().orElseThrow().policy().orElseThrow());
    }

    @Test
    void keepsTestOnlyTablesFromMaterializingAnImpliedMainRequest() {
        AuthoredToolchains toolchains = decode("""
                [toolchain.java.test]
                distribution = "temurin"
                """);

        assertTrue(toolchains.mainJava().isEmpty());
        assertEquals(
                JavaDistribution.TEMURIN,
                toolchains.testJava().orElseThrow().distribution().orElseThrow());
        assertTrue(toolchains.testJava().orElseThrow().version().isEmpty());
        assertTrue(toolchains.testJava().orElseThrow().policy().isEmpty());
    }

    @Test
    void distinguishesOmittedFeaturesFromAnExplicitEmptyFeatureSet() {
        AuthoredJavaToolchain omitted = decode("""
                [toolchain.java]
                distribution = "temurin"
                """).mainJava().orElseThrow();
        AuthoredJavaToolchain explicitEmpty = decode("""
                [toolchain.java]
                distribution = "temurin"
                features = []
                """).mainJava().orElseThrow();

        assertEquals(Optional.empty(), omitted.features());
        assertEquals(Optional.of(Set.of()), explicitEmpty.features());
    }

    @Test
    void treatsEachNonemptyDirectMainFieldAsAuthoredPresence() {
        assertTrue(decode("[toolchain.java]\nversion = 21\n").mainJava().isPresent());
        assertTrue(decode("[toolchain.java]\ndistribution = \"temurin\"\n")
                .mainJava().isPresent());
        assertTrue(decode("[toolchain.java]\nfeatures = [\"native-image\"]\n")
                .mainJava().isPresent());
        assertTrue(decode("[toolchain.java]\npolicy = \"prefer-managed\"\n")
                .mainJava().isPresent());
    }

    @Test
    void anchorsAnEmptyFeaturesOnlyAggregateToItsDirectField() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode("[toolchain.java]\nfeatures = []\n"));

        assertEquals(
                "Invalid value for `toolchain.java.features`: An authored [toolchain.java] table "
                        + "must contain at least one meaningful field.",
                failure.getMessage());
    }

    @Test
    void canonicalizesRepeatedFeatureTokensIntoTheAuthoredSet() {
        AuthoredJavaToolchain main = decode(
                "[toolchain.java]\nfeatures = [\"native-image\", \"native-image\"]\n")
                .mainJava()
                .orElseThrow();

        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), main.features().orElseThrow());
    }

    @Test
    void anchorsZoltAndJavaValueFailuresToTheirConcreteFields() {
        assertFailure("""
                [toolchain.zolt]
                version = "latest"
                """, "Invalid value for `toolchain.zolt.version`: Invalid Zolt version");
        assertFailure("""
                [toolchain.java]
                version = 0
                """, "Invalid value for `toolchain.java.version`: Java feature release must be a positive");
        assertFailure("""
                [toolchain.java]
                version = 9223372036854775807
                """, "Invalid value for `toolchain.java.version`: Java feature release is outside");
        assertFailure("""
                [toolchain.java.test]
                version = 0
                """, "Invalid value for `toolchain.java.test.version`: Java feature release must be a positive");
        assertFailure("""
                [toolchain.java.test]
                version = -9223372036854775808
                """, "Invalid value for `toolchain.java.test.version`: Java feature release is outside");
    }

    @Test
    void leavesTheSchemaAsTheAuthorityForClosedSymbols() {
        assertFailure("""
                [toolchain.java]
                distribution = "oracle"
                """, "Invalid symbol `oracle` for `toolchain.java.distribution`");
        assertFailure("""
                [toolchain.java]
                features = ["jlink"]
                """, "Invalid symbol `jlink` for `toolchain.java.features`");
        assertFailure("""
                [toolchain.java.test]
                policy = "managed"
                """, "Invalid symbol `managed` for `toolchain.java.test.policy`");
        assertFailure("""
                [toolchain.java.test]
                features = ["native-image"]
                """, "Unknown manifest field `toolchain.java.test.features`");

        assertModelSymbols(
                FinalManifestToolchainFields.JAVA_DISTRIBUTION,
                Arrays.asList(JavaDistribution.values()),
                JavaDistribution::id);
        assertModelSymbols(
                FinalManifestToolchainFields.JAVA_FEATURES,
                Arrays.asList(JavaFeature.values()),
                JavaFeature::id);
        assertModelSymbols(
                FinalManifestToolchainFields.JAVA_POLICY,
                Arrays.asList(ToolchainPolicy.values()),
                ToolchainPolicy::id);
    }

    @Test
    void returnsTheEmptyAggregateAndDefersDefaultsAndInheritance() {
        assertEquals(AuthoredToolchains.empty(), decode(""));

        AuthoredToolchains requests = decode("""
                [toolchain.java]
                policy = "allow-system"

                [toolchain.java.test]
                version = 17
                """);
        assertTrue(requests.mainJava().orElseThrow().version().isEmpty());
        assertTrue(requests.mainJava().orElseThrow().distribution().isEmpty());
        assertTrue(requests.testJava().orElseThrow().distribution().isEmpty());
        assertTrue(requests.testJava().orElseThrow().policy().isEmpty());
        assertFalse(requests.zolt().isPresent());
    }

    private static AuthoredToolchains decode(String source) {
        return new ManifestToolchainDecoder().decode(ManifestSemanticTestSupport.index(source));
    }

    private static void assertFailure(String source, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    private static <T> void assertModelSymbols(
            ManifestField field,
            List<T> modelValues,
            Function<T, String> id) {
        String familyName = field.symbolFamily().orElseThrow();
        List<String> schemaValues = FinalManifestSchema.registry()
                .symbols()
                .family(familyName)
                .orElseThrow()
                .values();
        assertEquals(Set.copyOf(schemaValues), Set.copyOf(modelValues.stream().map(id).toList()));
    }
}
