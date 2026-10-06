package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.kotlin.KotlinCompilerToolRoots;
import sh.zolt.dependency.PackageId;

/** Verifies the executable identity of checksum-verified Kotlin compiler tool JARs. */
final class KotlinCompilerToolJarInspector {
    private static final String REGISTRAR =
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar";
    private static final String COMMAND_LINE_PROCESSOR =
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor";

    private KotlinCompilerToolJarInspector() {
    }

    static void inspectCompiler(String version, Path jar) {
        inspect(
                version,
                jar,
                List.of("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class"),
                "kotlin-compiler-embeddable",
                "compiler");
    }

    static void inspectKapt(String version, Path jar) {
        inspect(
                version,
                jar,
                List.of("org/jetbrains/kotlin/kapt/KaptCommandLineProcessor.class"),
                "kotlin-annotation-processing-embeddable",
                "KAPT plugin");
    }

    static void inspectPlugin(String version, PackageId packageId, Path jar) {
        if (packageId.equals(KotlinCompilerToolRoots.SERIALIZATION)) {
            inspect(
                    version,
                    jar,
                    List.of(REGISTRAR),
                    "kotlinx-serialization-compiler-plugin.embeddable",
                    "serialization compiler plugin");
            return;
        }
        if (packageId.equals(KotlinCompilerToolRoots.ALL_OPEN)) {
            inspect(
                    version,
                    jar,
                    List.of(REGISTRAR, COMMAND_LINE_PROCESSOR),
                    "kotlin-allopen-compiler-plugin.embeddable",
                    "all-open compiler plugin");
            return;
        }
        if (packageId.equals(KotlinCompilerToolRoots.NO_ARG)) {
            inspect(
                    version,
                    jar,
                    List.of(REGISTRAR, COMMAND_LINE_PROCESSOR),
                    "kotlin-noarg-compiler-plugin.embeddable",
                    "JPA no-arg compiler plugin");
            return;
        }
        if (packageId.equals(KotlinCompilerToolRoots.POWER_ASSERT)) {
            inspect(
                    version,
                    jar,
                    List.of(REGISTRAR, COMMAND_LINE_PROCESSOR),
                    "kotlin-power-assert-compiler-plugin.embeddable",
                    "Power-assert compiler plugin");
            return;
        }
        throw KotlinCompilerToolchainResolver.invalid(
                "the selected compiler plugin root is unsupported: " + packageId);
    }

    private static void inspect(
            String configuredVersion,
            Path jarPath,
            List<String> requiredEntries,
            String implementationTitle,
            String label) {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            for (String requiredEntry : requiredEntries) {
                if (jar.getJarEntry(requiredEntry) == null) {
                    throw KotlinCompilerToolchainResolver.invalid(
                            "the selected " + label + " JAR does not contain " + requiredEntry);
                }
            }
            if (jar.getManifest() == null) {
                throw KotlinCompilerToolchainResolver.invalid(
                        "the selected " + label + " JAR has no manifest");
            }
            Attributes attributes = jar.getManifest().getMainAttributes();
            String title = normalize(attributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE));
            if (!implementationTitle.equals(title)) {
                throw KotlinCompilerToolchainResolver.invalid(
                        "the selected " + label + " JAR reports Implementation-Title `"
                                + title + "` instead of `" + implementationTitle + "`");
            }
            String version = normalize(attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
            if (!matchesVersion(configuredVersion, version)) {
                throw KotlinCompilerToolchainResolver.invalid(
                        "the selected " + label + " JAR reports Implementation-Version `"
                                + version + "` which does not match configured version `"
                                + configuredVersion + "`");
            }
        } catch (KotlinCompileException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new KotlinCompileException(
                    "Could not inspect the checksum-verified Kotlin " + label + " JAR at " + jarPath
                            + ". Run `zolt resolve` to refresh the artifact cache, then retry.",
                    exception);
        }
    }

    private static boolean matchesVersion(String configured, String reported) {
        if (configured.equals(reported)) {
            return true;
        }
        String releasePrefix = configured + "-release-";
        return reported.startsWith(releasePrefix) && reported.length() > releasePrefix.length();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
