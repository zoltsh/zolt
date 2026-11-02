package sh.zolt.build.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SourceDiscoveryResultTest {
    private static final List<Path> MAIN = List.of(Path.of("Main.java"));
    private static final List<Path> GROOVY_MAIN = List.of(Path.of("Main.groovy"));
    private static final List<Path> TEST = List.of(Path.of("MainTest.java"));
    private static final List<Path> GROOVY_TEST = List.of(Path.of("MainSpec.groovy"));

    @Test
    void legacyConstructorsKeepTheirPositionalMeaning() {
        SourceDiscoveryResult two = new SourceDiscoveryResult(MAIN, TEST);
        SourceDiscoveryResult three = new SourceDiscoveryResult(MAIN, TEST, GROOVY_TEST);
        SourceDiscoveryResult four = new SourceDiscoveryResult(MAIN, GROOVY_MAIN, TEST, GROOVY_TEST);

        assertEquals(MAIN, two.mainSources());
        assertEquals(TEST, two.testSources());
        assertEquals(TEST, three.testSources());
        assertEquals(GROOVY_TEST, three.groovyTestSources());
        assertEquals(GROOVY_MAIN, four.groovyMainSources());
        assertEquals(TEST, four.testSources());
        assertEquals(GROOVY_TEST, four.groovyTestSources());
        assertEquals(List.of(), two.kotlinMainSources());
        assertEquals(List.of(), three.kotlinTestSources());
        assertEquals(List.of(), four.kotlinMainSources());
        assertEquals(List.of(), four.kotlinTestSources());
    }

    @Test
    void kotlinSourcesParticipateInCombinedOrderingAndEmptiness() {
        Path javaMain = Path.of("b/Main.java");
        Path groovyMain = Path.of("a/Main.groovy");
        Path kotlinMain = Path.of("c/Main.kt");
        Path javaTest = Path.of("b/MainTest.java");
        Path groovyTest = Path.of("c/MainSpec.groovy");
        Path kotlinTest = Path.of("a/MainTest.kt");
        SourceDiscoveryResult result = new SourceDiscoveryResult(
                List.of(javaMain),
                List.of(groovyMain),
                List.of(kotlinMain),
                List.of(javaTest),
                List.of(groovyTest),
                List.of(kotlinTest));

        assertEquals(List.of(groovyMain, javaMain, kotlinMain), result.allMainSources());
        assertEquals(List.of(kotlinTest, javaTest, groovyTest), result.allTestSources());
        assertFalse(result.empty());
    }
}
