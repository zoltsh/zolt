package sh.zolt.build.incremental;

import sh.zolt.build.discovery.SourceDiscoveryResult;
import java.util.ArrayList;
import java.util.List;

/** Conservative state markers for main languages outside Java selective compilation. */
final class IncrementalCompileLanguageFallbacks {
    private IncrementalCompileLanguageFallbacks() {
    }

    static List<String> main(SourceDiscoveryResult sources) {
        List<String> reasons = new ArrayList<>();
        if (!sources.groovyMainSources().isEmpty()) {
            reasons.add("groovy-main-sources");
        }
        if (!sources.kotlinMainSources().isEmpty()) {
            reasons.add("kotlin-main-sources");
        }
        return List.copyOf(reasons);
    }
}
