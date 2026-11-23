#!/usr/bin/env python3
"""Offline local dashboard, adapted from uploaded FastAPI/React screenshot workflow.
Uses THE Android Java classes (no remote API, no extra Python packages).
Binds 127.0.0.1 only. Needs Python 3 and JDK 17+.
"""
from base64 import b64decode
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import json
import subprocess
import tempfile
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CLASSES = HERE / '.classes'


def compile_java():
    CLASSES.mkdir(exist_ok=True)
    sources = [
        ROOT / 'app/src/main/java/com/pixelblock/autoplay/vision/PixelAnalyzer.java',
        ROOT / 'app/src/main/java/com/pixelblock/autoplay/strategy/StrategyEngine.java',
        ROOT / 'app/src/main/java/com/pixelblock/autoplay/strategy/MoveVerifier.java',
        HERE / 'AnalyzeScreenshot.java',
    ]
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(CLASSES), *map(str, sources)],
                   check=True, timeout=35)


class Handler(BaseHTTPRequestHandler):
    def response(self, status, payload, ctype='application/json; charset=utf-8'):
        raw = payload if isinstance(payload, bytes) else payload.encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == '/':
            self.response(200, (HERE / 'dashboard.html').read_bytes(), 'text/html; charset=utf-8')
        elif self.path == '/health':
            self.response(200, '{"ok":true,"mode":"offline Java core"}')
        else:
            self.response(404, '{"error":"Not found"}')

    def do_POST(self):
        if self.path != '/analyze':
            self.response(404, '{"error":"Not found"}')
            return
        size = int(self.headers.get('Content-Length', '0'))
        if size <= 0 or size > 18_000_000:
            self.response(413, '{"error":"Image too large"}')
            return
        try:
            doc = json.loads(self.rfile.read(size))
            image = b64decode(doc['imageBase64'], validate=True)
            if len(image) > 12_000_000:
                raise ValueError('Image exceeds 12 MB')
            with tempfile.TemporaryDirectory() as tmp:
                f = Path(tmp) / 'frame.png'
                f.write_bytes(image)
                p = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(CLASSES),
                                    'AnalyzeScreenshot', str(f)],
                                   capture_output=True, text=True, timeout=20)
            if p.returncode:
                raise ValueError(p.stderr[-1000:] or 'Java failed')
            self.response(200, p.stdout)
        except (ValueError, KeyError, subprocess.TimeoutExpired) as ex:
            self.response(400, json.dumps({'error': str(ex)}, ensure_ascii=False))


def main():
    compile_java()
    host = '127.0.0.1'
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f'Block Blast screenshot dashboard: http://{host}:{port}/', flush=True)
    HTTPServer((host, port), Handler).serve_forever()


if __name__ == '__main__':
    main()
