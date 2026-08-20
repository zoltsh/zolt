package sh.zolt.toml.manifest;

import java.util.Objects;
import org.tomlj.TomlParseResult;
import sh.zolt.toml.ZoltConfigException;

/** Package-private pairing of exact source, stable syntax evidence, and Tomlj semantics. */
record ParsedManifestSyntax(
        String source,
        ManifestSyntax syntax,
        TomlParseResult parsed) {
    ParsedManifestSyntax {
        Objects.requireNonNull(source, "Manifest source is required.");
        Objects.requireNonNull(syntax, "Manifest syntax is required.");
        Objects.requireNonNull(parsed, "Parsed TOML is required.");
        if (!syntax.matchesSource(source)) {
            throw new ZoltConfigException(
                    "Manifest source does not match its parsed syntax; shape validation failed closed.");
        }
    }
}
