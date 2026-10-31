/* Independent, bounded, ordered delivery of captured traffic to kai2. */
class Kai2Mirror {
  constructor({fetcher = fetch, report = () => {}, port = 8892, timeoutMs = 5000,
               maxBytes = 64 * 1024 * 1024, maxQueued = 32} = {}) {
    this.fetcher = fetcher; this.report = report; this.timeoutMs = timeoutMs;
    this.maxBytes = maxBytes; this.maxQueued = maxQueued;
    this.queue = []; this.bytes = 0; this.running = false;
    this.state = {accepted: 0, dropped: 0, failed: 0, pending: 0, status: 'waiting'};
    this.setPort(port);
  }
  setPort(port) {
    if (!Number.isInteger(port) || port < 1024 || port > 65535 || port === 8891) {
      throw new Error('kai2 port must be 1024–65535 and different from legacy 8891');
    }
    this.port = port;
  }
  publish() { this.report({...this.state, port: this.port, pending: this.queue.length}); }
  enqueue(path, options = {}) {
    const size = options.body?.byteLength ?? (typeof options.body === 'string' ? options.body.length : 0);
    if (this.queue.length >= this.maxQueued || this.bytes + size > this.maxBytes) {
      this.state.dropped++; this.state.status = 'overflow'; this.publish(); return false;
    }
    this.queue.push({path, options, size, port: this.port}); this.bytes += size;
    void this.drain(); return true;
  }
  async drain() {
    if (this.running) return;
    this.running = true;
    try {
      while (this.queue.length) {
        const entry = this.queue[0];
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), this.timeoutMs);
        try {
          const response = await this.fetcher(`http://127.0.0.1:${entry.port}${entry.path}`,
            {...entry.options, signal: controller.signal});
          const text = await response.text();
          if (!response.ok || (entry.path === '/ingest' ? text.trim() !== 'ok' : !text.startsWith('OK '))) {
            throw new Error(`HTTP ${response.status}`);
          }
          this.state.accepted++; this.state.status = 'connected'; this.state.error = null;
        } catch (error) {
          this.state.failed++; this.state.status = 'disconnected';
          this.state.error = String(error.message || error);
        } finally {
          clearTimeout(timer); this.queue.shift(); this.bytes -= entry.size; this.publish();
        }
      }
    } finally { this.running = false; }
  }
}
if (typeof module !== 'undefined') module.exports = {Kai2Mirror};
