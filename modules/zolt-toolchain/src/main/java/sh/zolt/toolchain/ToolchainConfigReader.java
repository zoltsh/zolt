package sh.zolt.toolchain;

import sh.zolt.error.ActionableError;
import sh.zolt.toml.ToolchainSectionCodec;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

public final class ToolchainConfigReader {
    public Optional<JavaToolchainRequest> readJava(Path configPath) {
        Path normalized = configPath.toAbsolutePath().normalize();
        try {
            return readJava(Toml.parse(normalized), normalized);
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not read zolt.toml at " + normalized + ".",
                    "Check that the file exists and is readable.",
                    exception));
        }
    }

    public Optional<JavaToolchainRequest> readJava(String content) {
        return readJava(Toml.parse(content), Path.of("zolt.toml"));
    }

    /**
     * Reads the optional {@code [toolchain.java.test]} scoped runtime toolchain, which pins the JDK
     * used to run tests (compile stays on {@code [toolchain.java]}). Distribution/features/policy
     * default from the main entry; returns empty when no test runtime toolchain is declared.
     */
    public Optional<JavaToolchainRequest> readJavaTest(Path configPath) {
        Path normalized = configPath.toAbsolutePath().normalize();
        try {
            TomlParseResult result = Toml.parse(normalized);
            if (result.hasErrors()) {
                throw new ZoltConfigException(parseErrorMessage(result, normalized));
            }
            return ToolchainSectionCodec.parseJavaTestToolchain(
                    result,
                    "zolt.toml",
                    ToolchainSectionCodec.parseJavaToolchain(result, "zolt.toml").orElse(null));
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not read zolt.toml at " + normalized + ".",
                    "Check that the file exists and is readable.",
                    exception));
        }
    }

    private static Optional<JavaToolchainRequest> readJava(
            TomlParseResult result,
            Path configPath) {
        if (result.hasErrors()) {
            throw new ZoltConfigException(parseErrorMessage(result, configPath));
        }
        return ToolchainSectionCodec.parseJavaToolchain(result, "zolt.toml");
    }

    private static String parseErrorMessage(TomlParseResult result, Path configPath) {
        TomlParseError firstError = result.errors().getFirst();
        return "Could not parse zolt.toml at "
                + configPath
                + ". Fix the TOML syntax near "
                + firstError.position()
                + ": "
                + firstError.getMessage();
    }
}
