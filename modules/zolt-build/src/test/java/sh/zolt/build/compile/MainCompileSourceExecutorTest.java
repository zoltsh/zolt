package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;

final class MainCompileSourceExecutorTest {
    @Test
    void rejectsGroovyMainSourcesBeforeACompileCanBeSkipped() {
        MainCompileSourceExecutor executor = new MainCompileSourceExecutor(null, null, null);
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(),
                List.of(Path.of("src/main/groovy/com/example/Main.groovy")),
                List.of(),
                List.of());

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> executor.compile(
                        true,
                        Path.of("."),
                        null,
                        sources,
                        null,
                        Path.of("target/classes"),
                        Path.of("target/generated/sources/annotations"),
                        null));

        assertTrue(exception.getMessage().contains("Groovy main sources are not supported"));
        assertTrue(exception.getMessage().contains("[build].sources"));
    }
}
