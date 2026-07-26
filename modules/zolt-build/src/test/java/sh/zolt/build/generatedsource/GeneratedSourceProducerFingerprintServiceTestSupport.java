package sh.zolt.build.generatedsource;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Cross-package test seam for exercising the production producer identity with controlled ambient
 * environment and process-probe output.
 */
public final class GeneratedSourceProducerFingerprintServiceTestSupport {
    private GeneratedSourceProducerFingerprintServiceTestSupport() {
    }

    public static GeneratedSourceProducerFingerprintService service(
            Map<String, String> environment,
            Supplier<String> probedVersion) {
        return new GeneratedSourceProducerFingerprintService(
                java.io.File.pathSeparator,
                (command, directory, processEnvironment, timeout) ->
                        new ExecGeneratedSourceService.ProcessResult(
                                0,
                                probedVersion.get(),
                                false),
                environment::get);
    }
}
