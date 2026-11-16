import { readFile, writeFile, readdir, unlink } from "node:fs/promises";
import { createHash } from "node:crypto";
import { resolve } from "node:path";

const output = resolve("dist/frontend/browser");
const files = await readdir(output);
const assets = files.filter((name) =>
  /^(?:main|polyfills|chunk|styles)-[A-Z0-9]+\.(?:js|css)$/.test(name),
);
assets.push(
  "index.html",
  "manifest.webmanifest",
  "icon-192.png",
  "icon-512.png",
  "share-store.js",
);
const hash = createHash("sha256");
for (const name of assets) hash.update(await readFile(resolve(output, name)));
const template = await readFile(
  resolve(output, "vault-worker.template.js"),
  "utf8",
);
hash.update(template);
await writeFile(
  resolve(output, "vault-worker.js"),
  template
    .replace("__VERSION__", hash.digest("hex").slice(0, 16))
    .replace("__ASSETS__", JSON.stringify(assets.map((name) => "/" + name))),
);
await unlink(resolve(output, "vault-worker.template.js"));
console.log(`Generated versioned app shell (${assets.length} static assets).`);
