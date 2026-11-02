package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.doctor.JdkStatus;

final class EffectiveCompilerIdentityTest {
    @Test
    void leavesJavaOnlyIdentityStable() {
        assertEquals(
                "sha256:e0c9305a68b9184c0726edbaf235cec9f3de8680ccdb625eddc93dde56cedb0d",
                EffectiveCompilerIdentity.of(jdkStatus()));
    }

    @Test
    void composesRelocatableGroovyCompilerIdentity() {
        GroovyCompilerToolchain first = toolchain("a".repeat(64), Path.of("first/groovy.jar"));
        GroovyCompilerToolchain relocated = toolchain("a".repeat(64), Path.of("elsewhere/groovy.jar"));
        GroovyCompilerToolchain changed = toolchain("b".repeat(64), Path.of("first/groovy.jar"));

        String firstIdentity = EffectiveCompilerIdentity.of(jdkStatus(), first);

        assertEquals(
                "sha256:3027e384be16a19e1e0fa1436b8ba1a6ce19720827f39e23bc328ae7a5c45a0b",
                firstIdentity);
        assertEquals(firstIdentity, EffectiveCompilerIdentity.of(jdkStatus(), relocated));
        assertNotEquals(firstIdentity, EffectiveCompilerIdentity.of(jdkStatus(), changed));
        assertNotEquals(EffectiveCompilerIdentity.of(jdkStatus()), firstIdentity);
    }

    @Test
    void conditionallyIncludesCompilerSemantics() {
        String compiler = "relocatable-compiler-identity";
        String withoutSemantics = EffectiveCompilerIdentity.of(
                jdkStatus(),
                "kotlinCompiler",
                compiler);
        String withSemantics = EffectiveCompilerIdentity.of(
                jdkStatus(),
                "kotlinCompiler",
                compiler,
                "kotlin-main-v1");

        assertNotEquals(withoutSemantics, withSemantics);
        assertEquals(
                withSemantics,
                EffectiveCompilerIdentity.of(
                        jdkStatus(),
                        "kotlinCompiler",
                        compiler,
                        " kotlin-main-v1 "));
    }

    private static GroovyCompilerToolchain toolchain(String sha256, Path jar) {
        return new GroovyCompilerToolchain("4.0.22", sha256, jar);
    }

    private static JdkStatus jdkStatus() {
        return new JdkStatus(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("21.0.11"),
                Optional.of("jdk-distribution-identity"),
                "21");
    }
}
