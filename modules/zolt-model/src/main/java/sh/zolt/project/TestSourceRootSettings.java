package sh.zolt.project;

import java.util.List;

/** Compatibility view of the language-specific unit-test source roots. */
public interface TestSourceRootSettings {
    TestSourceRoots testSourceRoots();

    default List<String> testSources() {
        return testSourceRoots().javaSources();
    }

    default List<String> groovyTestSources() {
        return testSourceRoots().groovySources();
    }

    default List<String> kotlinTestSources() {
        return testSourceRoots().kotlinSources();
    }
}
