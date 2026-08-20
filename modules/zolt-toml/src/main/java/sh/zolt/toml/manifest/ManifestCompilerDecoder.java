package sh.zolt.toml.manifest;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    Optional<AuthoredCompiler> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
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

        Optional<String> encoding = encodingField.map(ManifestCompilerDecoder::encoding);
        Optional<AuthoredCompiler.JdkApiMode> jdkApi = jdkApiField.map(
                ManifestCompilerDecoder::jdkApi);
        List<String> args = argsField
                .map(field -> mainArguments(field, encoding, jdkApi))
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
                testJdkApiField, testArgsField, deferEmptyTest);
        Optional<AuthoredCompiler.Generated> generated = generated(
                generatedMainField, generatedTestField);
        ValidatedManifestField anchor = firstPresent(index, COMPILER_FIELDS);
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredCompiler(encoding, jdkApi, args, test, generated)));
    }

    private static String encoding(ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        ManifestSemanticDiagnostics.construct(
                field,
                () -> new AuthoredCompiler(
                        Optional.of(value),
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        Optional.empty()));
        return value;
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
            Optional<AuthoredCompiler.JdkApiMode> jdkApi) {
        List<String> values = ManifestTomlValues.strings(field);
        for (int index = 0; index < values.size(); index++) {
            List<String> prefix = values.subList(0, index + 1);
            int diagnosticIndex = index;
            ManifestSemanticDiagnostics.construct(
                    field,
                    diagnosticIndex,
                    () -> new AuthoredCompiler(
                            encoding,
                            jdkApi,
                            prefix,
                            Optional.empty(),
                            Optional.empty()));
        }
        return values;
    }

    private static Optional<AuthoredCompiler.Test> test(
            Optional<ValidatedManifestField> jdkApiField,
            Optional<ValidatedManifestField> argsField,
            boolean deferEmptyTest) {
        if (jdkApiField.isEmpty() && argsField.isEmpty()) {
            return Optional.empty();
        }
        Optional<AuthoredCompiler.JdkApiMode> jdkApi = jdkApiField.map(
                ManifestCompilerDecoder::jdkApi);
        List<String> args = argsField.map(ManifestTomlValues::strings).orElseGet(List::of);
        for (int index = 0; index < args.size(); index++) {
            List<String> prefix = args.subList(0, index + 1);
            int diagnosticIndex = index;
            ManifestSemanticDiagnostics.construct(
                    argsField.orElseThrow(),
                    diagnosticIndex,
                    () -> new AuthoredCompiler.Test(jdkApi, prefix));
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
            Optional<ValidatedManifestField> testField) {
        if (mainField.isEmpty() && testField.isEmpty()) {
            return Optional.empty();
        }
        Optional<ManifestRelativePath> main = mainField.map(ManifestCompilerDecoder::path);
        Optional<ManifestRelativePath> test = testField.map(ManifestCompilerDecoder::path);
        ValidatedManifestField anchor = mainField.or(() -> testField).orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredCompiler.Generated(main, test)));
    }

    private static ManifestRelativePath path(ValidatedManifestField field) {
        return ManifestSemanticDiagnostics.construct(
                field, () -> new ManifestRelativePath(ManifestTomlValues.string(field)));
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
