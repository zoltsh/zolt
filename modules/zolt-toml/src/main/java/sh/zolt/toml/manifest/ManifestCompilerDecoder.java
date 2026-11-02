package sh.zolt.toml.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.toml.schema.FinalManifestCompilerFields;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Decodes authored compiler settings without applying defaults or inheritance. */
final class ManifestCompilerDecoder {
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
        Optional<ValidatedManifestField> kotlinModuleField = index.field(
                FinalManifestCompilerFields.COMPILER_KOTLIN_MODULE);
        Optional<ValidatedManifestField> testJdkApiField = index.field(
                FinalManifestCompilerFields.COMPILER_TEST_JDK_API);
        Optional<ValidatedManifestField> testArgsField = index.field(
                FinalManifestCompilerFields.COMPILER_TEST_ARGS);
        Optional<ValidatedManifestField> testKotlinModuleField = index.field(
                FinalManifestCompilerFields.COMPILER_TEST_KOTLIN_MODULE);
        Optional<ValidatedManifestField> generatedMainField = index.field(
                FinalManifestCompilerFields.COMPILER_GENERATED_MAIN);
        Optional<ValidatedManifestField> generatedTestField = index.field(
                FinalManifestCompilerFields.COMPILER_GENERATED_TEST);
        Optional<ValidatedManifestField> firstField = index.firstDirectField(
                FinalManifestPaths.COMPILER,
                FinalManifestPaths.COMPILER_TEST,
                FinalManifestPaths.COMPILER_GENERATED);
        if (firstField.isEmpty()) {
            return Optional.empty();
        }

