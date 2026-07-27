package sh.zolt.build.incremental;

final class IncrementalCompileStateFormatter {
    String format(IncrementalCompileState state) {
        StringBuilder content = new StringBuilder();
        line(content, "version", IncrementalCompileStateEncoding.VERSION);
        line(content, "scope", state.scope());
        encodedLine(content, "projectDirectory", state.projectDirectory().toString());
        encodedLine(content, "outputDirectory", state.outputDirectory().toString());
        encodedLine(content, "generatedSourcesDirectory", state.generatedSourcesDirectory().toString());
        line(content, "compilerSettingsHash", state.compilerSettingsHash());
        line(content, "buildFingerprintSha256", state.buildFingerprintSha256());
        line(content, "processorAttributionComplete", Boolean.toString(state.processorAttributionComplete()));
        state.fallbackReasons().forEach(reason -> encodedRecord(content, "fallbackReason", reason));
        state.sourceRoots().forEach(root -> encodedRecord(content, "sourceRoot", root));
        state.generatedSourceRoots().forEach(root -> encodedRecord(content, "generatedSourceRoot", root));
        state.compileClasspath().forEach(entry -> encodedRecord(
                content,
                "compileClasspath",
                entry.path().toString(),
                Long.toString(entry.size()),
                Long.toString(entry.lastModifiedNanos()),
                entry.hash()));
        state.processorClasspath().forEach(entry -> encodedRecord(
                content,
                "processorClasspath",
                entry.path().toString(),
                Long.toString(entry.size()),
                Long.toString(entry.lastModifiedNanos()),
                entry.hash()));
        for (IncrementalCompileState.SourceRecord source : state.sources()) {
            encodedRecord(
                    content,
                    "source",
                    source.path().toString(),
                    source.sourceRoot().toString(),
                    source.generatedSourceStepId().orElse(""),
                    source.contentHash(),
                    source.packageName());
            source.declaredTypes().forEach(type -> encodedRecord(content, "sourceDeclaredType", source.path().toString(), type));
            source.classOutputs().forEach(output -> encodedRecord(content, "sourceClass", source.path().toString(), output.toString()));
            source.referencedClasses().forEach(reference -> encodedRecord(content, "sourceReference", source.path().toString(), reference));
            source.generatedSources().forEach(generated -> encodedRecord(content, "sourceGeneratedSource", source.path().toString(), generated.toString()));
            source.generatedClasses().forEach(generated -> encodedRecord(content, "sourceGeneratedClass", source.path().toString(), generated.toString()));
            source.generatedResources().forEach(generated -> encodedRecord(content, "sourceGeneratedResource", source.path().toString(), generated.toString()));
        }
        for (IncrementalCompileState.ClassRecord classRecord : state.classes()) {
            encodedRecord(
                    content,
                    "class",
                    classRecord.binaryName(),
                    classRecord.outputPath().toString(),
                    classRecord.classFileHash(),
                    classRecord.abiHash(),
                    classRecord.packagePrivateAbiHash(),
                    Integer.toString(classRecord.accessFlags()),
                    classRecord.superName().orElse(""));
            classRecord.interfaces().forEach(interfaceName -> encodedRecord(
                    content,
                    "classInterface",
                    classRecord.binaryName(),
                    interfaceName));
        }
        state.reverseDependencies().forEach((className, sources) ->
                sources.forEach(source -> encodedRecord(content, "reverseDependency", className, source.toString())));
        return content.toString();
    }

    private static void line(StringBuilder content, String name, String value) {
        content.append(name).append('=').append(value).append('\n');
    }

    private static void encodedLine(StringBuilder content, String name, String value) {
        content.append(name).append('=').append(IncrementalCompileStateEncoding.encode(value)).append('\n');
    }

    private static void encodedRecord(StringBuilder content, String name, String... values) {
        content.append(name);
        for (String value : values) {
            content.append('\t').append(IncrementalCompileStateEncoding.encode(value));
        }
        content.append('\n');
    }
}
