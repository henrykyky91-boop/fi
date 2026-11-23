#!/usr/bin/env python3
"""
Spur Server - dein eigener Foto-Server (Raspberry Pi).

Nur Python-Standardbibliothek. Optional:
  sudo apt install python3-pil ffmpeg   (Vorschaubilder fuer Imports / Videos)

Befehle:
  python3 spur_server.py serve  --data /mnt/server013/spur
  python3 spur_server.py import-snapchat --data /mnt/server013/spur  <export-ordner-oder-zips...>
  python3 spur_server.py token  --data /mnt/server013/spur  [--reset]
  python3 spur_server.py stats  --data /mnt/server013/spur
"""
import argparse
import hashlib
import hmac
import io
import json
import mimetypes
import os
import re
import secrets
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from PIL import Image, ImageOps  # type: ignore
    HAVE_PIL = True
except Exception:  # pragma: no cover
    HAVE_PIL = False

VERSION = "1.0.0"
ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,96}$")
KINDS = ("media", "thumb")
MAX_CHUNK = 64 * 1024 * 1024
TRASH_DAYS = 30
UA = "SpurServer/%s (private photo server)" % VERSION
HERE = os.path.dirname(os.path.abspath(__file__))

FIELDS = [
    # api name, column, type
    ("id", "id", str), ("type", "type", str), ("source", "source", str),
    ("takenAt", "taken_at", int), ("tzOffset", "tz_offset", int),
    ("lat", "lat", float), ("lon", "lon", float), ("accuracy", "accuracy", float),
    ("altitude", "altitude", float), ("heading", "heading", float), ("speed", "speed", float),
    ("address", "address", str), ("city", "city", str), ("country", "country", str),
    ("width", "width", int), ("height", "height", int), ("durationMs", "duration_ms", int),
    ("size", "size", int), ("mime", "mime", str), ("sha256", "sha256", str),
    ("device", "device", str), ("camera", "camera", str),
    ("note", "note", str), ("tags", "tags", str), ("favorite", "favorite", bool),
    ("extra", "extra", str), ("updatedAt", "updated_at", int), ("deleted", "deleted", bool),
]
COLS = {a: c for a, c, _ in FIELDS}
TYPES = {a: t for a, _, t in FIELDS}
CLIENT_FIELDS = [a for a, _, _ in FIELDS if a not in ("updatedAt", "deleted", "size", "sha256", "mime")]
EDITABLE = ("note", "tags", "favorite", "address", "city", "country", "lat", "lon")

SCHEMA = """
CREATE TABLE IF NOT EXISTS media(
  id TEXT PRIMARY KEY, type TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'spur',
  taken_at INTEGER NOT NULL, tz_offset INTEGER DEFAULT 0,
  lat REAL, lon REAL, accuracy REAL, altitude REAL, heading REAL, speed REAL,
  address TEXT, city TEXT, country TEXT,
  width INTEGER DEFAULT 0, height INTEGER DEFAULT 0, duration_ms INTEGER DEFAULT 0,
  size INTEGER DEFAULT 0, mime TEXT, sha256 TEXT, device TEXT, camera TEXT,
  note TEXT DEFAULT '', tags TEXT DEFAULT '', favorite INTEGER DEFAULT 0, extra TEXT,
  file TEXT, thumb TEXT, created_at INTEGER, updated_at INTEGER NOT NULL,
  deleted INTEGER DEFAULT 0, deleted_at INTEGER);
CREATE INDEX IF NOT EXISTS idx_media_upd ON media(updated_at);
CREATE INDEX IF NOT EXISTS idx_media_taken ON media(taken_at);
CREATE TABLE IF NOT EXISTS geocache(key TEXT PRIMARY KEY, address TEXT, city TEXT, country TEXT);
"""


def now_ms():
    return int(time.time() * 1000)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ----------------------------------------------------------------------------- store

