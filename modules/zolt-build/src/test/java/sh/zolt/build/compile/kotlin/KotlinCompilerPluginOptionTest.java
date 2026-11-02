package sh.zolt.build.compile.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class KotlinCompilerPluginOptionTest {
    @Test
    void rendersTheCompilerOptionGrammar() {
        KotlinCompilerPluginOption option = new KotlinCompilerPluginOption(
                "org.jetbrains.kotlin.allopen",
                "preset",
                "spring");

        assertEquals(
                "plugin:org.jetbrains.kotlin.allopen:preset=spring",
                option.argument());
    }

    @Test
    void rejectsMalformedIdentifiersAndMultilineValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerPluginOption("allopen:other", "preset", "spring"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerPluginOption("allopen", "pre=set", "spring"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KotlinCompilerPluginOption("allopen", "preset", "spring\nother"));
    }
}
