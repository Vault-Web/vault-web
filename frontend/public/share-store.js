/* Temporary, same-origin share drafts. Never stores authentication or API data. */
globalThis.vaultShareStore = (() => {
  const ttl = 30 * 60 * 1000;
  const maxBytes = 50 * 1024 * 1024;
  async function database() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open("vault-web-share-drafts", 1);
      request.onupgradeneeded = () =>
        request.result.createObjectStore("drafts");
      request.onsuccess = () => resolve(request.result);
      request.onerror = () =>
        reject(new Error("Temporary share storage is unavailable."));
    });
  }
  async function transaction(action) {
    const db = await database();
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction("drafts", "readwrite");
        const result = action(tx.objectStore("drafts"));
        tx.oncomplete = () => resolve(result.value);
        tx.onerror = tx.onabort = () =>
          reject(new Error("Could not save or read this share draft."));
      });
    } finally {
      db.close();
    }
  }
  async function cleanup() {
    await transaction((store) => {
      const request = store.openCursor();
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) return;
        if (cursor.value.expiresAt <= Date.now()) cursor.delete();
        cursor.continue();
      };
      return {};
    });
  }
  function validate(draft) {
    if (
      draft.files.length > 10 ||
      draft.files.reduce((n, f) => n + f.size, 0) > maxBytes
    ) {
      throw new Error(
        "Share up to 10 files, totaling at most 50 MiB. Use Cloud directly for larger uploads.",
      );
    }
    if (
      [draft.title, draft.text, draft.url].some(
        (v) => typeof v !== "string" || v.length > 100000,
      )
    ) {
      throw new Error("Shared text is too large or unsupported.");
    }
    if (!draft.files.length && !draft.title && !draft.text && !draft.url) {
      throw new Error("This share contains no supported files or text.");
    }
    if (
      draft.files.some(
        (f) => !(f instanceof File) || !f.name || /[/\\\u0000]/.test(f.name),
      )
    ) {
      throw new Error("A shared file has an unsupported name.");
    }
  }
  return {
    validate,
    cleanup,
    async save(draft) {
      validate(draft);
      await cleanup();
      const id = crypto.randomUUID();
      await transaction((store) => {
        // Bound temporary disk use even when many shares arrive without confirmation.
        store.clear();
        store.put({ ...draft, expiresAt: Date.now() + ttl }, id);
        return {};
      });
      return id;
    },
    async read(id) {
      await cleanup();
      return transaction((store) => {
        const result = {};
        const request = store.get(id);
        request.onsuccess = () => {
          result.value = request.result;
        };
        return result;
      });
    },
    async remove(id) {
      await transaction((store) => {
        store.delete(id);
        return {};
      });
    },
    async progress(id, destinationUser, destinationPath, uploaded) {
      await transaction((store) => {
        const request = store.get(id);
        request.onsuccess = () => {
          if (request.result && request.result.expiresAt > Date.now()) {
            store.put(
              {
                ...request.result,
                destinationUser,
                destinationPath,
                uploaded: [...uploaded],
              },
              id,
            );
          }
        };
        return {};
      });
    },
  };
})();
