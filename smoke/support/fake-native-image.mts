import { chmod, mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export interface FakeNativeImage {
  readonly argsLog: string;
  readonly command: string;
}

export async function writeFakeNativeImage(root: string): Promise<FakeNativeImage> {
  await mkdir(root, { recursive: true });
  const command = join(root, "fake-native-image");
  const argsLog = join(root, "native-image.args");
  await writeFile(command, [
    "#!/usr/bin/env bash",
    "set -euo pipefail",
    'printf \'%s\\n\' "$*" >"${ZOLT_FAKE_NATIVE_IMAGE_ARGS:?}"',
    'output=""',
    'while [[ "$#" -gt 0 ]]; do',
    '  if [[ "$1" == "-o" ]]; then',
    "    shift",
    '    output="${1:-}"',
    "    break",
    "  fi",
    "  shift",
    "done",
    '[[ -n "$output" ]] || { printf \'missing -o output path\\n\' >&2; exit 2; }',
    'mkdir -p "$(dirname "$output")"',
    'printf \'#!/usr/bin/env bash\\nprintf "spring boot native aot canary\\n"\\n\' >"$output"',
    'chmod +x "$output"',
    'printf \'fake native-image wrote %s\\n\' "$output"',
    "",
  ].join("\n"), "utf8");
  await chmod(command, 0o755);
  return { argsLog, command };
}
