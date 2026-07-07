#!/usr/bin/env python3
"""
Minimal terminal server — HTTP on port 9120.
Serves an xterm.js UI at / and bridges shell I/O via SSE + POST.
Uses Python's pty module for a proper interactive terminal (supports vim, nano, etc.).
"""
import http.server
import json
import os
import queue
import select
import subprocess
import threading
import pty
import sys

PORT = 9120

HTML = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>Terminal</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/xterm@5/css/xterm.css"/>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { width: 100%; height: 100%; background: #0a0a0a; overflow: hidden; }
  #terminal { width: 100%; height: 100%; }
</style>
</head>
<body>
<div id="terminal"></div>
<script src="https://cdn.jsdelivr.net/npm/xterm@5/lib/xterm.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/xterm-addon-fit@0.8/lib/xterm-addon-fit.min.js"></script>
<script>
const term = new Terminal({
  theme: {
    background:  '#0a0a0a',
    foreground:  '#e2e8f0',
    cursor:      '#818cf8',
    black:       '#0a0a0a',
    brightBlack: '#475569',
    green:       '#4ade80',
    yellow:      '#fbbf24',
    blue:        '#818cf8',
    magenta:     '#c084fc',
    cyan:        '#22d3ee',
    white:       '#e2e8f0',
    brightWhite: '#f8fafc',
  },
  fontFamily: '"Cascadia Code", "JetBrains Mono", monospace',
  fontSize: 14,
  lineHeight: 1.2,
  cursorBlink: true,
  cursorStyle: 'block',
  scrollback: 5000,
  allowTransparency: false,
});

const fit = new FitAddon.FitAddon();
term.loadAddon(fit);
term.open(document.getElementById('terminal'));
fit.fit();
window.addEventListener('resize', () => fit.fit());

// Output: SSE stream from /output
const es = new EventSource('/output');
es.onmessage = e => term.write(JSON.parse(e.data));
es.onerror   = () => { /* reconnects automatically */ };

// Input: POST each keystroke to /input
term.onData(data => {
  fetch('/input', {
    method: 'POST',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: data,
  }).catch(() => {});
});

// Resize: POST cols/rows to /resize
function sendResize() {
  fit.fit();
  fetch('/resize', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cols: term.cols, rows: term.rows }),
  }).catch(() => {});
}
window.addEventListener('resize', sendResize);
</script>
</body>
</html>"""

# ── PTY + shell ─────────────────────────────────────────────────────────────

output_queue: queue.Queue = queue.Queue()
master_fd: int = -1
proc = None


def start_shell() -> None:
    global master_fd, proc

    m, slave = pty.openpty()
    master_fd = m

    env = os.environ.copy()
    env.setdefault("TERM", "xterm-256color")
    env.setdefault("LANG", "en_US.UTF-8")

    proc = subprocess.Popen(
        ["sh", "-i"],
        stdin=slave,
        stdout=slave,
        stderr=slave,
        close_fds=True,
        preexec_fn=os.setsid,
        env=env,
    )
    os.close(slave)

    def _reader():
        while True:
            try:
                r, _, _ = select.select([master_fd], [], [], 5)
                if master_fd in r:
                    data = os.read(master_fd, 4096)
                    if data:
                        output_queue.put(data.decode("utf-8", errors="replace"))
            except OSError:
                break

    threading.Thread(target=_reader, daemon=True).start()


# ── HTTP handler ─────────────────────────────────────────────────────────────


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):  # silence request logs
        pass

    # ── GET ──────────────────────────────────────────────────────────────────

    def do_GET(self):
        if self.path == "/":
            body = HTML.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        elif self.path == "/output":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            try:
                while True:
                    try:
                        chunk = output_queue.get(timeout=15)
                        self.wfile.write(
                            f"data: {json.dumps(chunk)}\n\n".encode()
                        )
                        self.wfile.flush()
                    except queue.Empty:
                        # heartbeat keeps the connection alive
                        self.wfile.write(b": heartbeat\n\n")
                        self.wfile.flush()
            except Exception:
                pass

        else:
            self.send_response(404)
            self.end_headers()

    # ── POST ─────────────────────────────────────────────────────────────────

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)

        if self.path == "/input":
            if master_fd >= 0:
                try:
                    os.write(master_fd, body)
                except OSError:
                    pass
            self.send_response(204)
            self.end_headers()

        elif self.path == "/resize":
            try:
                import struct, fcntl, termios
                data = json.loads(body)
                cols, rows = int(data.get("cols", 80)), int(data.get("rows", 24))
                winsize = struct.pack("HHHH", rows, cols, 0, 0)
                fcntl.ioctl(master_fd, termios.TIOCSWINSZ, winsize)
            except Exception:
                pass
            self.send_response(204)
            self.end_headers()

        else:
            self.send_response(404)
            self.end_headers()


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    start_shell()
    server = http.server.HTTPServer(("127.0.0.1", PORT), Handler)
    print(f"Terminal ready on http://127.0.0.1:{PORT}/", flush=True)
    server.serve_forever()
