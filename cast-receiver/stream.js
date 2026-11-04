/* Framing is independent of HTTP chunk boundaries. Kept separate for transport tests. */
(function (root) {
  "use strict";
  async function* records(reader, limit) {
    if (!Number.isInteger(limit) || limit <= 0) throw new Error("Invalid stream allocation limit");
    const header = new Uint8Array(4);
    let headerUsed = 0, payload = null, used = 0;
    for (;;) {
      const part = await reader.read();
      if (part.done) {
        if (headerUsed || payload) throw new Error("Truncated live video record");
        return;
      }
      let offset = 0;
      while (offset < part.value.length) {
        if (!payload) {
          const n = Math.min(4 - headerUsed, part.value.length - offset);
          header.set(part.value.subarray(offset, offset + n), headerUsed);
          headerUsed += n; offset += n;
          if (headerUsed < 4) continue;
          const length = new DataView(header.buffer).getUint32(0);
          if (!length || length > limit) throw new Error("Invalid live video record length");
          payload = new Uint8Array(length); used = 0; headerUsed = 0;
        }
        const n = Math.min(payload.length - used, part.value.length - offset);
        payload.set(part.value.subarray(offset, offset + n), used);
        used += n; offset += n;
        if (used === payload.length) {
          const complete = payload; payload = null; used = 0;
          yield complete;
        }
      }
    }
  }
  const api = { records };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.MilkDropStream = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