class Store:
    def __init__(self, data_dir):
        self.dir = os.path.abspath(data_dir)
        for sub in ("media", "thumbs", "incoming", "trash"):
            os.makedirs(os.path.join(self.dir, sub), exist_ok=True)
        self.db = sqlite3.connect(os.path.join(self.dir, "spur.db"), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.db.executescript(SCHEMA)
        self.lock = threading.RLock()
        self._last_ts = 0
        self.cfg_path = os.path.join(self.dir, "config.json")
        self.cfg = self._load_cfg()

    # config / token
    def _load_cfg(self):
        cfg = {}
        if os.path.exists(self.cfg_path):
            with open(self.cfg_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        if not cfg.get("token"):
            cfg["token"] = secrets.token_urlsafe(32)
            self._save_cfg(cfg)
        return cfg

    def _save_cfg(self, cfg):
        tmp = self.cfg_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
        os.replace(tmp, self.cfg_path)
        try:
            os.chmod(self.cfg_path, 0o600)
        except OSError:
            pass

    def reset_token(self):
        self.cfg["token"] = secrets.token_urlsafe(32)
        self._save_cfg(self.cfg)
        return self.cfg["token"]

    def check_token(self, tok):
        return bool(tok) and hmac.compare_digest(tok.encode(), self.cfg["token"].encode())

    # timestamps strictly increasing -> reliable sync cursor
    def stamp(self):
        with self.lock:
            t = max(now_ms(), self._last_ts + 1)
            row = self.db.execute("SELECT MAX(updated_at) FROM media").fetchone()
            if row and row[0] and row[0] >= t:
                t = row[0] + 1
            self._last_ts = t
            return t

    def path(self, rel):
        return os.path.join(self.dir, rel) if rel else None

    # rows
    @staticmethod
    def to_api(row):
        d = {}
        for a, c, t in FIELDS:
            v = row[c]
            if t is bool:
                v = bool(v)
            d[a] = v
        d["hasThumb"] = bool(row["thumb"])
        d["hasFile"] = bool(row["file"])
        return d

    def get(self, mid):
        with self.lock:
            return self.db.execute("SELECT * FROM media WHERE id=?", (mid,)).fetchone()

    def list_since(self, since, limit):
        with self.lock:
            rows = self.db.execute(
                "SELECT * FROM media WHERE updated_at>? ORDER BY updated_at LIMIT ?", (since, limit)
            ).fetchall()
        return [self.to_api(r) for r in rows]

    def insert(self, meta, file_rel, thumb_rel, size, sha, mime):
        vals = {}
        for a in CLIENT_FIELDS:
            if a in meta and meta[a] is not None:
                t = TYPES[a]
                try:
                    vals[COLS[a]] = int(bool(meta[a])) if t is bool else t(meta[a])
                except (TypeError, ValueError):
                    pass
        vals.update({"file": file_rel, "thumb": thumb_rel, "size": size, "sha256": sha, "mime": mime,
                     "created_at": now_ms(), "deleted": 0})
        vals.setdefault("type", "photo")
        vals.setdefault("source", "spur")
        vals.setdefault("taken_at", now_ms())
        with self.lock:
            vals["updated_at"] = self.stamp()
            cols = ",".join(vals.keys())
            q = ",".join("?" for _ in vals)
            self.db.execute("INSERT OR REPLACE INTO media(%s) VALUES(%s)" % (cols, q), list(vals.values()))
            self.db.commit()
        return self.get(vals["id"])

    def patch(self, mid, changes):
        sets, args = [], []
        for a in EDITABLE:
            if a in changes:
                v = changes[a]
                t = TYPES[a]
                if v is not None:
                    v = int(bool(v)) if t is bool else t(v)
                sets.append("%s=?" % COLS[a])
                args.append(v)
        if not sets:
            return self.get(mid)
        with self.lock:
            sets.append("updated_at=?")
            args.append(self.stamp())
            args.append(mid)
            self.db.execute("UPDATE media SET %s WHERE id=? AND deleted=0" % ",".join(sets), args)
            self.db.commit()
        return self.get(mid)

    def delete(self, mid):
        with self.lock:
            row = self.get(mid)
            if not row or row["deleted"]:
                return False
            for col in ("file", "thumb"):
                rel = row[col]
                if rel and os.path.exists(self.path(rel)):
                    dst = os.path.join(self.dir, "trash", os.path.basename(rel))
                    try:
                        shutil.move(self.path(rel), dst)
                    except OSError:
                        pass
            self.db.execute("UPDATE media SET deleted=1, deleted_at=?, file=NULL, thumb=NULL, updated_at=? WHERE id=?",
                            (now_ms(), self.stamp(), mid))
            self.db.commit()
            return True

    def purge_trash(self):
        cutoff = time.time() - TRASH_DAYS * 86400
        tdir = os.path.join(self.dir, "trash")
        for name in os.listdir(tdir):
            p = os.path.join(tdir, name)
            try:
                if os.path.getmtime(p) < cutoff:
                    os.remove(p)
            except OSError:
                pass

    def stats(self):
        with self.lock:
            r = self.db.execute(
                "SELECT COUNT(*), SUM(type='photo'), SUM(type='video'), SUM(source='snapchat'), "
                "COALESCE(SUM(size),0), SUM(lat IS NOT NULL) FROM media WHERE deleted=0").fetchone()
        du = shutil.disk_usage(self.dir)
        return {"count": r[0] or 0, "photos": r[1] or 0, "videos": r[2] or 0, "snapchat": r[3] or 0,
                "bytes": r[4] or 0, "withLocation": r[5] or 0,
                "diskTotal": du.total, "diskFree": du.free}

    # incoming files
    def part_path(self, mid, kind):
        return os.path.join(self.dir, "incoming", "%s.%s.part" % (mid, kind))

    def store_file(self, src, mid, taken_at, ext):
        d = datetime.fromtimestamp((taken_at or now_ms()) / 1000, tz=timezone.utc)
        rel = os.path.join("media", "%04d" % d.year, "%02d" % d.month, "%s.%s" % (mid, ext))
        os.makedirs(os.path.dirname(self.path(rel)), exist_ok=True)
        shutil.move(src, self.path(rel))
        return rel


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""):
            h.update(b)
    return h.hexdigest()


def ext_for(mime, fallback):
    m = (mime or "").lower()
    return {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp", "image/heic": "heic",
            "video/mp4": "mp4", "video/quicktime": "mov", "video/3gpp": "3gp"}.get(m, fallback)


def make_thumb(src, dst, is_video):
    """Vorschaubild erzeugen (Pillow fuer Fotos, ffmpeg fuer Videos). True bei Erfolg."""
    try:
        if is_video:
            if not shutil.which("ffmpeg"):
                return False
            tmp = dst + ".tmp.jpg"
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-ss", "0.3", "-i", src, "-frames:v", "1",
                            "-vf", "scale=480:-2", tmp], check=True, timeout=60,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            os.replace(tmp, dst)
            return True
        if not HAVE_PIL:
            return False
        with Image.open(src) as im:
            im = ImageOps.exif_transpose(im)
            im.thumbnail((480, 480))
            im.convert("RGB").save(dst, "JPEG", quality=80, optimize=True)
        return True
    except Exception as e:  # pragma: no cover
        log("thumb failed", src, e)
        return False


