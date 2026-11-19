#!/usr/bin/env python3
"""Tiny HTTP CONNECT proxy that counts bytes received from upstream (= bytes fetched by ffmpeg)."""
import socket, sys, threading
total = 0
lock = threading.Lock()
def pipe(src, dst, count):
    global total
    try:
        while True:
            d = src.recv(65536)
            if not d: break
            if count:
                with lock: total += len(d)
            dst.sendall(d)
    except OSError: pass
    finally:
        for s in (src, dst):
            try: s.shutdown(socket.SHUT_RDWR)
            except OSError: pass
def handle(c):
    req = b""
    while b"\r\n\r\n" not in req:
        d = c.recv(4096)
        if not d: return
        req += d
    line = req.split(b"\r\n")[0].decode()
    method, target, _ = line.split(" ", 2)
    if method != "CONNECT": c.close(); return
    host, port = target.rsplit(":", 1)
    u = socket.create_connection((host, int(port)), timeout=60)
    c.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
    threading.Thread(target=pipe, args=(c, u, False), daemon=True).start()
    pipe(u, c, True)
def main():
    port, out = int(sys.argv[1]), sys.argv[2]
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", port)); s.listen(64)
    def report():
        while True:
            threading.Event().wait(0.5)
            with lock: open(out, "w").write(str(total))
    threading.Thread(target=report, daemon=True).start()
    while True:
        c, _ = s.accept()
        threading.Thread(target=handle, args=(c,), daemon=True).start()
if __name__ == "__main__": main()
