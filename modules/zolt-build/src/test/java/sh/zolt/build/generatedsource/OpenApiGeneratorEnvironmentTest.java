package sh.zolt.build.generatedsource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import sh.zolt.process.SupervisedProcessSpec;

/**
 * The generator honors {@code JAVA_POST_PROCESS_FILE} and its siblings by shelling out once per
 * generated file. Those commands would run outside process supervision and outside generated-source
 * fingerprinting, so the generator must launch with a cleared, curated environment.
 */
final class OpenApiGeneratorEnvironmentTest {
    private static final Map<String, String> AMBIENT = Map.of(
            "PATH", "/usr/bin:/bin",
            "HOME", "/home/dev",
            "JAVA_HOME", "/opt/jdk21",
            "JAVA_POST_PROCESS_FILE", "/tmp/exfiltrate.sh",
            "TYPESCRIPT_POST_PROCESS_FILE", "/tmp/exfiltrate.sh",
            "AWS_SECRET_ACCESS_KEY", "super-secret",
            "LD_PRELOAD", "/tmp/evil.so");

    @Test
    void curatesOnlyOsEssentialsAndJavaHome() {
        Map<String, String> environment = OpenApiGeneratorEnvironment.build(ambient(), "Linux");

        assertEquals(
                Map.of("PATH", "/usr/bin:/bin", "HOME", "/home/dev", "JAVA_HOME", "/opt/jdk21"),
                environment);
    }

    @Test
    void windowsKeepsTheSystemRootPairTheJvmNeeds() {
        Map<String, String> environment = OpenApiGeneratorEnvironment.build(
                name -> switch (name) {
                    case "SystemRoot" -> "C:\\Windows";
                    case "SystemDrive" -> "C:";
                    default -> ambient().apply(name);
                },
                "Windows 11");

        assertEquals("C:\\Windows", environment.get("SystemRoot"));
        assertEquals("C:", environment.get("SystemDrive"));
        assertFalse(environment.containsKey("JAVA_POST_PROCESS_FILE"), environment.toString());
    }

    @Test
    void generatorProcessClearsTheAmbientEnvironmentSoPostProcessingHooksCannotRun() {
        SupervisedProcessSpec spec = OpenApiGeneratedSourceService.processSpec(
                List.of("/opt/jdk21/bin/java", "-cp", "generator.jar"),
                Path.of("/workspace/demo"),
                ambient());

        assertTrue(spec.clearEnvironment(), "the ambient environment must not be inherited");
        for (String leaked : List.of(
                "JAVA_POST_PROCESS_FILE",
                "TYPESCRIPT_POST_PROCESS_FILE",
                "AWS_SECRET_ACCESS_KEY",
                "LD_PRELOAD")) {
            assertFalse(spec.environment().containsKey(leaked), leaked + " reached the generator");
        }
        assertEquals("/usr/bin:/bin", spec.environment().get("PATH"));
    }

    private static UnaryOperator<String> ambient() {
        return AMBIENT::get;
    }
}
