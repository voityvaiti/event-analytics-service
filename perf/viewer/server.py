"""Serve the perf journals to a local page: every cell's rows and their lag curves.

Bound to 127.0.0.1 and serving only explicit routes, never the repository, which holds
the development signing key. Journals are re-read on every request, so new rows show up
on reload. NaN, which Python writes for a failed lag sample, becomes null: browsers
reject NaN in JSON.

Usage: python3 perf/viewer/server.py [--port N] [--no-browser]
"""

import argparse
import errno
import glob
import json
import math
import os
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PERF_DIR = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "index.html")
DEFAULT_PORT = 8765


def finite(value):
    """Replace NaN and infinities, at any depth, with None."""
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, dict):
        return {key: finite(item) for key, item in value.items()}
    if isinstance(value, list):
        return [finite(item) for item in value]
    return value


def read_jsonl(path):
    """Parse one JSON object per line; return the objects and how many lines failed."""
    rows, unparsable = [], 0
    if not os.path.exists(path):
        return rows, unparsable
    with open(path, encoding="utf-8") as lines:
        for line in lines:
            if not line.strip():
                continue
            try:
                rows.append(finite(json.loads(line)))
            except json.JSONDecodeError:
                unparsable += 1
    return rows, unparsable


def cells():
    """Every journal under perf/, keyed by its directory relative to perf/."""
    found = {}
    for journal in sorted(glob.glob(os.path.join(PERF_DIR, "**", "journal.jsonl"), recursive=True)):
        cell = os.path.relpath(os.path.dirname(journal), PERF_DIR).replace(os.sep, "/")
        found[cell] = journal
    return found


def cell_summaries():
    summaries = []
    for cell, journal in cells().items():
        rows, unparsable = read_jsonl(journal)
        summaries.append(
            {
                "cell": cell,
                "rows": len(rows),
                "unparsable": unparsable,
                "has_lag": os.path.exists(os.path.join(os.path.dirname(journal), "lag.jsonl")),
                "latest": max((row.get("date") or "" for row in rows), default=""),
            }
        )
    return summaries


def cell_detail(cell):
    """One cell's rows and its lag curves by run_id, or None for an unknown cell."""
    journal = cells().get(cell)
    if journal is None:
        return None
    rows, unparsable = read_jsonl(journal)
    curves, curves_unparsable = read_jsonl(os.path.join(os.path.dirname(journal), "lag.jsonl"))
    return {
        "cell": cell,
        "rows": rows,
        "unparsable": unparsable + curves_unparsable,
        "curves": {curve["run_id"]: curve["samples"] for curve in curves if "run_id" in curve},
    }


class Handler(BaseHTTPRequestHandler):
    """Routes: / for the page, /api/cells for the list, /api/cell?cell=… for one cell."""

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/":
            with open(PAGE, "rb") as page:
                self.respond(200, "text/html; charset=utf-8", page.read())
        elif url.path == "/api/cells":
            self.respond_json(200, cell_summaries())
        elif url.path == "/api/cell":
            detail = cell_detail(parse_qs(url.query).get("cell", [""])[0])
            if detail is None:
                self.respond_json(404, {"error": "unknown cell"})
            else:
                self.respond_json(200, detail)
        else:
            self.respond_json(404, {"error": "not found"})

    def respond_json(self, status, body):
        payload = json.dumps(body, allow_nan=False).encode("utf-8")
        self.respond(status, "application/json", payload)

    def respond(self, status, content_type, payload):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        return


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", DEFAULT_PORT)))
    parser.add_argument("--no-browser", action="store_true", help="do not open a browser tab")
    args = parser.parse_args()

    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    except OSError as failure:
        if failure.errno == errno.EADDRINUSE:
            sys.exit(f"Port {args.port} is taken; pass --port or set PORT.")
        raise
    url = f"http://127.0.0.1:{args.port}/"
    print(f"Perf journals at {url} (Ctrl-C to stop)", flush=True)
    if not args.no_browser:
        threading.Timer(0.3, webbrowser.open, args=(url,)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
