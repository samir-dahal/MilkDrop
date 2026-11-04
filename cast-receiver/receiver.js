/* A media-only Cast receiver. No camera, microphone, UI mirroring, or third-party player. */
(function () {
  "use strict";
  const namespace = "urn:x-cast:com.milkdrop.visualizer.live";
  const context = cast.framework.CastReceiverContext.getInstance();
  const video = document.getElementById("visuals");
  let active = null;
  function send(sender, data) {
    try { context.sendCustomMessage(namespace, sender, data); } catch (_) { /* Sender has disconnected. */ }
  }
  function stop() {
    const old = active; active = null;
    if (!old) return;
    old.abort.abort(); clearInterval(old.stats);
    video.removeEventListener("playing", old.playing);
    if (old.reader) old.reader.cancel().catch(() => {});
    try { if (old.buffer && old.buffer.updating) old.buffer.abort(); } catch (_) {}
    video.pause(); video.removeAttribute("src"); video.load();
    URL.revokeObjectURL(old.url);
  }
  function event(target, name, signal, action) {
    return new Promise((resolve, reject) => {
      function cleanup() {
        target.removeEventListener(name, done); target.removeEventListener("error", fail);
        signal.removeEventListener("abort", abort);
      }
      function done() { cleanup(); resolve(); }
      function fail() { cleanup(); reject(new Error("Video decoder rejected the stream")); }
      function abort() { cleanup(); reject(new DOMException("Stopped", "AbortError")); }
      target.addEventListener(name, done, { once: true }); target.addEventListener("error", fail, { once: true });
      signal.addEventListener("abort", abort, { once: true });
      if (signal.aborted) { abort(); return; }
      if (action) { try { action(); } catch (error) { cleanup(); reject(error); } }
    });
  }
  function latestEnd() { return video.buffered.length ? video.buffered.end(video.buffered.length - 1) : 0; }
  async function start(sender, message) {
    stop();
    if (message.version !== 1) throw new Error("Update the MilkDrop receiver to match the Android app");
    const source = new URL(message.url);
    // Android sends an opaque session URL on its numeric LAN address. Reject unrelated URLs.
    if (source.protocol !== "http:" || !/^\d+\.\d+\.\d+\.\d+$/.test(source.hostname) ||
        !/^\/[0-9a-f-]{36}\/frames$/.test(source.pathname) || source.username || source.password || source.search || source.hash) {
      throw new Error("Invalid MilkDrop stream address");
    }
    if (!window.MediaSource || !MediaSource.isTypeSupported(message.mime)) throw new Error("This receiver cannot decode the live AVC stream");
    if (!/^video\/mp4; codecs="avc1\.[0-9a-f]{6}"$/.test(message.mime)) throw new Error("Unsupported live codec");
    const media = new MediaSource();
    const session = { abort: new AbortController(), media, url: URL.createObjectURL(media), reader: null, buffer: null, stats: null, playing: null, lastRecordAt: performance.now() };
    active = session;
    const signal = session.abort.signal;
    session.playing = () => send(sender, { type: "PLAYING" });
    video.addEventListener("playing", session.playing);
    try {
      await event(media, "sourceopen", signal, () => { video.src = session.url; });
      if (active !== session) return;
      media.duration = Infinity;
      const buffer = media.addSourceBuffer(message.mime); session.buffer = buffer;
      const response = await fetch(source.href, { cache: "no-store", signal });
      if (!response.ok || !response.body) throw new Error("Cannot reach the phone's live video stream");
      session.reader = response.body.getReader();
      let started = false, trimAt = 0;
      let previousFrames = 0, previousTime = performance.now();
      session.stats = setInterval(() => {
        const now = performance.now();
        if (now - session.lastRecordAt > 10000 && active === session) {
          send(sender, { type: "ERROR", message: "The live video connection stopped delivering frames" }); stop(); return;
        }
        const quality = video.getVideoPlaybackQuality ? video.getVideoPlaybackQuality() : null;
        const frames = quality ? quality.totalVideoFrames : 0;
        send(sender, { type: "STATS", bufferSeconds: Math.max(0, latestEnd() - video.currentTime),
          fps: (frames - previousFrames) * 1000 / (now - previousTime), droppedFrames: quality ? quality.droppedVideoFrames : null });
        previousFrames = frames; previousTime = now;
      }, 1000);
      for await (const record of MilkDropStream.records(session.reader, message.maxRecordBytes)) {
        if (active !== session) return;
        session.lastRecordAt = performance.now();
        await event(buffer, "updateend", signal, () => buffer.appendBuffer(record));
        if (video.buffered.length) {
          const end = latestEnd(), lag = end - video.currentTime;
          const begin = video.buffered.start(video.buffered.length - 1);
          // Catch up after a stall or skipped GOP; never replay seconds of obsolete visuals.
          if (!started || lag > 0.4 || video.currentTime < begin) video.currentTime = Math.max(begin, end - 0.1);
          video.playbackRate = started && lag > 0.2 && lag <= 0.4 ? 1.04 : 1;
          if (!started && end - begin >= 0.04) {
            started = true;
            // play() can wait for additional decoder input on older TVs. Keep
            // appending frames while that promise is pending to avoid a startup deadlock.
            video.play().catch(error => {
              if (active === session) {
                send(sender, { type: "ERROR", message: String(error.message || error) }); stop();
              }
            });
          }
          if (typeof media.setLiveSeekableRange === "function") media.setLiveSeekableRange(begin, end);
        }
        const now = performance.now();
        if (video.currentTime > 2 && now >= trimAt) {
          trimAt = now + 1000;
          await event(buffer, "updateend", signal, () => buffer.remove(0, video.currentTime - 2));
        }
      }
      if (active === session) throw new Error("Live video connection ended");
    } catch (error) {
      if (active === session) {
        send(sender, { type: "ERROR", message: String(error.message || error) }); stop();
      }
    }
  }
  context.addCustomMessageListener(namespace, (event) => {
    const message = event.data;
    if (!message || typeof message !== "object") return;
    if (message.type === "HELLO") {
      let fullHd60 = false;
      try { fullHd60 = context.canDisplayType("video/mp4", "avc1.42c02a", 1920, 1080, 60); } catch (_) {}
      send(event.senderId, { type: "CAPABILITIES", fullHd60: fullHd60 === true });
      return;
    }
    if (message.type === "STOP") { stop(); send(event.senderId, { type: "STOPPED" }); }
    if (message.type === "START") start(event.senderId, message).catch(error => {
      send(event.senderId, { type: "ERROR", message: String(error.message || error) });
    });
  });
  context.addEventListener(cast.framework.system.EventType.SENDER_DISCONNECTED, () => {
    if (!context.getSenders().length) { stop(); context.stop(); }
  });
  window.addEventListener("pagehide", stop);
  video.addEventListener("error", () => {
    if (active) { send(null, { type: "ERROR", message: "The receiver video decoder failed" }); stop(); }
  });
  // This visualization manages its own MSE player rather than CAF's media queue.
  // Sender disconnection and the stream watchdog above own the session lifetime.
  context.start({ disableIdleTimeout: true, customNamespaces: { [namespace]: cast.framework.system.MessageType.JSON }, mediaElement: video });
})();
