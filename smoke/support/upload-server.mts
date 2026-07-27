import type { SmokeContext } from "smoque";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";

import type { BasicCredentials } from "./authenticated-server.mts";

/**
 * A loopback file server that also stores `PUT` bodies, so a smoke can round-trip real uploads:
 * publish writes artifacts, checksum sidecars, and signatures, then a consumer resolves them back.
 * `startAuthenticatedFileServer` stays read-only for the resolution smokes that must reject writes.
 */
export interface UploadFileServer {
  readonly requests: readonly string[];
  readonly root: string;
  url(path?: string): string;
}

export async function startUploadFileServer(
  t: SmokeContext,
  root: string,
  credentials?: BasicCredentials,
): Promise<UploadFileServer> {
  const serverRoot = resolve(root);
  await mkdir(serverRoot, { recursive: true });
  const authorization = credentials === undefined
    ? undefined
    : `Basic ${Buffer.from(`${credentials.username}:${credentials.password}`).toString("base64")}`;
  const requests: string[] = [];
  const server = createServer((request, response) => {
    void handle(request, response);
  });

  async function handle(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const method = request.method ?? "GET";
    const pathname = decodeURIComponent(new URL(request.url ?? "/", "http://localhost").pathname);
    requests.push(`${method} ${pathname}`);

    if (authorization !== undefined && request.headers.authorization !== authorization) {
      response.writeHead(401, { "WWW-Authenticate": 'Basic realm="zolt-smoke"' });
      response.end();
      return;
    }

    const file = resolve(serverRoot, `.${pathname}`);
    if (file !== serverRoot && !file.startsWith(`${serverRoot}${sep}`)) {
      response.writeHead(404);
      response.end();
      return;
    }

    if (method === "PUT" || method === "POST") {
      const chunks: Buffer[] = [];
      for await (const chunk of request) {
        chunks.push(Buffer.from(chunk));
      }
      await mkdir(dirname(file), { recursive: true });
      await writeFile(file, Buffer.concat(chunks));
      response.writeHead(201);
      response.end();
      return;
    }
    if (method !== "GET" && method !== "HEAD") {
      response.writeHead(405);
      response.end();
      return;
    }

    try {
      if (!(await stat(file)).isFile()) {
        throw new Error("not a file");
      }
      const content = await readFile(file);
      response.writeHead(200, { "Content-Length": content.length });
      response.end(method === "HEAD" ? undefined : content);
    } catch {
      response.writeHead(404);
      response.end();
    }
  }

  await listen(server);
  t.cleanup(async () => await close(server));
  const address = server.address();
  if (address === null || typeof address === "string") {
    t.fail("Upload file server did not bind a TCP port.");
  }

  return {
    requests,
    root: serverRoot,
    url: (path = "") => `http://127.0.0.1:${address.port}/${path.replace(/^\/+/u, "")}`,
  };
}

async function listen(server: Server): Promise<void> {
  await new Promise<void>((resolveListen, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolveListen();
    });
  });
}

async function close(server: Server): Promise<void> {
  if (!server.listening) {
    return;
  }
  await new Promise<void>((resolveClose, reject) => {
    server.close((error) => error === undefined ? resolveClose() : reject(error));
  });
}
