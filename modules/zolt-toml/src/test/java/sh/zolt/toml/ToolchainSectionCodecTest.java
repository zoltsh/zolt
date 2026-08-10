package sh.zolt.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

final class ToolchainSectionCodecTest {
    @Test
    void parsesJavaToolchainTable() {
        Optional<JavaToolchainRequest> request = ToolchainSectionCodec.parseJavaToolchain(Toml.parse("""
                [toolchain.zolt]
                version = "0.1.0"

                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"
                """), "zolt.toml");

        JavaToolchainRequest java = request.orElseThrow();
        assertEquals("21", java.version());
        assertEquals(Optional.of(JavaDistribution.GRAALVM_COMMUNITY), java.distribution());
        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), java.features());
        assertEquals(ToolchainPolicy.REQUIRE_MANAGED, java.policy());
    }

    @Test
    void javaToolchainDoesNotBreakZoltVersionParsing() {
        Optional<String> version = ToolchainSectionCodec.parseZoltVersion(Toml.parse("""
                [toolchain.zolt]
                version = "0.1.0"

                [toolchain.java]
                version = "21"
                """), "zolt.toml");

        assertEquals(Optional.of("0.1.0"), version);
    }

    @Test
    void rejectsScalarJavaToolchain() {
        ZoltConfigException exception = assertThrows(
                ZoltConfigException.class,
                () -> ToolchainSectionCodec.parseJavaToolchain(Toml.parse("""
                        [toolchain]
                        java = "21"
                        """), "zolt.toml"));

        assertEquals("Invalid value for [toolchain].java in zolt.toml. Use a table.", exception.getMessage());
    }

    @Test
    void parsesTestToolchainInheritingDistributionFeaturesAndPolicy() {
        org.tomlj.TomlParseResult result = Toml.parse("""
                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"

                [toolchain.java.test]
                version = "17"
                """);
        JavaToolchainRequest main = ToolchainSectionCodec.parseJavaToolchain(result, "zolt.toml").orElseThrow();

        JavaToolchainRequest test =
                ToolchainSectionCodec.parseJavaTestToolchain(result, "zolt.toml", main).orElseThrow();

        assertEquals("17", test.version());
        assertEquals(Optional.of(JavaDistribution.GRAALVM_COMMUNITY), test.distribution());
        assertEquals(Set.of(JavaFeature.NATIVE_IMAGE), test.features());
        assertEquals(ToolchainPolicy.REQUIRE_MANAGED, test.policy());
    }

    @Test
    void testToolchainCanOverrideDistribution() {
        org.tomlj.TomlParseResult result = Toml.parse("""
                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"

                [toolchain.java.test]
                version = "17"
                distribution = "temurin"
                """);
        JavaToolchainRequest main = ToolchainSectionCodec.parseJavaToolchain(result, "zolt.toml").orElseThrow();

        JavaToolchainRequest test =
                ToolchainSectionCodec.parseJavaTestToolchain(result, "zolt.toml", main).orElseThrow();

        assertEquals(Optional.of(JavaDistribution.TEMURIN), test.distribution());
    }

    @Test
    void testToolchainCanClearInheritedNativeImageFeature() {
        org.tomlj.TomlParseResult result = Toml.parse("""
                [toolchain.java]
                version = "25"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"

                [toolchain.java.test]
                version = "25"
                distribution = "temurin"
                features = []
                """);
        JavaToolchainRequest main = ToolchainSectionCodec.parseJavaToolchain(result, "zolt.toml").orElseThrow();

        JavaToolchainRequest test =
                ToolchainSectionCodec.parseJavaTestToolchain(result, "zolt.toml", main).orElseThrow();

        assertEquals(Optional.of(JavaDistribution.TEMURIN), test.distribution());
        assertTrue(test.features().isEmpty());
        assertEquals(ToolchainPolicy.REQUIRE_MANAGED, test.policy());
    }

    @Test
    void equalVersionTestToolchainIsIdenticalToMainRequest() {
        org.tomlj.TomlParseResult result = Toml.parse("""
                [toolchain.java]
                version = "21"
                distribution = "temurin"

                [toolchain.java.test]
                version = "21"
                """);
        JavaToolchainRequest main = ToolchainSectionCodec.parseJavaToolchain(result, "zolt.toml").orElseThrow();

        JavaToolchainRequest test =
                ToolchainSectionCodec.parseJavaTestToolchain(result, "zolt.toml", main).orElseThrow();

        assertEquals(main, test);
    }

    @Test
    void testToolchainWithoutMainToolchainIsRejected() {
        org.tomlj.TomlParseResult result = Toml.parse("""
                [toolchain.java.test]
                version = "17"
                """);

        ZoltConfigException exception = assertThrows(
                ZoltConfigException.class,
                () -> ToolchainSectionCodec.parseJavaTestToolchain(result, "zolt.toml", null));

        assertTrue(exception.getMessage().contains("[toolchain.java.test] needs a [toolchain.java] entry"));
    }

    @Test
    void rejectsUnknownTestToolchainField() {
        org.tomlj.TomlParseResult result = Toml.parse("""
                [toolchain.java]
                version = "21"

                [toolchain.java.test]
                version = "17"
                policy = "allow-system"
                """);
        JavaToolchainRequest main = ToolchainSectionCodec.parseJavaToolchain(result, "zolt.toml").orElseThrow();

        ZoltConfigException exception = assertThrows(
                ZoltConfigException.class,
                () -> ToolchainSectionCodec.parseJavaTestToolchain(result, "zolt.toml", main));

        assertTrue(exception.getMessage().contains("Unknown field [toolchain.java.test].policy"));
    }

    @Test
    void noTestToolchainReturnsEmpty() {
        org.tomlj.TomlParseResult result = Toml.parse("""
                [toolchain.java]
                version = "21"
                """);
        JavaToolchainRequest main = ToolchainSectionCodec.parseJavaToolchain(result, "zolt.toml").orElseThrow();

        assertTrue(ToolchainSectionCodec.parseJavaTestToolchain(result, "zolt.toml", main).isEmpty());
    }

    @Test
    void rejectsUnknownFeature() {
        ZoltConfigException exception = assertThrows(
                ZoltConfigException.class,
                () -> ToolchainSectionCodec.parseJavaToolchain(Toml.parse("""
                        [toolchain.java]
                        version = "21"
                        features = ["loom"]
                        """), "zolt.toml"));

        assertTrue(exception.getMessage().contains("Unsupported value for [toolchain.java].features"));
    }

    @Test
    void rejectsFloatingJavaToolchainVersionAlias() {
        ZoltConfigException exception = assertThrows(
                ZoltConfigException.class,
                () -> ToolchainSectionCodec.parseJavaToolchain(Toml.parse("""
                        [toolchain.java]
                        version = "latest"
                        distribution = "temurin"
                        """), "zolt.toml"));

        assertTrue(exception.getMessage().contains("Java feature release number"));
        assertTrue(exception.getMessage().contains("aliases such as `latest` are not supported"));
    }

    @Test
    void rejectsNonAsciiJavaToolchainVersionNumerals() {
        ZoltConfigException exception = assertThrows(
                ZoltConfigException.class,
                () -> ToolchainSectionCodec.parseJavaToolchain(Toml.parse("""
                        [toolchain.java]
                        version = "٢٥"
                        distribution = "temurin"
                        """), "zolt.toml"));

        assertTrue(exception.getMessage().contains("Java feature release number"));
    }
}
