// Reproducible, original mask-safe vault icon, without external image tooling.
import { deflateSync } from "node:zlib";
import { writeFileSync } from "node:fs";
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const body = Buffer.concat([Buffer.from(type), data]);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, checksum]);
}
for (const size of [192, 512]) {
  const pixels = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const dx = x / size - 0.5;
      const dy = y / size - 0.5;
      const box = Math.abs(dx) < 0.27 && Math.abs(dy) < 0.27;
      const border = box && (Math.abs(dx) > 0.24 || Math.abs(dy) > 0.24);
      const ring = Math.hypot(dx, dy) > 0.105 && Math.hypot(dx, dy) < 0.135;
      const handle = Math.abs(dx) < 0.016 && Math.abs(dy) < 0.18;
      const hinge =
        dx > -0.21 && dx < -0.18 && Math.abs(dy) > 0.13 && Math.abs(dy) < 0.2;
      const color =
        border || ring || handle || hinge
          ? [110, 231, 183, 255]
          : [15, 23, 42, 255];
      const offset = y * (size * 4 + 1) + 1 + x * 4;
      color.forEach((v, i) => {
        pixels[offset + i] = v;
      });
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size);
  header.writeUInt32BE(size, 4);
  header[8] = 8;
  header[9] = 6;
  writeFileSync(
    `public/icon-${size}.png`,
    Buffer.concat([
      Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
      chunk("IHDR", header),
      chunk("IDAT", deflateSync(pixels)),
      chunk("IEND", Buffer.alloc(0)),
    ]),
  );
}
