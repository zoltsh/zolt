package sh.zolt.build.classpath;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ClasspathClassifierTest {
    @Test
    void runtimeClasspathKeepsClassifierJarPath() {
        ZoltLockfile lockfile;
        try {
            lockfile = sh.zolt.build.lockfile.ContentAddressedLockTestSupport.migrate(Path.of(""), """
                version = 1

                [[package]]
                id = "com.example:native-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/native-lib/1.0.0/native-lib-1.0.0-linux-x86_64.jar"
                dependencies = []
                """);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }

        ClasspathSet classpaths = new ClasspathBuilder().build(
                LockfileClasspathPackageConverter.classpathPackages(lockfile));

        assertEquals(1, classpaths.runtime().entries().size());
        assertEquals(
                "native-lib-1.0.0-linux-x86_64.jar",
                classpaths.runtime().entries().getFirst().getFileName().toString());
        assertEquals(classpaths.runtime().entries(), classpaths.test().entries());
    }
}
