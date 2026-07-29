package sh.zolt.build.incremental;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class IncrementalCompileStateEncoding {
    static final String VERSION = "5";

    private IncrementalCompileStateEncoding() {
    }

    static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