        ManifestCompilerPresence presence = new ManifestCompilerPresence(argsField, observer);
        Optional<String> encoding = encodingField.map(ManifestTomlValues::string);
        encodingField.ifPresent(field -> presence.direct(
                field,
                () -> compiler(
                        encoding, Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty())));
        Optional<AuthoredCompiler.JdkApiMode> jdkApi = jdkApiField.map(
                ManifestCompilerDecoder::jdkApi);
        jdkApiField.ifPresent(field -> presence.direct(
                field,
                () -> compiler(
                        encoding, jdkApi, List.of(), Optional.empty(), Optional.empty(), Optional.empty())));
        List<String> args = argsField
                .map(field -> mainArguments(field, encoding, jdkApi, presence))
                .orElseGet(List::of);
        Optional<String> kotlinModule = kotlinModuleField.map(ManifestTomlValues::string);
        kotlinModuleField.ifPresent(field -> presence.afterMainArgs(
                field,
                () -> compiler(
                        encoding, jdkApi, args, kotlinModule, Optional.empty(), Optional.empty())));
        boolean deferEmptyTest = shouldDeferEmptyTest(
                encoding,
                jdkApi,
                argsField,
                args,
                kotlinModule,
                testJdkApiField,
                testArgsField,
                testKotlinModuleField,
                generatedMainField,
                generatedTestField);
        Optional<AuthoredCompiler.Test> test = test(
                index,
                testJdkApiField,
                testArgsField,
                testKotlinModuleField,
                deferEmptyTest,
                encoding,
                jdkApi,
                args,
                kotlinModule,
                presence);
        Optional<AuthoredCompiler.Generated> generated = generated(
                index,
                generatedMainField,
                generatedTestField,
                encoding,
                jdkApi,
                args,
                kotlinModule,
                test,
                presence);
        ValidatedManifestField anchor = firstField.orElseThrow(() ->
                new IllegalStateException(
                        "Authored compiler aggregate has no direct field evidence."));
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredCompiler(encoding, jdkApi, args, kotlinModule, test, generated)));
    }

    private static AuthoredCompiler.JdkApiMode jdkApi(ValidatedManifestField field) {
        return ManifestAuthoredSymbols.authored(
                field,
                ManifestTomlValues.string(field),
                AuthoredCompiler.JdkApiMode.values(),
                AuthoredCompiler.JdkApiMode::configValue);
    }

    private static List<String> mainArguments(
            ValidatedManifestField field,
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> jdkApi,
            ManifestCompilerPresence presence) {
        List<String> values = ManifestTomlValues.strings(field);
        for (int index = 0; index < values.size(); index++) {
            List<String> prefix = values.subList(0, index + 1);
            int diagnosticIndex = index;
            presence.indexed(
                    field,
                    diagnosticIndex,
                    () -> compiler(
                            encoding, jdkApi, prefix, Optional.empty(), Optional.empty(), Optional.empty()));
        }
        return values;
    }

    private static Optional<AuthoredCompiler.Test> test(
            ManifestDecodeIndex decodeIndex,
            Optional<ValidatedManifestField> jdkApiField,
            Optional<ValidatedManifestField> argsField,
            Optional<ValidatedManifestField> kotlinModuleField,
            boolean deferEmptyTest,
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> mainJdkApi,
            List<String> mainArgs,
            Optional<String> mainKotlinModule,
            ManifestCompilerPresence presence) {
        if (jdkApiField.isEmpty() && argsField.isEmpty() && kotlinModuleField.isEmpty()) {
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
                            mainKotlinModule,
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
                            mainKotlinModule,
                            Optional.of(partial),
                            Optional.empty()));
        }
        Optional<String> kotlinModule = kotlinModuleField.map(ManifestTomlValues::string);
        if (kotlinModuleField.isPresent()) {
            ValidatedManifestField field = kotlinModuleField.orElseThrow();
            AuthoredCompiler.Test partial = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredCompiler.Test(jdkApi, args, kotlinModule));
            presence.afterArguments(
                    argsField,
                    field,
                    () -> compiler(
                            encoding,
                            mainJdkApi,
                            mainArgs,
                            mainKotlinModule,
                            Optional.of(partial),
                            Optional.empty()));
        }
        if (deferEmptyTest) {
            return Optional.empty();
        }
        return Optional.of(ManifestSemanticDiagnostics.construct(
                decodeIndex.firstDirectField(FinalManifestPaths.COMPILER_TEST)
                        .orElseThrow(),
                () -> new AuthoredCompiler.Test(jdkApi, args, kotlinModule)));
    }

    private static boolean shouldDeferEmptyTest(
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> jdkApi,
            Optional<ValidatedManifestField> argsField,
            List<String> args,
            Optional<String> kotlinModule,
            Optional<ValidatedManifestField> testJdkApiField,
            Optional<ValidatedManifestField> testArgsField,
            Optional<ValidatedManifestField> testKotlinModuleField,
            Optional<ValidatedManifestField> generatedMainField,
            Optional<ValidatedManifestField> generatedTestField) {
        return encoding.isEmpty()
                && jdkApi.isEmpty()
                && argsField.isPresent()
                && args.isEmpty()
                && kotlinModule.isEmpty()
                && testJdkApiField.isEmpty()
                && testArgsField.isPresent()
                && ManifestTomlValues.strings(testArgsField.orElseThrow()).isEmpty()
                && testKotlinModuleField.isEmpty()
                && generatedMainField.isEmpty()
                && generatedTestField.isEmpty();
    }

    private static Optional<AuthoredCompiler.Generated> generated(
            ManifestDecodeIndex decodeIndex,
            Optional<ValidatedManifestField> mainField,
            Optional<ValidatedManifestField> testField,
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> jdkApi,
            List<String> args,
            Optional<String> kotlinModule,
            Optional<AuthoredCompiler.Test> testSettings,
            ManifestCompilerPresence presence) {
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
                            encoding, jdkApi, args, kotlinModule, testSettings, Optional.of(partial)));
        }
        Optional<ManifestRelativePath> testPath = testField.map(ManifestCompilerDecoder::path);
        if (testField.isPresent()) {
            ValidatedManifestField field = testField.orElseThrow();
            AuthoredCompiler.Generated partial = ManifestSemanticDiagnostics.construct(
                    field, () -> new AuthoredCompiler.Generated(main, testPath));
            presence.afterMainArgs(
                    field,
                    () -> compiler(
                            encoding, jdkApi, args, kotlinModule, testSettings, Optional.of(partial)));
        }
        return Optional.of(ManifestSemanticDiagnostics.construct(
                decodeIndex.firstDirectField(FinalManifestPaths.COMPILER_GENERATED)
                        .orElseThrow(),
                () -> new AuthoredCompiler.Generated(main, testPath)));
    }

    private static ManifestRelativePath path(ValidatedManifestField field) {
        return ManifestSemanticDiagnostics.construct(
                field, () -> new ManifestRelativePath(ManifestTomlValues.string(field)));
    }

    private static AuthoredCompiler compiler(
            Optional<String> encoding,
            Optional<AuthoredCompiler.JdkApiMode> jdkApi,
            List<String> args,
            Optional<String> kotlinModule,
            Optional<AuthoredCompiler.Test> test,
            Optional<AuthoredCompiler.Generated> generated) {
        return new AuthoredCompiler(encoding, jdkApi, args, kotlinModule, test, generated);
    }

    @FunctionalInterface
    interface CompilerPresenceObserver {
        void present(AuthoredCompiler compiler);
    }

}
