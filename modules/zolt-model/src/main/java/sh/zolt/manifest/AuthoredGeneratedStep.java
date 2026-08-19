package sh.zolt.manifest;

/** A typed generated-source producer or declared root from one scope collection. */
public sealed interface AuthoredGeneratedStep
        permits AuthoredOpenApiStep,
                AuthoredProtobufStep,
                AuthoredExecStep,
                AuthoredDeclaredRootStep {
    GeneratedStepSettings settings();
}
