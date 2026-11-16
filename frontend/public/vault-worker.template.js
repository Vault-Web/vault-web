importScripts("/share-store.js");

const CACHE = "vault-web-shell-__VERSION__";
const ASSETS = __ASSETS__;
const STATIC_PATHS = new Set(ASSETS);

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(ASSETS)));
  // Existing tabs keep their matching shell until closed; never force an update.
});
self.addEventListener("activate", (event) => {
  event.waitUntil(
    Promise.all([
      caches
        .keys()
        .then((keys) =>
          Promise.all(
            keys
              .filter(
                (key) => key.startsWith("vault-web-shell-") && key !== CACHE,
              )
              .map((key) => caches.delete(key)),
          ),
        ),
      vaultShareStore.cleanup(),
      self.clients.claim(),
    ]),
  );
});

async function receiveShare(request) {
  try {
    const declaredSize = Number(request.headers.get("content-length"));
    if (declaredSize > 51 * 1024 * 1024) throw new Error("size");
    if (!request.headers.get("content-type")?.startsWith("multipart/form-data"))
      throw new Error("type");
    // Limit the body before parsing; Content-Length may be absent or untrusted.
    const reader = request.body.getReader();
    const parts = [];
    let bytes = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      if (bytes > 51 * 1024 * 1024) {
        await reader.cancel();
        throw new Error("size");
      }
      parts.push(value);
    }
    const form = await new Response(new Blob(parts), {
      headers: { "Content-Type": request.headers.get("content-type") },
    }).formData();
    const id = await vaultShareStore.save({
      title: form.get("title") || "",
      text: form.get("text") || "",
      url: form.get("url") || "",
      files: form.getAll("files"),
    });
    return Response.redirect(
      new URL("/share-target?id=" + id, self.location.origin),
      303,
    );
  } catch {
    return Response.redirect(
      new URL("/share-target?error=unsupported", self.location.origin),
      303,
    );
  }
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname === "/share-target" && request.method === "POST") {
    event.respondWith(receiveShare(request));
    return;
  }
  if (request.method !== "GET" || request.headers.has("Authorization")) return;
  if (
    request.mode === "navigate" &&
    (url.pathname === "/" ||
      url.pathname === "/login" ||
      url.pathname === "/cloud" ||
      url.pathname === "/share-target")
  ) {
    // Only the known static index is cached. Never cache arbitrary navigation responses.
    event.respondWith(
      fetch(request).catch(() =>
        caches.open(CACHE).then((cache) => cache.match("/index.html")),
      ),
    );
  } else if (!url.search && STATIC_PATHS.has(url.pathname)) {
    event.respondWith(
      caches
        .open(CACHE)
        .then(
          async (cache) => (await cache.match(url.pathname)) || fetch(request),
        ),
    );
  }
  // Runtime config, APIs, uploads, public links and every other request use the network.
});
