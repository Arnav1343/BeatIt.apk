"""
iPod Music Server
==================
Flask backend with full search, suggestions, download progress polling, and streaming.
"""

import sys
import os
import threading
import time
from pathlib import Path

if sys.platform == "win32":
    os.system("")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from flask import Flask, jsonify, request, send_from_directory, send_file

from scraper import SongScraper


def _human_size(b):
    if b < 1024 * 1024:
        return f"{b / 1024:.0f} KB"
    return f"{b / (1024 * 1024):.1f} MB"

app = Flask(__name__, static_folder="static", static_url_path="/static")

DOWNLOADS_DIR = Path(__file__).parent / "downloads"
DOWNLOADS_DIR.mkdir(exist_ok=True)

# task_id -> { status, percent, result, error }
_tasks = {}
_tasks_lock = threading.Lock()


# ── Pages ─────────────────────────────────────────────────────────────

@app.route("/")
def index():
    return send_from_directory("static", "index.html")


# ── Search ────────────────────────────────────────────────────────────

@app.route("/api/search", methods=["POST"])
def api_search():
    """POST { query } -> metadata dict"""
    data = request.get_json(force=True)
    query = data.get("query", "").strip()
    if not query:
        return jsonify({"error": "No query provided"}), 400
    try:
        scraper = SongScraper(quality=320)
        result = scraper.search(query)
        return jsonify(result)
    except LookupError as e:
        return jsonify({"error": str(e)}), 404
    except Exception as e:
        return jsonify({"error": f"Search failed: {e}"}), 500


@app.route("/api/suggestions", methods=["POST"])
def api_suggestions():
    """POST { query } -> list of suggestion dicts (quick top-5 music results)"""
    data = request.get_json(force=True)
    query = data.get("query", "").strip()
    if not query:
        return jsonify([])
    try:
        import yt_dlp
        import re
        # Append "song" to bias results toward music
        music_query = query + " song"
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "extract_flat": True,
            "default_search": "ytsearch15",
            "noplaylist": True,
            "skip_download": True,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(music_query, download=False)
        entries = info.get("entries", []) or []

        # Filter: only keep results that look like songs
        REJECT = re.compile(
            r"(#shorts|shorts|cricket|wicket|ipl|match|highlights|reaction|gameplay|tutorial|podcast|vlog|unboxing|review|trailer|teaser|behind.the.scenes|interview|news|cooking|recipe|workout|fitness)",
            re.IGNORECASE,
        )
        out = []
        for e in entries:
            if not e:
                continue
            dur = e.get("duration") or 0
            title = e.get("title", "")
            # Skip shorts (<60s), very long videos (>10 min), and reject keywords
            if dur > 0 and dur < 60:
                continue
            if dur > 600:
                continue
            if REJECT.search(title):
                continue
            out.append({
                "title": title,
                "artist": e.get("uploader", ""),
                "duration": dur,
                "url": e.get("url") or e.get("webpage_url") or f"https://www.youtube.com/watch?v={e.get('id','')}",
                "thumbnail": e.get("thumbnail", ""),
            })
            if len(out) >= 5:
                break
        return jsonify(out)
    except Exception:
        return jsonify([])


# ── Download ──────────────────────────────────────────────────────────

@app.route("/api/download", methods=["POST"])
def api_download():
    """POST { url, title, quality, codec } -> { task_id }. Download runs in background."""
    data = request.get_json(force=True)
    url = data.get("url", "").strip()
    title = data.get("title", "Unknown")
    codec = data.get("codec", "mp3").lower()
    if codec not in ("mp3", "opus"):
        codec = "mp3"

    # Quality defaults per codec
    if codec == "opus":
        default_q = 128
        valid_q = (64, 96, 128, 160, 192)
    else:
        default_q = 320
        valid_q = (128, 192, 256, 320)

    quality = int(data.get("quality", default_q))
    if quality not in valid_q:
        quality = default_q

    if not url:
        return jsonify({"error": "No URL provided"}), 400

    task_id = str(int(time.time() * 1000))
    with _tasks_lock:
        _tasks[task_id] = {"status": "starting", "percent": 0}

    def _run():
        def _hook(d):
            with _tasks_lock:
                if d["status"] == "downloading":
                    total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                    dl = d.get("downloaded_bytes", 0)
                    pct = int(dl / total * 100) if total else 0
                    _tasks[task_id] = {"status": "downloading", "percent": pct}
                elif d["status"] == "finished":
                    _tasks[task_id] = {"status": "converting", "percent": 100}

        try:
            scraper = SongScraper(quality=quality, codec=codec)
            audio = scraper.download(url=url, output_dir=DOWNLOADS_DIR, progress_hook=_hook)
            size = audio.stat().st_size
            with _tasks_lock:
                _tasks[task_id] = {
                    "status": "done", "percent": 100,
                    "result": {
                        "filename": audio.name,
                        "title": title,
                        "size": size,
                        "size_human": _human_size(size),
                        "codec": codec,
                    }
                }
        except Exception as ex:
            with _tasks_lock:
                _tasks[task_id] = {"status": "error", "percent": 0, "error": str(ex)}

    threading.Thread(target=_run, daemon=True).start()
    return jsonify({"task_id": task_id})


@app.route("/api/progress/<task_id>")
def api_progress(task_id):
    """GET -> { status, percent, result?, error? }"""
    with _tasks_lock:
        task = _tasks.get(task_id, {"status": "unknown", "percent": 0})
    return jsonify(task)


# ── Library ───────────────────────────────────────────────────────────

@app.route("/api/library")
def api_library():
    songs = []
    # Collect both MP3 and Opus files
    patterns = ["*.mp3", "*.opus", "*.ogg"]
    all_files = []
    for pat in patterns:
        all_files.extend(DOWNLOADS_DIR.glob(pat))
    all_files.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    for f in all_files:
        s = f.stat()
        songs.append({
            "filename": f.name,
            "title": f.stem,
            "size": s.st_size,
            "size_human": _human_size(s.st_size),
            "modified": s.st_mtime,
            "codec": "opus" if f.suffix in (".opus", ".ogg") else "mp3",
        })
    return jsonify(songs)


@app.route("/api/music/<path:filename>")
def api_music(filename):
    fp = DOWNLOADS_DIR / filename
    if not fp.exists():
        return jsonify({"error": "File not found"}), 404
    mime = "audio/ogg" if fp.suffix in (".opus", ".ogg") else "audio/mpeg"
    return send_file(fp, mimetype=mime)


@app.route("/api/delete", methods=["POST"])
def api_delete():
    data = request.get_json(force=True)
    fp = DOWNLOADS_DIR / data.get("filename", "")
    if fp.exists() and fp.suffix == ".mp3":
        fp.unlink()
        return jsonify({"success": True})
    return jsonify({"error": "File not found"}), 404


# ── Run ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("\n  iPod Music Server -> http://localhost:5000\n")
    app.run(host="0.0.0.0", port=5000, debug=False)