def image_size(p):
    if not HAVE_PIL:
        return 0, 0
    try:
        with Image.open(p) as im:
            im = ImageOps.exif_transpose(im)
            return im.size
    except Exception:
        return 0, 0


# ----------------------------------------------------------------------------- geocoding

class Geocoder(threading.Thread):
    """Ergaenzt fehlende Adressen per OpenStreetMap Nominatim (max. 1 Anfrage/Sekunde)."""

    def __init__(self, store):
        super().__init__(daemon=True)
        self.store = store
        self.wake = threading.Event()

    def run(self):
        while True:
            try:
                did = self.step()
            except Exception as e:  # pragma: no cover
                log("geocoder:", e)
                did = False
            if not did:
                self.wake.wait(600)
                self.wake.clear()

    def step(self):
        s = self.store
        with s.lock:
            rows = s.db.execute("SELECT id, lat, lon FROM media WHERE deleted=0 AND lat IS NOT NULL "
                                "AND (city IS NULL OR city='') AND (address IS NULL OR address='') "
                                "LIMIT 50").fetchall()
        if not rows:
            return False
        for r in rows:
            key = "%.3f,%.3f" % (r["lat"], r["lon"])
            with s.lock:
                c = s.db.execute("SELECT * FROM geocache WHERE key=?", (key,)).fetchone()
            if c is None:
                res = self.lookup(r["lat"], r["lon"])
                if res is None:
                    return False  # offline - spaeter erneut
                addr, city, country = res
                with s.lock:
                    s.db.execute("INSERT OR REPLACE INTO geocache VALUES(?,?,?,?)", (key, addr, city, country))
                    s.db.commit()
                time.sleep(1.1)
            else:
                addr, city, country = c["address"], c["city"], c["country"]
            s.patch(r["id"], {"address": addr or "", "city": city or "-", "country": country or ""})
        return True

    @staticmethod
    def lookup(lat, lon):
        url = ("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&accept-language=de"
               "&lat=%f&lon=%f" % (lat, lon))
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=20) as r:
                j = json.loads(r.read().decode())
            a = j.get("address", {})
            city = a.get("city") or a.get("town") or a.get("village") or a.get("municipality") or a.get("county")
            street = a.get("road") or a.get("pedestrian") or a.get("footway") or ""
            num = a.get("house_number") or ""
            line = " ".join(x for x in (street, num) if x)
            addr = ", ".join(x for x in (line, city) if x)
            return addr, city or "", a.get("country") or ""
        except Exception:
            return None


