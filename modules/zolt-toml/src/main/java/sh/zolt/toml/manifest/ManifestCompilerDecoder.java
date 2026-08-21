package sh.zolt.toml.manifest;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.toml.schema.FinalManifestCompilerFields;
import sh.zolt.toml.schema.ManifestField;

/** Decodes authored compiler settings without applying defaults or inheritance. */
final class ManifestCompilerDecoder {
    private static final List<ManifestField> COMPILER_FIELDS = List.of(
            FinalManifestCompilerFields.COMPILER_ENCODING,
            FinalManifestCompilerFields.COMPILER_JDK_API,
            FinalManifestCompilerFields.COMPILER_ARGS,
            FinalManifestCompilerFields.COMPILER_TEST_JDK_API,
            FinalManifestCompilerFields.COMPILER_TEST_ARGS,
            FinalManifestCompilerFields.COMPILER_GENERATED_MAIN,
            FinalManifestCompilerFields.COMPILER_GENERATED_TEST);

    Optional<AuthoredCompiler> decode(
            ManifestDecodeIndex index,
            CompilerPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored compiler presence observer is required.");
        Optional<ValidatedManifestField> encodingField = index.field(
                FinalManifestCompilerFields.COMPILER_ENCODING);
        Optional<ValidatedManifestField> jdkApiField = index.field(
                FinalManifestCompilerFields.COMPILER_JDK_API);
        Optional<ValidatedManifestField> argsField = index.field(
                FinalManifestCompilerFields.COMPILER_ARGS);
        Optional<ValidatedManifestField> testJdkApiField = index.field(
                FinalManifestCompilerFields.COMPILER_TEST_JDK_API);
        Optional<ValidatedManifestField> testArgsField = index.field(
                FinalManifestCompilerFields.COMPILER_TEST_ARGS);
        Optional<ValidatedManifestField> generatedMainField = index.field(
                FinalManifestCompilerFields.COMPILER_GENERATED_MAIN);
        Optional<ValidatedManifestField> generatedTestField = index.field(
                FinalManifestCompilerFields.COMPILER_GENERATED_TEST);
        if (COMPILER_FIELDS.stream().map(index::field).allMatch(Optional::isEmpty)) {
            return Optional.empty();
        }

        Presence presence = new Presence(argsField, observer);
        Optional<String> encoding = encodingField.map(ManifestTomlValues::string);
        encodingField.ifPresent(field -> presence.direct(
                field,
                () -> compiler(
                        encoding, Optional.empty(), List.of(), Optional.empty(), Optional.empty())));
        Optional<AuthoredCompiler.JdkApiMode> jdkApi = jdkApiField.map(
                ManifestCompilerDecoder::jdkApi);
        jdkApiField.ifPresent(field -> presence.direct(
                field,
                () -> compiler(
                        encoding, jdkApi, List.of(), Optional.empty(), Optional.empty())));
        List<String> args = argsField
                .map(field -> mainArguments(field, encoding, jdkApi, presence))
                .orElseGet(List::of);
        boolean deferEmptyTest = shouldDeferEmptyTest(
                encoding,
                jdkApi,
                argsField,
                args,
                testJdkApiField,
                testArgsField,
                generatedMainField,
                generatedTestField);
        Optional<AuthoredCompiler.Test> test = test(
                testJdkApiField,
                testArgsField,
                deferEmptyTest,
                encoding,
                jdkApi,
                args,
                presence);
        Optional<AuthoredCompiler.Generated> generated = generated(
                generatedMainField,
                generatedTestField,
                encoding,
                jdkApi,
                args,
                test,
                presence);
        ValidatedManifestField anchor = firstPresent(index, COMPILER_FIELDS);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredCompiler(encoding, jdkApi, args, test, generated)));
    }

    private static AuthoredCompiler.JdkApiMode jdkApi(ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        return Arrays.stream(AuthoredCompiler.JdkApiMode.values())
                .filter(mode -> mode.configValue().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Final manifest schema accepted symbol `" + value + "` for `"
                                + field.path()
                                + "` but the authored model does not recognize it."));
    }

    private static List<String> mainArguments(
            ValidatedManifestField field,
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> jdkApi,
            Presence presence) {
        List<String> values = ManifestTomlValues.strings(field);
        for (int index = 0; index < values.size(); index++) {
            List<String> prefix = values.subList(0, index + 1);
            int diagnosticIndex = index;
            presence.indexed(
                    field,
                    diagnosticIndex,
                    () -> compiler(
                            encoding, jdkApi, prefix, Optional.empty(), Optional.empty()));
        }
        return values;
    }

    private static Optional<AuthoredCompiler.Test> test(
            Optional<ValidatedManifestField> jdkApiField,
            Optional<ValidatedManifestField> argsField,
            boolean deferEmptyTest,
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> mainJdkApi,
            List<String> mainArgs,
            Presence presence) {
        if (jdkApiField.isEmpty() && argsField.isEmpty()) {
            return Optional.empty();
        }
        Optional<AuthoredCompiler.JdkApiMode> jdkApi = jdkApiField.map(
                ManifestCompilerDecoder::jdkApi);
        if (jdkApiField.isPresent()) {
            ValidatedManifestField field = jdkApiField.orElseThrow();
            AuthoredCompiler.Test partial = ManifestSemanticDiagnostics.construct(
                    field, () -> new AuthoredCompiler.Test(jdkApi, List.of()));
            presence.afterMainArgs(
                    field,
                    () -> compiler(
                            encoding,
                            mainJdkApi,
                            mainArgs,
                            Optional.of(partial),
                            Optional.empty()));
        }
        List<String> args = argsField.map(ManifestTomlValues::strings).orElseGet(List::of);
        for (int index = 0; index < args.size(); index++) {
            List<String> prefix = args.subList(0, index + 1);
            int diagnosticIndex = index;
            AuthoredCompiler.Test partial = ManifestSemanticDiagnostics.construct(
                    argsField.orElseThrow(),
                    diagnosticIndex,
                    () -> new AuthoredCompiler.Test(jdkApi, prefix));
            presence.afterMainArgs(
                    argsField.orElseThrow(),
                    diagnosticIndex,
                    () -> compiler(
                            encoding,
                            mainJdkApi,
                            mainArgs,
                            Optional.of(partial),
                            Optional.empty()));
        }
        if (deferEmptyTest) {
            return Optional.empty();
        }
        ValidatedManifestField anchor = jdkApiField.or(() -> argsField).orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredCompiler.Test(jdkApi, args)));
    }

    private static boolean shouldDeferEmptyTest(
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> jdkApi,
            Optional<ValidatedManifestField> argsField,
            List<String> args,
            Optional<ValidatedManifestField> testJdkApiField,
            Optional<ValidatedManifestField> testArgsField,
            Optional<ValidatedManifestField> generatedMainField,
            Optional<ValidatedManifestField> generatedTestField) {
        return encoding.isEmpty()
                && jdkApi.isEmpty()
                && argsField.isPresent()
                && args.isEmpty()
                && testJdkApiField.isEmpty()
                && testArgsField.isPresent()
                && ManifestTomlValues.strings(testArgsField.orElseThrow()).isEmpty()
                && generatedMainField.isEmpty()
                && generatedTestField.isEmpty();
    }

    private static Optional<AuthoredCompiler.Generated> generated(
            Optional<ValidatedManifestField> mainField,
            Optional<ValidatedManifestField> testField,
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> jdkApi,
            List<String> args,
            Optional<AuthoredCompiler.Test> testSettings,
            Presence presence) {
        if (mainField.isEmpty() && testField.isEmpty()) {
            return Optional.empty();
        }
        Optional<ManifestRelativePath> main = mainField.map(ManifestCompilerDecoder::path);
        if (mainField.isPresent()) {
            ValidatedManifestField field = mainField.orElseThrow();
            AuthoredCompiler.Generated partial = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredCompiler.Generated(main, Optional.empty()));
            presence.afterMainArgs(
                    field,
                    () -> compiler(
                            encoding, jdkApi, args, testSettings, Optional.of(partial)));
        }
        Optional<ManifestRelativePath> testPath = testField.map(ManifestCompilerDecoder::path);
        if (testField.isPresent()) {
            ValidatedManifestField field = testField.orElseThrow();
            AuthoredCompiler.Generated partial = ManifestSemanticDiagnostics.construct(
                    field, () -> new AuthoredCompiler.Generated(main, testPath));
            presence.afterMainArgs(
                    field,
                    () -> compiler(
                            encoding, jdkApi, args, testSettings, Optional.of(partial)));
        }
        ValidatedManifestField anchor = mainField.or(() -> testField).orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredCompiler.Generated(main, testPath)));
    }

    private static ManifestRelativePath path(ValidatedManifestField field) {
        return ManifestSemanticDiagnostics.construct(
                field, () -> new ManifestRelativePath(ManifestTomlValues.string(field)));
    }

    private static AuthoredCompiler compiler(
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> jdkApi,
            List<String> args,
            Optional<AuthoredCompiler.Test> test,
            Optional<AuthoredCompiler.Generated> generated) {
        return new AuthoredCompiler(encoding, jdkApi, args, test, generated);
    }

    private static final class Presence {
        private final Optional<ValidatedManifestField> argsField;
        private final CompilerPresenceObserver observer;
        private boolean observed;

        private Presence(
                Optional<ValidatedManifestField> argsField,
                CompilerPresenceObserver observer) {
            this.argsField = argsField;
            this.observer = observer;
        }

        private void direct(ValidatedManifestField field, Supplier<AuthoredCompiler> factory) {
            ManifestSemanticDiagnostics.construct(
                    field, () -> observe(factory.get()));
        }

        private void indexed(
                ValidatedManifestField field,
                int index,
                Supplier<AuthoredCompiler> factory) {
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> observe(factory.get()));
        }

        private void afterMainArgs(
                ValidatedManifestField field,
                Supplier<AuthoredCompiler> factory) {
            if (!observed && argsField.isPresent()) {
                direct(argsField.orElseThrow(), factory);
            } else {
                direct(field, factory);
            }
        }

        private void afterMainArgs(
                ValidatedManifestField field,
                int index,
                Supplier<AuthoredCompiler> factory) {
            if (!observed && argsField.isPresent()) {
                direct(argsField.orElseThrow(), factory);
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

    @FunctionalInterface
    interface CompilerPresenceObserver {
        void present(AuthoredCompiler compiler);
    }

    private static ValidatedManifestField firstPresent(
            ManifestDecodeIndex index,
            List<ManifestField> handles) {
        return handles.stream()
                .map(index::field)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Authored compiler aggregate has no direct field evidence."));
    }
}
