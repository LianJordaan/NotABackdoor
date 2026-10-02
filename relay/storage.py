"""Small durable relay registry. It stores hashes, never panel credentials."""

from __future__ import annotations

import hashlib
import hmac
import os
from pathlib import Path
import secrets
import sqlite3
import time


PAIR_SECONDS = 15 * 60
MAX_PAIR_ATTEMPTS = 5
MAX_PENDING_PER_IP = 3


def secret_hash(value: str) -> str:
    return hashlib.sha256(value.encode("ascii")).hexdigest()


class Registry:
    def __init__(self, path: Path):
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if path.parent.is_symlink():
            raise ValueError("Relay data directory cannot be a symbolic link")
        if os.name != "nt":
            path.parent.chmod(0o700)
        if path.is_symlink():
            raise ValueError("Relay database cannot be a symbolic link")
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.last_attempt_prune = 0.0
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS pairings (
                id TEXT PRIMARY KEY,
                code_hash TEXT NOT NULL,
                pending_hash TEXT NOT NULL,
                device_hash TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                claimed INTEGER NOT NULL DEFAULT 0,
                client_ip TEXT NOT NULL DEFAULT ''
            );
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                secret_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                revoked INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS attempt_buckets (
                key TEXT PRIMARY KEY,
                tokens REAL NOT NULL,
                updated REAL NOT NULL
            );
        """)
        if "client_ip" not in {row[1] for row in self.db.execute("PRAGMA table_info(pairings)")}:
            self.db.execute("ALTER TABLE pairings ADD COLUMN client_ip TEXT NOT NULL DEFAULT ''")
            self.db.commit()
        try:
            path.chmod(0o600)
        except OSError:
            pass

    def close(self) -> None:
        self.db.close()

    def start_pair(self, pair_id: str, code_hash: str, pending_hash: str,
                   device_hash: str, client_ip: str = "", *, now: int | None = None) -> int:
        now = int(time.time()) if now is None else now
        self.db.execute("DELETE FROM pairings WHERE expires_at <= ? AND claimed = 0", (now,))
        pending_count = self.db.execute(
            "SELECT COUNT(*) FROM pairings WHERE claimed = 0 AND expires_at > ?", (now,)
        ).fetchone()[0]
        if pending_count >= 100:
            raise ValueError("Too many pending pairings")
        if self.db.execute("SELECT COUNT(*) FROM pairings WHERE client_ip = ? AND claimed = 0 "
                           "AND expires_at > ?", (client_ip, now)).fetchone()[0] >= MAX_PENDING_PER_IP:
            raise ValueError("Too many pending pairings from this address")
        expires = now + PAIR_SECONDS
        self.db.execute(
            "INSERT INTO pairings(id, code_hash, pending_hash, device_hash, expires_at, client_ip) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (pair_id, code_hash, pending_hash, device_hash, expires, client_ip),
        )
        self.db.commit()
        return expires

    def allow_attempt(self, key: str, capacity: int, window: int, *, now: float | None = None) -> bool:
        """Persist a token bucket across service restarts. No passwords are stored."""
        now = time.time() if now is None else now
        if now - self.last_attempt_prune >= 600:
            self.db.execute("DELETE FROM attempt_buckets WHERE updated < ?", (now - 86400,))
            self.last_attempt_prune = now
        row = self.db.execute("SELECT tokens, updated FROM attempt_buckets WHERE key = ?", (key,)).fetchone()
        if row is None:
            if self.db.execute("SELECT COUNT(*) FROM attempt_buckets").fetchone()[0] >= 50_000:
                self.db.commit()
                return False
            tokens = float(capacity)
            updated = now
        else:
            updated = max(now, row["updated"])
            tokens = min(float(capacity), row["tokens"]
                         + max(0.0, now - row["updated"]) * capacity / window)
        allowed = tokens >= 1.0
        if allowed:
            tokens -= 1.0
        self.db.execute("INSERT INTO attempt_buckets(key, tokens, updated) VALUES (?, ?, ?) "
                        "ON CONFLICT(key) DO UPDATE SET tokens=excluded.tokens, updated=excluded.updated",
                        (key, tokens, updated))
        self.db.commit()
        return allowed

    def pair_available(self, pair_id: str, *, now: int | None = None) -> bool:
        now = int(time.time()) if now is None else now
        row = self.db.execute(
            "SELECT claimed, expires_at, attempts FROM pairings WHERE id = ?", (pair_id,)
        ).fetchone()
        return bool(row and not row["claimed"] and now < row["expires_at"]
                    and row["attempts"] < MAX_PAIR_ATTEMPTS)

    def claim(self, pair_id: str, code: str, *, now: int | None = None) -> bool:
        now = int(time.time()) if now is None else now
        row = self.db.execute("SELECT * FROM pairings WHERE id = ?", (pair_id,)).fetchone()
        if not row or row["claimed"] or now >= row["expires_at"] or row["attempts"] >= MAX_PAIR_ATTEMPTS:
            return False
        self.db.execute("UPDATE pairings SET attempts = attempts + 1 WHERE id = ?", (pair_id,))
        if not hmac.compare_digest(row["code_hash"], secret_hash(code)):
            self.db.commit()
            return False
        self.db.execute("INSERT INTO devices(id, secret_hash, created_at) VALUES (?, ?, ?)",
                        (pair_id, row["device_hash"], now))
        self.db.execute("UPDATE pairings SET claimed = 1, client_ip = '' WHERE id = ?", (pair_id,))
        self.db.commit()
        return True

    def pair_status(self, pair_id: str, pending_token: str, *, now: int | None = None) -> str:
        now = int(time.time()) if now is None else now
        row = self.db.execute(
            "SELECT claimed, expires_at, pending_hash FROM pairings WHERE id = ?", (pair_id,)
        ).fetchone()
        if not row or not hmac.compare_digest(row["pending_hash"], secret_hash(pending_token)):
            return "unknown"
        if row["claimed"]:
            return "claimed"
        return "expired" if now >= row["expires_at"] else "pending"

    def cancel_pair(self, pair_id: str, pending_token: str) -> bool:
        row = self.db.execute(
            "SELECT pending_hash, claimed FROM pairings WHERE id = ?", (pair_id,)
        ).fetchone()
        if not row or not hmac.compare_digest(row["pending_hash"], secret_hash(pending_token)):
            return False
        self.db.execute("UPDATE pairings SET expires_at = 0, client_ip = '' WHERE id = ?", (pair_id,))
        if row["claimed"]:
            self.db.execute("UPDATE devices SET revoked = 1 WHERE id = ?", (pair_id,))
        self.db.commit()
        return True

    def authenticate(self, pair_id: str, device_secret: str) -> bool:
        row = self.db.execute(
            "SELECT secret_hash, revoked FROM devices WHERE id = ?", (pair_id,)
        ).fetchone()
        return bool(row and not row["revoked"]
                    and hmac.compare_digest(row["secret_hash"], secret_hash(device_secret)))

    def active(self, pair_id: str) -> bool:
        row = self.db.execute("SELECT revoked FROM devices WHERE id = ?", (pair_id,)).fetchone()
        return bool(row and not row["revoked"])

    def revoke(self, pair_id: str) -> None:
        self.db.execute("UPDATE devices SET revoked = 1 WHERE id = ?", (pair_id,))
        self.db.commit()


def load_signing_key(path: Path) -> bytes:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError("Relay signing key cannot be a symbolic link")
    if not path.exists():
        try:
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
                stream.write(secrets.token_bytes(32))
        except FileExistsError:
            pass
    key = path.read_bytes()
    if len(key) != 32:
        raise ValueError("Relay signing key has an invalid length")
    try:
        path.chmod(0o600)
    except OSError:
        pass
    return key
