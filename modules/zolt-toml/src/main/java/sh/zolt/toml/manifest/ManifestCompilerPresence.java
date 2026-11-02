package sh.zolt.toml.manifest;

import java.util.Optional;
import java.util.function.Supplier;
import sh.zolt.manifest.authored.AuthoredCompiler;

/** Anchors compiler aggregate presence to the first canonical semantic field. */
final class ManifestCompilerPresence {
    private final Optional<ValidatedManifestField> mainArgsField;
    private final ManifestCompilerDecoder.CompilerPresenceObserver observer;
    private boolean observed;

    ManifestCompilerPresence(
            Optional<ValidatedManifestField> mainArgsField,
            ManifestCompilerDecoder.CompilerPresenceObserver observer) {
        this.mainArgsField = mainArgsField;
        this.observer = observer;
    }

    void direct(ValidatedManifestField field, Supplier<AuthoredCompiler> factory) {
        ManifestSemanticDiagnostics.construct(field, () -> observe(factory.get()));
    }

    void indexed(
            ValidatedManifestField field,
            int index,
            Supplier<AuthoredCompiler> factory) {
        ManifestSemanticDiagnostics.construct(field, index, () -> observe(factory.get()));
    }

    void afterMainArgs(
            ValidatedManifestField field,
            Supplier<AuthoredCompiler> factory) {
        if (!observed && mainArgsField.isPresent()) {
            direct(mainArgsField.orElseThrow(), factory);
        } else {
            direct(field, factory);
        }
    }

    void afterArguments(
            Optional<ValidatedManifestField> localArgsField,
            ValidatedManifestField field,
            Supplier<AuthoredCompiler> factory) {
        if (!observed && mainArgsField.isPresent()) {
            direct(mainArgsField.orElseThrow(), factory);
        } else if (!observed && localArgsField.isPresent()) {
            direct(localArgsField.orElseThrow(), factory);
        } else {
            direct(field, factory);
        }
    }

    void afterMainArgs(
            ValidatedManifestField field,
            int index,
            Supplier<AuthoredCompiler> factory) {
        if (!observed && mainArgsField.isPresent()) {
            direct(mainArgsField.orElseThrow(), factory);
        } else {
            indexed(field, index, factory);
        }
    }

    private AuthoredCompiler observe(AuthoredCompiler compiler) {
        if (!observed) {
            observer.present(compiler);
            observed = true;
        }
        return compiler;
    }
}
