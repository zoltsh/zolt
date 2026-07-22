import { appendFile, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export async function writeNativeWorkspace(root: string): Promise<void> {
  const app = join(root, "apps/api");
  const core = join(root, "modules/core");
  await mkdir(join(app, "src/main/java/com/acme/api"), { recursive: true });
  await mkdir(join(core, "src/main/java/com/acme/core"), { recursive: true });
  await writeFile(join(root, "zolt.toml"), [
    "[workspace]",
    'name = "workspace-real-native"',
    'members = ["apps/api", "modules/core"]',
    'defaultMembers = ["apps/api"]',
    "",
  ].join("\n"), "utf8");
  await writeFile(join(core, "zolt.toml"), [
    "[project]",
    'name = "core"',
    'version = "0.1.0"',
    'group = "com.acme"',
    'java = "21"',
    "",
  ].join("\n"), "utf8");
  await writeFile(join(core, "src/main/java/com/acme/core/Core.java"), [
    "package com.acme.core;",
    "",
    "public final class Core {",
    "    private Core() {}",
    '    public static String message() { return "core"; }',
    "}",
    "",
  ].join("\n"), "utf8");
  await writeFile(join(app, "zolt.toml"), [
    "[project]",
    'name = "api"',
    'version = "0.1.0"',
    'group = "com.acme"',
    'java = "21"',
    'main = "com.acme.api.Api"',
    "",
    "[dependencies]",
    '"com.acme:core" = { workspace = "modules/core" }',
    "",
    "[native]",
    'imageName = "workspace-api"',
    'args = ["--no-fallback"]',
    "",
  ].join("\n"), "utf8");
  await writeFile(join(app, "src/main/java/com/acme/api/Api.java"), [
    "package com.acme.api;",
    "",
    "import com.acme.core.Core;",
    "",
    "public final class Api {",
    "    private Api() {}",
    "    public static void main(String[] args) {",
    '        String suffix = args.length == 0 ? "no-args" : args[0];',
    '        System.out.println("api:" + Core.message() + ":" + suffix);',
    "    }",
    "}",
    "",
  ].join("\n"), "utf8");
}

export async function addVertxNativeConfig(project: string): Promise<void> {
  await appendFile(join(project, "zolt.toml"), [
    "",
    "[native]",
    'imageName = "vertx-http"',
    'output = "target/native"',
    "args = [",
    '  "--no-fallback",',
    '  "--initialize-at-run-time=io.netty.channel,io.netty.handler.ssl,io.netty.util.internal.shaded.org.jctools.util.UnsafeLongArrayAccess",',
    '  "--initialize-at-build-time=io.netty.buffer.UnpooledByteBufAllocator,io.netty.buffer.AbstractByteBufAllocator,io.netty.buffer.Unpooled,io.netty.buffer.EmptyByteBuf,io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess,org.slf4j"',
    "]",
    "",
  ].join("\n"), "utf8");
}
