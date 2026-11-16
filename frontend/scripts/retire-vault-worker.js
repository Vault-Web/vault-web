// Rollback artifact: serve this file as /vault-worker.js alongside the old frontend.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      await Promise.all(
        (await caches.keys())
          .filter((key) => key.startsWith("vault-web-shell-"))
          .map((key) => caches.delete(key)),
      );
      await new Promise((resolve, reject) => {
        const request = indexedDB.deleteDatabase("vault-web-share-drafts");
        request.onsuccess = resolve;
        request.onerror = reject;
        // Close share review tabs before retirement if they hold this database open.
        request.onblocked = resolve;
      });
      await self.registration.unregister();
    })(),
  );
});
