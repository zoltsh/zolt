package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.project.CompilerSettings;

final class PackageCompilerSettingsIdentityTest {
    @Test
    void fingerprintsKotlinModuleIdentityInItsOwningScope() {
        CompilerSettings original = settings("main-one", "test-one");
        CompilerSettings mainChanged = settings("main-two", "test-one");
        CompilerSettings testChanged = settings("main-one", "test-two");

        assertNotEquals(
                PackageCompilerSettingsIdentity.main(original),
                PackageCompilerSettingsIdentity.main(mainChanged));
        assertEquals(
                PackageCompilerSettingsIdentity.main(original),
                PackageCompilerSettingsIdentity.main(testChanged));
        assertNotEquals(
                PackageCompilerSettingsIdentity.test(original),
                PackageCompilerSettingsIdentity.test(mainChanged));
        assertNotEquals(
                PackageCompilerSettingsIdentity.test(original),
                PackageCompilerSettingsIdentity.test(testChanged));
    }

    private static CompilerSettings settings(String mainModule, String testModule) {
        return new CompilerSettings(
                "target/generated/sources/annotations",
                "target/generated/test-sources/annotations",
                "21",
                "UTF-8",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_RELEASE,
                "",
                "",
                "2.4.20",
                mainModule,
                testModule,
                Set.of());
    }
}
