package sh.zolt.project;

import java.util.List;

public record GeneratedSourceStep(
        String id,
        GeneratedSourceKind kind,
        String language,
        String output,
        List<String> inputs,
        boolean required,
        boolean clean,
        OpenApiGenerationSettings openApi,
        ProtobufGenerationSettings protobuf,
        ExecGenerationSettings exec,
        KspGenerationSettings ksp) {
    public GeneratedSourceStep(
            String id,
            GeneratedSourceKind kind,
            String language,
            String output,
            List<String> inputs,
            boolean required,
            boolean clean) {
        this(id, kind, language, output, inputs, required, clean, OpenApiGenerationSettings.empty());
    }

    public GeneratedSourceStep(
            String id,
            GeneratedSourceKind kind,
            String language,
            String output,
            List<String> inputs,
            boolean required,
            boolean clean,
            OpenApiGenerationSettings openApi) {
        this(id, kind, language, output, inputs, required, clean, openApi, ProtobufGenerationSettings.empty());
    }

    public GeneratedSourceStep(
            String id,
            GeneratedSourceKind kind,
            String language,
            String output,
            List<String> inputs,
            boolean required,
            boolean clean,
            OpenApiGenerationSettings openApi,
            ProtobufGenerationSettings protobuf) {
        this(id, kind, language, output, inputs, required, clean, openApi, protobuf, ExecGenerationSettings.empty());
    }

    public GeneratedSourceStep(
            String id,
            GeneratedSourceKind kind,
            String language,
            String output,
            List<String> inputs,
            boolean required,
            boolean clean,
            OpenApiGenerationSettings openApi,
            ProtobufGenerationSettings protobuf,
            ExecGenerationSettings exec) {
        this(id, kind, language, output, inputs, required, clean, openApi, protobuf, exec,
                KspGenerationSettings.empty());
    }

    public GeneratedSourceStep {
        id = requireNonBlank(id, "Generated source step id");
        kind = kind == null ? GeneratedSourceKind.DECLARED_ROOT : kind;
        language = requireNonBlank(language, "Generated source language");
        output = requireNonBlank(output, "Generated source output");
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        for (String input : inputs) {
            requireNonBlank(input, "Generated source input");
        }
        openApi = openApi == null ? OpenApiGenerationSettings.empty() : openApi;
        protobuf = protobuf == null ? ProtobufGenerationSettings.empty() : protobuf;
        exec = exec == null ? ExecGenerationSettings.empty() : exec;
        ksp = ksp == null ? KspGenerationSettings.empty() : ksp;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string.");
        }
        return value;
    }
}
