import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

export const PETCLINIC_ARCHIVE_ENTRIES = [
  "BOOT-INF/classes/META-INF/build-info.properties",
  "BOOT-INF/classes/application.properties",
  "BOOT-INF/classes/data.sql",
  "BOOT-INF/classes/static/app.css",
  "BOOT-INF/classes/templates/owners/list.html",
  "BOOT-INF/classes/static/generated.css",
  "BOOT-INF/classes/static/generated/assets/css/theme.css",
  "BOOT-INF/classes/static/generated/assets/js/app.js",
  "BOOT-INF/classes/META-INF/resources/webjars/petclinic-lite/0.1.0/petclinic-lite.css",
] as const;

export async function writePetclinicGeneratedResources(project: string): Promise<void> {
  const generated = join(project, "target/generated/resources");
  const staticRoot = join(generated, "static");
  const webjar = join(generated, "META-INF/resources/webjars/petclinic-lite/0.1.0");
  await mkdir(join(staticRoot, "generated/assets/css"), { recursive: true });
  await mkdir(join(staticRoot, "generated/assets/js"), { recursive: true });
  await mkdir(webjar, { recursive: true });
  await writeFile(join(staticRoot, "generated.css"), "body { color: #123456; }\n", "utf8");
  await writeFile(join(staticRoot, "generated/assets/css/theme.css"), ".petclinic-generated { color: #123456; }\n", "utf8");
  await writeFile(join(staticRoot, "generated/assets/js/app.js"), "window.petclinicLite = { generated: true };\n", "utf8");
  await writeFile(join(webjar, "petclinic-lite.css"), ".webjar-petclinic-lite { display: block; }\n", "utf8");
}
