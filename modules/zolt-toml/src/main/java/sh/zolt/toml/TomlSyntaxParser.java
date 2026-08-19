package sh.zolt.toml;

import static org.tomlj.TomlVersion.V1_0_0;

import java.util.Objects;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

/** Parses TOML 1.0 and captures the exact source ranges needed by manifest tooling. */
public final class TomlSyntaxParser {
    public ManifestSyntax parse(String source) {
        Objects.requireNonNull(source, "source");
        TomlParseResult parsed = Toml.parse(source, V1_0_0);
        if (parsed.hasErrors()) {
            TomlParseError firstError = parsed.errors().getFirst();
            throw new ZoltConfigException("Could not parse zolt.toml. Fix the TOML syntax near "
                    + firstError.position()
                    + ": "
                    + firstError.getMessage());
        }

        try {
            TomlSourceScanner.Result syntax = new TomlSourceScanner(source).scan();
            return new ManifestSyntax(syntax.tables(), syntax.assignments(), parsed);
        } catch (TomlSourceScanner.ScanException exception) {
            throw new ZoltConfigException(
                    "Could not determine safe source spans in zolt.toml near UTF-16 offset "
                            + exception.offset()
                            + ". No source edits are safe: "
                            + exception.getMessage());
        }
    }
}
