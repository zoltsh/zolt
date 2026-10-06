package sh.zolt.build.generatedsource.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.zolt.build.BuildException;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProtobufGenerationSettings;

final class KspProducerFingerprintServiceTest {
    @Test
    void fingerprintsTheSeparatedChecksumDerivedToolchain() {
        AtomicReference<List<String>> arguments = new AtomicReference<>();
        KspJvmToolchain toolchain = new KspJvmToolchain(
                "2.2.0-2.0.2",
                "2.2.0",
                List.of(Path.of("engine.jar")),
                List.of(Path.of("processor.jar")),
                "ksp:checksum-identity");
        KspProducerFingerprintService service = new KspProducerFingerprintService(
                (packages, engine, processors, kotlin, ksp) -> {
                    arguments.set(List.of(engine, processors, kotlin, ksp));
                    return toolchain;
                });

        String fingerprint = service.fingerprint(List.of(), "2.2.0", step());

        assertEquals(
                List.of("ksp:ksp:engine", "ksp:ksp:processors", "2.2.0", "2.2.0-2.0.2"),
                arguments.get());
        assertEquals(
                "zolt.ksp-producer.v1|ksp:checksum-identity",
                fingerprint);
    }

    @Test
    void rejectsAProducerFromAnotherGeneratedSourceKind() {
        GeneratedSourceStep declared = new GeneratedSourceStep(
                "declared",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "generated",
                List.of(),
                true,
                false);

        assertThrows(
                BuildException.class,
                () -> new KspProducerFingerprintService().fingerprint(
                        List.of(),
                        "2.2.0",
                        declared));
    }

    private static GeneratedSourceStep step() {
        return new GeneratedSourceStep(
                "symbols",
                GeneratedSourceKind.KSP,
                "kotlin",
                "target/generated/ksp/main/symbols",
                List.of(),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty(),
                new KspGenerationSettings(
                        "ksp",
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(new KspProcessorSettings(
                                "com.example:processor", "1.0.0", Optional.empty())),
                        Map.of()));
    }
}
