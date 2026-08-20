package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.List;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.ManifestSchemaRegistry;

/** Shared state for one fail-closed manifest-shape validation pass. */
final class ManifestShapeValidationContext {
    final String source;
    final ManifestSyntax syntax;
    final ManifestSchemaNavigator navigator;
    final ManifestShapeDiagnostics diagnostics = new ManifestShapeDiagnostics();
    final ManifestShapeSourceResolver sources;
    final ManifestShapeFieldValidator fieldValidator;
    final List<ValidatedManifestSection> sections = new ArrayList<>();
    final List<ValidatedManifestField> fields = new ArrayList<>();

    ManifestShapeValidationContext(
            String source,
            ManifestSyntax syntax,
            ManifestSchemaRegistry registry) {
        if (!syntax.matchesSource(source)) {
            throw new ZoltConfigException(
                    "Manifest source does not match its parsed syntax; shape validation failed closed.");
        }
        this.source = source;
        this.syntax = syntax;
        navigator = new ManifestSchemaNavigator(registry);
        sources = new ManifestShapeSourceResolver(syntax, source.length());
        fieldValidator = new ManifestShapeFieldValidator(
                registry.symbols(), navigator, diagnostics);
    }
}
