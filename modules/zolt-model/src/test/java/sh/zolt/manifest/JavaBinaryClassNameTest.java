package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class JavaBinaryClassNameTest {
    @Test
    void acceptsQualifiedTopLevelAndNestedBinaryNames() {
        assertDoesNotThrow(() -> new JavaBinaryClassName("com.example.Main"));
        assertDoesNotThrow(() -> new JavaBinaryClassName("com.example.Outer$Inner"));
        assertDoesNotThrow(() -> new JavaBinaryClassName("com.example.Outer$1"));
    }

    @Test
    void rejectsNamesThatAreNotFullyQualifiedJavaBinaryNames() {
        List<String> invalid = List.of(
                "Main",
                "com..Main",
                "com.example.1Main",
                "com.example.class",
                "com.example.Main method",
                "[Lcom.example.Main;");

        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> new JavaBinaryClassName(value));
        }
    }
}
