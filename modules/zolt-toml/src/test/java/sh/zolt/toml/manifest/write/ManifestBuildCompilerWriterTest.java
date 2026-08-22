package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCompiler;

final class ManifestBuildCompilerWriterTest {
    @Test
    void emitsEveryBuildAndCompilerFieldInExactSchemaAndModelOrder() {
        AuthoredBuild build = new AuthoredBuild(
                List.of(path("src/main/java"), path("src/generated/java")),
                Optional.of(new AuthoredBuild.Output(
                        Optional.of(path(".zolt/build")),
                        Optional.of(path("main-output")),
                        Optional.of(path("test-output")),
                        Optional.of(path("integration-output")))),
                Optional.of(new AuthoredBuild.Metadata(
                        Optional.of(true), Optional.of(true), Optional.of(true))));
        AuthoredCompiler compiler = new AuthoredCompiler(
                Optional.of("UTF-16"),
                Optional.of(AuthoredCompiler.JdkApiMode.HOST),
                List.of("-Xlint:all", "-parameters"),
                Optional.of(new AuthoredCompiler.Test(
                        Optional.of(AuthoredCompiler.JdkApiMode.RELEASE),
                        List.of("-g", "-Xlint:none"))),
                Optional.of(new AuthoredCompiler.Generated(
                        Optional.of(path("generated/main")),
                        Optional.of(path("generated/test")))));

        String output = write(Optional.of(build), Optional.of(compiler));

        assertEquals(
                """
                [build]
                sources = ["src/main/java", "src/generated/java"]

                [build.output]
                root = ".zolt/build"
                main = "main-output"
                test = "test-output"
                integration = "integration-output"

                [build.metadata]
                buildInfo = true
                git = true
                reproducible = true

                [compiler]
                encoding = "UTF-16"
                jdkApi = "host"
                args = ["-Xlint:all", "-parameters"]

                [compiler.test]
                jdkApi = "release"
                args = ["-g", "-Xlint:none"]

                [compiler.generated]
                main = "generated/main"
                test = "generated/test"
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        AuthoredBuildConfiguration decoded = decodeBuild(output);
        assertEquals(build, decoded.build().orElseThrow());
        assertEquals(compiler, decoded.compiler().orElseThrow());
    }

    @Test
    void omitsFrozenConventionalDefaultsAndEmptyCollections() {
        AuthoredBuild build = new AuthoredBuild(
                List.of(path("src/main/java")),
                Optional.of(new AuthoredBuild.Output(
                        Optional.of(path("target")),
                        Optional.of(path("classes")),
                        Optional.of(path("test-classes")),
                        Optional.of(path("integration-test-classes")))),
                Optional.of(new AuthoredBuild.Metadata(
                        Optional.of(false), Optional.of(false), Optional.of(false))));
        AuthoredCompiler compiler = new AuthoredCompiler(
                Optional.of("UTF-8"),
                Optional.of(AuthoredCompiler.JdkApiMode.RELEASE),
                List.of(),
                Optional.of(new AuthoredCompiler.Test(
                        Optional.of(AuthoredCompiler.JdkApiMode.RELEASE), List.of())),
                Optional.empty());

        assertEquals("", write(Optional.of(build), Optional.of(compiler)));
        assertEquals("", write(Optional.empty(), Optional.empty()));
    }

    @Test
    void roundTripsTheSemanticNormalizationWithoutDroppingMeaningfulEvidence() {
        AuthoredBuild build = new AuthoredBuild(
                List.of(path("src/main/java")),
                Optional.of(new AuthoredBuild.Output(
                        Optional.of(path("target")),
                        Optional.of(path("classes")),
                        Optional.of(path("custom-test")),
                        Optional.empty())),
                Optional.of(new AuthoredBuild.Metadata(
                        Optional.of(false), Optional.of(true), Optional.empty())));
        AuthoredCompiler compiler = new AuthoredCompiler(
                Optional.of("UTF-8"),
                Optional.of(AuthoredCompiler.JdkApiMode.RELEASE),
                List.of(),
                Optional.of(new AuthoredCompiler.Test(
                        Optional.of(AuthoredCompiler.JdkApiMode.RELEASE),
                        List.of("-parameters"))),
                Optional.of(new AuthoredCompiler.Generated(
                        Optional.of(path("generated/sources/annotations")),
                        Optional.empty())));

        String output = write(Optional.of(build), Optional.of(compiler));

        assertEquals(
                """
                [build.output]
                test = "custom-test"

                [build.metadata]
                git = true

                [compiler.test]
                args = ["-parameters"]

                [compiler.generated]
                main = "generated/sources/annotations"
                """,
                output);
        AuthoredBuildConfiguration decoded = decodeBuild(output);
        assertEquals(
                new AuthoredBuild(
                        List.of(),
                        Optional.of(new AuthoredBuild.Output(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(path("custom-test")),
                                Optional.empty())),
                        Optional.of(new AuthoredBuild.Metadata(
                                Optional.empty(), Optional.of(true), Optional.empty()))),
                decoded.build().orElseThrow());
        assertEquals(
                new AuthoredCompiler(
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        Optional.of(new AuthoredCompiler.Test(
                                Optional.empty(), List.of("-parameters"))),
                        Optional.of(new AuthoredCompiler.Generated(
                                Optional.of(path("generated/sources/annotations")),
                                Optional.empty()))),
                decoded.compiler().orElseThrow());
        assertFalse(Toml.parse(output).hasErrors());
    }

    @Test
    void retainsAReleaseTestOverrideWhenTheMainCompilerUsesTheHostApi() {
        AuthoredCompiler compiler = new AuthoredCompiler(
                Optional.empty(),
                Optional.of(AuthoredCompiler.JdkApiMode.HOST),
                List.of(),
                Optional.of(new AuthoredCompiler.Test(
                        Optional.of(AuthoredCompiler.JdkApiMode.RELEASE), List.of())),
                Optional.empty());

        String output = write(Optional.empty(), Optional.of(compiler));

        assertEquals(
                """
                [compiler]
                jdkApi = "host"

                [compiler.test]
                jdkApi = "release"
                """,
                output);
        assertEquals(compiler, decodeBuild(output).compiler().orElseThrow());
    }

    private static String write(
            Optional<AuthoredBuild> build, Optional<AuthoredCompiler> compiler) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestBuildCompilerWriter().write(emitter, build, compiler);
        return emitter.finish();
    }

    private static AuthoredBuildConfiguration decodeBuild(String source) {
        return decodeAuthoredManifest(
                        "[project]\nname = \"round-trip\"\n\n" + source)
                .build();
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }
}