# ----------------------------------------------------------------------------- http

class Handler(BaseHTTPRequestHandler):
    server_version = "Spur/" + VERSION
    protocol_version = "HTTP/1.1"
    store = None  # type: Store
    geocoder = None

    def log_message(self, fmt, *args):
        if os.environ.get("SPUR_VERBOSE"):
            log(self.address_string(), fmt % args)

    # helpers
    def _send(self, code, body=b"", ctype="application/json", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code, obj, headers=None):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode(), headers=headers)

    def _err(self, code, msg):
        self._json(code, {"error": msg})

    def _body(self, limit=MAX_CHUNK + 1024):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("zu gross")
        return self.rfile.read(n) if n else b""

    def _token(self):
        h = self.headers.get("Authorization", "")
        if h.lower().startswith("bearer "):
            return h[7:].strip()
        for part in (self.headers.get("Cookie") or "").split(";"):
            k, _, v = part.strip().partition("=")
            if k == "spur_token":
                return urllib.parse.unquote(v)
        return None

    def _authed(self):
        if self.store.check_token(self._token()):
            return True
        self._err(401, "Nicht angemeldet")
        return False

    def _route(self):
        u = urllib.parse.urlparse(self.path)
        parts = [urllib.parse.unquote(p) for p in u.path.split("/") if p]
        return parts, urllib.parse.parse_qs(u.query)

    # dispatch
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        try:
            self._get()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log("GET error", self.path, repr(e))
            try:
                self._err(500, "Serverfehler")
            except Exception:
                pass

    def do_PUT(self):
        self._wrap(self._put)

    def do_POST(self):
        self._wrap(self._post)

    def do_PATCH(self):
        self._wrap(self._patch)

    def do_DELETE(self):
        self._wrap(self._delete)

    def _wrap(self, fn):
        try:
            fn()
        except ValueError as e:
            self._err(400, str(e))
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log(self.command, "error", self.path, repr(e))
            try:
                self._err(500, "Serverfehler")
            except Exception:
                pass

    # GET
    def _get(self):
        p, q = self._route()
        if not p or p == ["index.html"]:
            return self._static("index.html", "text/html; charset=utf-8")
        if p == ["datenschutz"]:
            return self._static("datenschutz.html", "text/html; charset=utf-8")
        if p[0] != "api":
            return self._err(404, "Nicht gefunden")
        if p == ["api", "ping"]:
            ok = self.store.check_token(self._token())
            out = {"name": "Spur", "version": VERSION, "auth": ok}
            if ok:
                out.update(self.store.stats())
            return self._json(200, out)
        if not self._authed():
            return
        if p == ["api", "media"]:
            since = int((q.get("since") or ["0"])[0])
            limit = max(1, min(5000, int((q.get("limit") or ["1000"])[0])))
            items = self.store.list_since(since, limit)
            cursor = items[-1]["updatedAt"] if items else since
            return self._json(200, {"items": items, "cursor": cursor, "more": len(items) == limit})
        if p == ["api", "stats"]:
            return self._json(200, self.store.stats())
        if len(p) == 4 and p[1] == "upload" and p[3] in KINDS:
            part = self.store.part_path(self._id(p[2]), p[3])
            return self._json(200, {"size": os.path.getsize(part) if os.path.exists(part) else 0})
        if len(p) == 3 and p[1] == "media":
            row = self.store.get(self._id(p[2]))
            return self._json(200, self.store.to_api(row)) if row else self._err(404, "Nicht gefunden")
        if len(p) == 4 and p[1] == "media" and p[3] in ("file", "thumb"):
            row = self.store.get(self._id(p[2]))
            if not row or row["deleted"]:
                return self._err(404, "Nicht gefunden")
            rel = row["file"] if p[3] == "file" else (row["thumb"] or (row["file"] if row["type"] == "photo" else None))
            if not rel or not os.path.exists(self.store.path(rel)):
                return self._err(404, "Keine Datei")
            ctype = (row["mime"] if rel == row["file"] else "image/jpeg") or "application/octet-stream"
            dl = (q.get("download") or ["0"])[0] == "1"
            return self._file(self.store.path(rel), ctype, dl)
        return self._err(404, "Nicht gefunden")

    def _id(self, mid):
        if not ID_RE.match(mid):
            raise ValueError("ungueltige id")
        return mid

    def _static(self, name, ctype):
        p = os.path.join(HERE, "web", name)
        if not os.path.exists(p):
            return self._err(404, "Nicht gefunden")
        with open(p, "rb") as f:
            self._send(200, f.read(), ctype)

    def _file(self, path, ctype, download=False):
        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        code = 200
        if rng and rng.startswith("bytes="):
            a, _, b = rng[6:].split(",")[0].partition("-")
            if a:
                start = int(a)
                if b:
                    end = min(int(b), size - 1)
            elif b:
                start = max(0, size - int(b))
            if start > end or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            code = 206
        length = end - start + 1
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "private, max-age=31536000, immutable")
        if download:
            self.send_header("Content-Disposition", 'attachment; filename="%s"' % os.path.basename(path))
        if code == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(path, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                b = f.read(min(256 * 1024, left))
                if not b:
                    break
                self.wfile.write(b)
                left -= len(b)

    # PUT /api/upload/<id>/<kind>?offset=N   (Datei in Stuecken, fortsetzbar)
    def _put(self):
        p, q = self._route()
        if not self._authed():
            return
        if not (len(p) == 4 and p[:2] == ["api", "upload"] and p[3] in KINDS):
            return self._err(404, "Nicht gefunden")
        mid = self._id(p[2])
        part = self.store.part_path(mid, p[3])
        cur = os.path.getsize(part) if os.path.exists(part) else 0
        offset = int((q.get("offset") or ["0"])[0])
        if offset != cur:
            # Client neu ausrichten (z. B. nach Verbindungsabbruch)
            self._body()
            return self._json(409, {"size": cur})
        data = self._body()
        with open(part, "ab") as f:
            f.write(data)
        self._json(200, {"size": cur + len(data)})

    # POST
    def _post(self):
        p, q = self._route()
        if p == ["api", "login"]:
            body = json.loads(self._body(4096) or b"{}")
            tok = (body.get("token") or "").strip()
            if not self.store.check_token(tok):
                return self._err(401, "Falscher Schluessel")
            cookie = "spur_token=%s; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000" % urllib.parse.quote(tok)
            return self._json(200, {"ok": True}, headers={"Set-Cookie": cookie})
        if not self._authed():
            return
        if len(p) == 4 and p[:2] == ["api", "media"] and p[3] == "commit":
            return self._commit(self._id(p[2]))
        return self._err(404, "Nicht gefunden")

    def _commit(self, mid):
        meta = json.loads(self._body(256 * 1024) or b"{}")
        meta["id"] = mid
        s = self.store
        existing = s.get(mid)
        if existing and not existing["deleted"]:
            return self._json(200, s.to_api(existing))
        part = s.part_path(mid, "media")
        if not os.path.exists(part):
            return self._err(409, "Keine Datei hochgeladen")
        size = os.path.getsize(part)
        want = int(meta.get("size") or 0)
        if want and want != size:
            return self._json(409, {"error": "Groesse passt nicht", "size": size})
        sha = sha256_file(part)
        if meta.get("sha256") and meta["sha256"].lower() != sha:
            os.remove(part)
            return self._json(409, {"error": "Pruefsumme falsch - Datei wird neu gesendet", "size": 0})
        is_video = meta.get("type") == "video"
        mime = meta.get("mime") or ("video/mp4" if is_video else "image/jpeg")
        file_rel = s.store_file(part, mid, int(meta.get("takenAt") or 0), ext_for(mime, "mp4" if is_video else "jpg"))
        thumb_rel = None
        tpart = s.part_path(mid, "thumb")
        tdst = os.path.join(s.dir, "thumbs", mid + ".jpg")
        if os.path.exists(tpart) and os.path.getsize(tpart) > 0:
            os.replace(tpart, tdst)
            thumb_rel = os.path.join("thumbs", mid + ".jpg")
        elif make_thumb(s.path(file_rel), tdst, is_video):
            thumb_rel = os.path.join("thumbs", mid + ".jpg")
        if not is_video and not meta.get("width"):
            meta["width"], meta["height"] = image_size(s.path(file_rel))
        row = s.insert(meta, file_rel, thumb_rel, size, sha, mime)
        if self.geocoder:
            self.geocoder.wake.set()
        log("gespeichert", mid, meta.get("type"), "%.1f MB" % (size / 1e6))
        self._json(200, s.to_api(row))

    def _patch(self):
        p, _ = self._route()
        if not self._authed():
            return
        if len(p) == 3 and p[:2] == ["api", "media"]:
            mid = self._id(p[2])
            if not self.store.get(mid):
                return self._err(404, "Nicht gefunden")
            row = self.store.patch(mid, json.loads(self._body(64 * 1024) or b"{}"))
            return self._json(200, self.store.to_api(row))
        return self._err(404, "Nicht gefunden")

    def _delete(self):
        p, _ = self._route()
        if not self._authed():
            return
        if len(p) == 3 and p[:2] == ["api", "media"]:
            self.store.delete(self._id(p[2]))
            return self._json(200, {"ok": True})
        return self._err(404, "Nicht gefunden")


def serve(args):
    store = Store(args.data)
    Handler.store = store
    if not args.no_geocode:
        Handler.geocoder = Geocoder(store)
        Handler.geocoder.start()

    def janitor():
        while True:
            store.purge_trash()
            # verwaiste Teil-Uploads (> 7 Tage) entfernen
            inc = os.path.join(store.dir, "incoming")
            for n in os.listdir(inc):
                fp = os.path.join(inc, n)
                try:
                    if os.path.getmtime(fp) < time.time() - 7 * 86400:
                        os.remove(fp)
                except OSError:
                    pass
            time.sleep(6 * 3600)

    threading.Thread(target=janitor, daemon=True).start()
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.daemon_threads = True
    log("Spur Server %s laeuft auf http://%s:%d  (Daten: %s)" % (VERSION, args.host, args.port, store.dir))
    log("Schluessel fuer die App:", store.cfg["token"])
    if not HAVE_PIL:
        log("Hinweis: python3-pil fehlt - Vorschaubilder fuer Imports werden nicht erzeugt.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


# ----------------------------------------------------------------------------- snapchat import

LOC_RE = re.compile(r"(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)")
FILE_RE = re.compile(r"(\d{4}-\d{2}-\d{2})_([0-9A-Za-z-]{8,})-(main|overlay)\.(\w+)$")
MEDIA_EXT = {"jpg", "jpeg", "png", "webp", "mp4", "mov", "heic"}


class ExportFiles:
    """Sammelt Dateien aus Export-Ordnern und/oder ZIP-Dateien (auch mehrteilige Exporte)."""

    def __init__(self, paths):
        self.entries = {}  # name -> (kind, container, member)
        self.json_entry = None
        self.zips = []
        for p in paths:
            p = os.path.abspath(p)
            if os.path.isdir(p):
                for root, _, files in os.walk(p):
                    for n in files:
                        self._add(n, ("dir", None, os.path.join(root, n)), os.path.join(root, n))
            elif zipfile.is_zipfile(p):
                z = zipfile.ZipFile(p)
                self.zips.append(z)
                for info in z.infolist():
                    if not info.is_dir():
                        self._add(os.path.basename(info.filename), ("zip", z, info.filename), info.filename)
            elif p.endswith(".json"):
                self.json_entry = ("dir", None, p)

    def _add(self, name, ref, full):
        if name == "memories_history.json":
            self.json_entry = ref
            return
        ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
        if ext in MEDIA_EXT and "memor" in full.lower():
            self.entries[name] = ref

    @staticmethod
    def read(ref):
        kind, z, member = ref
        if kind == "zip":
            return z.read(member)
        with open(member, "rb") as f:
            return f.read()

    def load_json(self):
        if not self.json_entry:
            return None
        return json.loads(self.read(self.json_entry).decode("utf-8-sig"))


def parse_snap_date(s):
    s = (s or "").replace(" UTC", "").strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d"):
        try:
            return int(datetime.strptime(s, fmt).replace(tzinfo=timezone.utc).timestamp() * 1000)
        except ValueError:
            pass
    return None


def parse_snap_loc(s):
    m = LOC_RE.search(s or "")
    if not m:
        return None, None
    lat, lon = float(m.group(1)), float(m.group(2))
    if abs(lat) < 1e-6 and abs(lon) < 1e-6:
        return None, None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None, None
    return lat, lon


def snap_mid(entry):
    for key in ("Download Link", "Media Download Url"):
        link = entry.get(key) or ""
        qs = urllib.parse.parse_qs(urllib.parse.urlparse(link).query)
        for k in ("mid", "media_id", "sid"):
            if qs.get(k):
                return qs[k][0]
    return None


def snap_download(entry):
    url = entry.get("Media Download Url")
    if not url:
        link = entry.get("Download Link") or ""
        if not link:
            return None
        base, _, query = link.partition("?")
        req = urllib.request.Request(base, data=query.encode(), method="POST",
                                     headers={"Content-Type": "application/x-www-form-urlencoded", "User-Agent": UA})
        with urllib.request.urlopen(req, timeout=40) as r:
            url = r.read().decode().strip()
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def split_download(data, is_video):
    """Snapchat liefert manchmal ein ZIP mit main + overlay."""
    if data[:2] == b"PK":
        main = overlay = None
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for n in z.namelist():
                if "overlay" in n:
                    overlay = z.read(n)
                elif main is None or "main" in n:
                    main = z.read(n)
        return main, overlay
    return data, None


def merge_overlay(main_bytes, overlay_bytes):
    if not (HAVE_PIL and overlay_bytes):
        return main_bytes
    try:
        base = ImageOps.exif_transpose(Image.open(io.BytesIO(main_bytes))).convert("RGBA")
        ov = Image.open(io.BytesIO(overlay_bytes)).convert("RGBA").resize(base.size)
        base.alpha_composite(ov)
        out = io.BytesIO()
        base.convert("RGB").save(out, "JPEG", quality=92)
        return out.getvalue()
    except Exception:
        return main_bytes


def import_snapchat(args):
    store = Store(args.data)
    src = ExportFiles(args.paths)
    data = src.load_json()
    if data is None:
        print("memories_history.json nicht gefunden. Gib den entpackten Export-Ordner oder die ZIP(s) an.")
        return 1
    items = data.get("Saved Media") if isinstance(data, dict) else data
    if not isinstance(items, list):
        print("Unbekanntes Format von memories_history.json")
        return 1

    by_mid, by_date = {}, {}
    for name, ref in src.entries.items():
        m = FILE_RE.search(name)
        if m:
            d = by_mid.setdefault(m.group(2).lower(), {})
            d[m.group(3)] = (name, ref)
        else:
            dm = re.match(r"(\d{4}-\d{2}-\d{2})", name)
            if dm and "overlay" not in name:
                by_date.setdefault(dm.group(1), []).append((name, ref))
    # Zusaetzlich: Dateien mit mid nach Datum gruppieren (Fallback, falls mid im JSON fehlt)
    for mid, d in by_mid.items():
        if "main" in d:
            m = FILE_RE.search(d["main"][0])
            by_date.setdefault(m.group(1), []).append(d["main"])
    for v in by_date.values():
        v.sort(key=lambda x: x[0])
    used = set()

    total = len(items)
    ok = skipped = missing = failed = 0
    print("Snapchat-Import: %d Memories gefunden, %d Dateien im Export." % (total, len(src.entries)))
    for i, e in enumerate(items, 1):
        try:
            taken = parse_snap_date(e.get("Date")) or now_ms()
            mtype = (e.get("Media Type") or "").lower()
            is_video = "video" in mtype
            lat, lon = parse_snap_loc(e.get("Location"))
            mid = snap_mid(e)
            uid = "snap-" + (re.sub(r"[^A-Za-z0-9_-]", "", mid)[:60] if mid else
                             hashlib.sha1(json.dumps(e, sort_keys=True).encode()).hexdigest()[:24])
            ex = store.get(uid)
            if ex is not None:
                skipped += 1
                continue
            main = overlay = None
            fname = None
            if mid and mid.lower() in by_mid and "main" in by_mid[mid.lower()]:
                fname, ref = by_mid[mid.lower()]["main"]
                main = src.read(ref)
                if "overlay" in by_mid[mid.lower()]:
                    overlay = src.read(by_mid[mid.lower()]["overlay"][1])
            else:
                day = datetime.fromtimestamp(taken / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
                for name, ref in by_date.get(day, []):
                    if name in used:
                        continue
                    if (name.lower().endswith((".mp4", ".mov"))) == is_video:
                        fname, main = name, src.read(ref)
                        break
            if fname:
                used.add(fname)
            if main is None and not args.no_download:
                try:
                    blob = snap_download(e)
                    if blob:
                        main, overlay = split_download(blob, is_video)
                except Exception as de:
                    print("  [%d/%d] Download fehlgeschlagen (%s) - Links sind nur ca. 7 Tage gueltig." % (i, total, de))
            if main is None:
                missing += 1
                continue
            if overlay and not is_video and not args.no_overlay:
                main = merge_overlay(main, overlay)
            ext = "mp4" if is_video else ("png" if main[:4] == b"\x89PNG" else "jpg")
            mime = "video/mp4" if is_video else ("image/png" if ext == "png" else "image/jpeg")
            part = store.part_path(uid, "media")
            with open(part, "wb") as f:
                f.write(main)
            size = os.path.getsize(part)
            sha = sha256_file(part)
            file_rel = store.store_file(part, uid, taken, ext)
            tdst = os.path.join(store.dir, "thumbs", uid + ".jpg")
            thumb_rel = os.path.join("thumbs", uid + ".jpg") if make_thumb(store.path(file_rel), tdst, is_video) else None
            w, h = (0, 0) if is_video else image_size(store.path(file_rel))
            meta = {"id": uid, "type": "video" if is_video else "photo", "source": "snapchat",
                    "takenAt": taken, "tzOffset": 0, "lat": lat, "lon": lon, "width": w, "height": h,
                    "extra": json.dumps({"snapDate": e.get("Date"), "snapLocation": e.get("Location"),
                                         "file": fname})}
            store.insert(meta, file_rel, thumb_rel, size, sha, mime)
            ok += 1
            if ok % 25 == 0 or i == total:
                print("  %d/%d importiert ..." % (i, total), flush=True)
        except KeyboardInterrupt:
            print("Abgebrochen - beim naechsten Start geht es dort weiter.")
            break
        except Exception as ex2:
            failed += 1
            print("  Fehler bei Eintrag %d: %r" % (i, ex2))
    print("Fertig: %d neu, %d schon vorhanden, %d ohne Datei, %d Fehler." % (ok, skipped, missing, failed))
    if missing:
        print("Tipp: Beim Export 'Export your Memories' aktivieren, dann sind die Dateien im ZIP enthalten.")
    return 0


# ----------------------------------------------------------------------------- cli

def main(argv=None):
    ap = argparse.ArgumentParser(prog="spur_server", description="Spur - eigener Foto-Server")
    sub = ap.add_subparsers(dest="cmd", required=True)
    default_data = os.environ.get("SPUR_DATA", "./spur-data")

    s = sub.add_parser("serve", help="Server starten")
    s.add_argument("--data", default=default_data)
    s.add_argument("--host", default="0.0.0.0")
    s.add_argument("--port", type=int, default=int(os.environ.get("SPUR_PORT", "8787")))
    s.add_argument("--no-geocode", action="store_true", help="keine Adressen von OpenStreetMap holen")

    i = sub.add_parser("import-snapchat", help="Snapchat-Memories importieren")
    i.add_argument("--data", default=default_data)
    i.add_argument("--no-download", action="store_true", help="nur Dateien aus dem Export, nichts herunterladen")
    i.add_argument("--no-overlay", action="store_true", help="Text/Sticker-Overlay nicht einbrennen")
    i.add_argument("paths", nargs="+", help="Export-Ordner, ZIP-Datei(en) oder memories_history.json")

    t = sub.add_parser("token", help="App-Schluessel anzeigen")
    t.add_argument("--data", default=default_data)
    t.add_argument("--reset", action="store_true")

    st = sub.add_parser("stats", help="Statistik")
    st.add_argument("--data", default=default_data)

    args = ap.parse_args(argv)
    if args.cmd == "serve":
        return serve(args)
    if args.cmd == "import-snapchat":
        return import_snapchat(args)
    if args.cmd == "token":
        store = Store(args.data)
        print(store.reset_token() if args.reset else store.cfg["token"])
        return 0
    if args.cmd == "stats":
        print(json.dumps(Store(args.data).stats(), indent=2))
        return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
