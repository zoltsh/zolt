package sh.zolt.build.packageplan;

import sh.zolt.project.CompilerSettings;

final class PackageCompilerSettingsIdentity {
    private PackageCompilerSettingsIdentity() {
    }

    static String main(CompilerSettings compiler) {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-main-compiler-settings.v1");
        mainValues(hash, compiler);
        return hash.finish();
    }

    static String test(CompilerSettings compiler) {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-test-compiler-settings.v1");
        mainValues(hash, compiler);
        hash.value(
                "generatedTestSources",
                compiler.generatedTestSources());
        hash.value("testArgs", compiler.testArgs().toString());
        hash.value("testPlatformApi", compiler.testPlatformApi());
        return hash.finish();
    }

    private static void mainValues(
            PackageCanonicalHash hash,
            CompilerSettings compiler) {
        hash.value("generatedSources", compiler.generatedSources());
        hash.value("release", compiler.release());
        hash.value("encoding", compiler.encoding());
        hash.value("args", compiler.args().toString());
        hash.value("platformApi", compiler.platformApi());
    }
}
