import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFile, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve, extname } from "node:path";
import { spawn } from "node:child_process";

const output = resolve("dist/frontend/browser");
const manifest = JSON.parse(
  await readFile(join(output, "manifest.webmanifest")),
);
assert.equal(manifest.display, "standalone");
assert.equal(manifest.share_target.method, "POST");
for (const size of [192, 512]) {
  const png = await readFile(join(output, `icon-${size}.png`));
  assert.equal(png.readUInt32BE(16), size);
  assert.equal(png.readUInt32BE(20), size);
}
const seen = [];
const server = createServer(async (req, res) => {
  const path = new URL(req.url, "http://localhost").pathname;
  seen.push(path);
  if (path === "/api/private") {
    res.end("private-online-only");
    return;
  }
  if (path === "/runtime-config.local.js") {
    res.end("globalThis.runtimeOnline = true;");
    return;
  }
  try {
    const file = path === "/" || !extname(path) ? "index.html" : path.slice(1);
    if (file.includes("..")) throw new Error("path");
    const body = await readFile(join(output, file));
    const type = {
      ".js": "text/javascript",
      ".css": "text/css",
      ".html": "text/html",
      ".webmanifest": "application/manifest+json",
      ".png": "image/png",
    }[extname(file)];
    res.setHeader("Content-Type", type || "application/octet-stream");
    res.setHeader("Cache-Control", "no-store");
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const profile = await mkdtemp(join(tmpdir(), "vaultweb-pwa-"));
const browser = spawn(process.env.CHROME_BIN || "chromium", [
  "--headless",
  "--no-sandbox",
  "--disable-dev-shm-usage",
  "--remote-debugging-port=0",
  "--user-data-dir=" + profile,
  "about:blank",
]);
let socket;
const pending = new Map();
let sequence = 0;
function send(method, params = {}) {
  const id = ++sequence;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error("CDP timeout: " + method));
    }, 30000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await send("Runtime.evaluate", {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails)
    throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
}
try {
  const endpoint = await new Promise((resolve, reject) => {
    let log = "";
    const timer = setTimeout(
      () => reject(new Error("Chromium did not start")),
      30000,
    );
    browser.stderr.on("data", (data) => {
      log += data;
      const match = log.match(/DevTools listening on (ws:\/\/[^\s]+)/);
      if (match) {
        clearTimeout(timer);
        resolve(match[1]);
      }
    });
    browser.on("error", reject);
  });
  const port = new URL(endpoint).port;
  const tabs = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  socket = new WebSocket(
    tabs.find((tab) => tab.type === "page").webSocketDebuggerUrl,
  );
  await new Promise((resolve) =>
    socket.addEventListener("open", resolve, { once: true }),
  );
  socket.addEventListener("message", ({ data }) => {
    const message = JSON.parse(data);
    const waiter = pending.get(message.id);
    if (!waiter) return;
    clearTimeout(waiter.timer);
    pending.delete(message.id);
    if (message.error) waiter.reject(new Error(JSON.stringify(message.error)));
    else waiter.resolve(message.result);
  });
  await send("Page.enable");
  await send("Network.enable");
  await send("Page.navigate", { url: origin + "/" });
  for (let i = 0; i < 100; i++) {
    if (
      await evaluate(
        '!!window.vaultShareStore && document.readyState === "complete"',
      )
    )
      break;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  await evaluate(
    '(async () => { await navigator.serviceWorker.register("/vault-worker.js"); await navigator.serviceWorker.ready; if (!navigator.serviceWorker.controller) await new Promise(r => navigator.serviceWorker.addEventListener("controllerchange", r, {once:true})); return true; })()',
  );
  const receipt = await evaluate(`(async () => {
    const form = new FormData(); form.set('title', '<script>private title</script>'); form.set('text', 'private body'); form.set('url', 'https://example.test/private'); form.append('files', new File(['real file bytes'], 'photo.txt', {type:'text/plain'}));
    form.append('files', new File(['invoice bytes'], 'john@example.com-invoice.pdf', {type:'application/pdf'}));
    form.append('files', new File(['photo bytes'], 'Photo^1.jpg', {type:'image/jpeg'}));
    const response = await fetch('/share-target', {method:'POST', body:form});
    const id = new URL(response.url).searchParams.get('id'); const draft = await vaultShareStore.read(id);
    return { id, title:draft.title, text:draft.text, url:draft.url, file:await draft.files[0].text(), name:draft.files[0].name, files:await Promise.all(draft.files.map(async file => ({name:file.name, text:await file.text(), type:file.type}))) };
  })()`);
  assert.match(receipt.id, /^[a-f0-9-]{36}$/);
  assert.equal(receipt.file, "real file bytes");
  assert.equal(receipt.title, "<script>private title</script>");
  assert.equal(receipt.text, "private body");
  assert.equal(receipt.url, "https://example.test/private");
  assert.equal(receipt.name, "photo.txt");
  assert.deepEqual(receipt.files, [
    { name: "photo.txt", text: "real file bytes", type: "text/plain" },
    {
      name: "john@example.com-invoice.pdf",
      text: "invoice bytes",
      type: "application/pdf",
    },
    { name: "Photo^1.jpg", text: "photo bytes", type: "image/jpeg" },
  ]);
  assert.equal(
    await evaluate(`(() => {
      for (const name of ['folder/file.txt', 'folder' + String.fromCharCode(92) + 'file.txt', 'bad' + String.fromCharCode(0) + '.txt', '']) {
        try { vaultShareStore.validate({title:'', text:'', url:'', files:[new File(['bytes'], name)]}); return false; }
        catch { /* Path separators, NUL and empty names remain rejected. */ }
      }
      return true;
    })()`),
    true,
  );
  const progress = await evaluate(
    `(async()=>{await vaultShareStore.progress('${receipt.id}','owner','/owner/nested',['completed.txt']); const draft=await vaultShareStore.read('${receipt.id}'); return {user:draft.destinationUser,path:draft.destinationPath,uploaded:draft.uploaded};})()`,
  );
  assert.deepEqual(progress, {
    user: "owner",
    path: "/owner/nested",
    uploaded: ["completed.txt"],
  });
  assert.equal(
    await evaluate(
      `(async () => { const form = new FormData(); return new URL((await fetch('/share-target', {method:'POST',body:form})).url).searchParams.get('error'); })()`,
    ),
    "unsupported",
  );
  assert.equal(
    await evaluate(
      `(async () => { const form = new FormData(); for(let i=0;i<11;i++) form.append('files',new File(['a'],'a.txt')); return new URL((await fetch('/share-target',{method:'POST',body:form})).url).searchParams.get('error'); })()`,
    ),
    "unsupported",
  );
  await evaluate('fetch("/api/private").then(r=>r.text())');
  const cached = await evaluate(
    '(async()=>{const c=await caches.open((await caches.keys()).find(k=>k.startsWith("vault-web-shell-"))); return (await c.keys()).map(r=>new URL(r.url).pathname);})()',
  );
  assert.ok(cached.includes("/index.html"));
  assert.ok(!cached.includes("/api/private"));
  assert.ok(!cached.includes("/runtime-config.local.js"));
  await send("Network.emulateNetworkConditions", {
    offline: true,
    latency: 0,
    downloadThroughput: 0,
    uploadThroughput: 0,
  });
  assert.equal(
    await evaluate('fetch("/api/private").then(()=>false,()=>true)'),
    true,
  );
  assert.equal(
    await evaluate(
      'fetch("/runtime-config.local.js").then(()=>false,()=>true)',
    ),
    true,
  );
  assert.equal(
    await evaluate(
      'fetch("/index.html").then(r=>r.text()).then(t=>t.includes("<app-root>"))',
    ),
    true,
  );
  await send("Page.navigate", {
    url: origin + "/share-target?id=" + receipt.id,
  });
  await new Promise((resolve) => setTimeout(resolve, 1000));
  assert.equal(await evaluate('!!document.querySelector("app-root")'), true);
  await evaluate(
    `(async()=>{ const db=await new Promise(r=>{const q=indexedDB.open('vault-web-share-drafts',1);q.onsuccess=()=>r(q.result);}); await new Promise(r=>{const tx=db.transaction('drafts','readwrite'); tx.objectStore('drafts').put({expiresAt:0},'expired'); tx.oncomplete=r;}); db.close(); })()`,
  );
  assert.equal(
    await evaluate('vaultShareStore.read("expired").then(v=>v===undefined)'),
    true,
  );
  assert.equal(
    await evaluate(
      `vaultShareStore.remove('${receipt.id}').then(()=>vaultShareStore.read('${receipt.id}')).then(v=>v===undefined)`,
    ),
    true,
  );
  console.log(
    "PWA Chromium checks passed: manifest/icons, worker installation, real multipart receipt, unsupported payloads, private network-only data, offline shell, expiry and discard.",
  );
} finally {
  socket?.close();
  browser.kill("SIGTERM");
  await new Promise((resolve) =>
    browser.exitCode !== null ? resolve() : browser.once("exit", resolve),
  );
  server.close();
  await rm(profile, { recursive: true, force: true });
}
