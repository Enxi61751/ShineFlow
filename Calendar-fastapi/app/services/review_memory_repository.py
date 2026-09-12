from __future__ import annotations

import hashlib
import json
import math
import re
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator, Optional
from uuid import uuid4

from app.schemas.review import AgentProfileInput, MemoryItem, ReviewHistoryItem, ReviewResult


_TOKEN_RE = re.compile(r"[a-zA-Z0-9_]{2,}|[\u4e00-\u9fff]")


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _iso_now() -> str:
    return _utc_now().isoformat()


def _json_dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _json_load(value: Optional[str], default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return default


def _fingerprint(content: str) -> str:
    normalized = re.sub(r"\s+", "", content).lower()
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _tokens(text: str) -> set[str]:
    raw = _TOKEN_RE.findall((text or "").lower())
    result = set(raw)
    chinese = "".join(token for token in raw if len(token) == 1 and "\u4e00" <= token <= "\u9fff")
    result.update(chinese[index : index + 2] for index in range(max(0, len(chinese) - 1)))
    return {token for token in result if token.strip()}


class ReviewMemoryRepository:
    """Small SQLite repository for durable review history and user memories."""

    def __init__(self, db_path: str) -> None:
        self.db_path = Path(db_path).expanduser()
        if not self.db_path.is_absolute():
            self.db_path = (Path.cwd() / self.db_path).resolve()
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(str(self.db_path), timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        return connection

    @contextmanager
    def _connection(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._connection() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS review_profiles (
                    user_id TEXT PRIMARY KEY,
                    assistant_name TEXT NOT NULL,
                    personality TEXT NOT NULL,
                    tone TEXT NOT NULL,
                    goals_json TEXT NOT NULL DEFAULT '[]',
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS schedule_reviews (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    review_date TEXT NOT NULL,
                    timezone TEXT NOT NULL,
                    request_json TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    model TEXT NOT NULL,
                    generated_by TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_schedule_reviews_user_date
                ON schedule_reviews(user_id, review_date DESC, created_at DESC);

                CREATE TABLE IF NOT EXISTS review_memories (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    content_fingerprint TEXT NOT NULL,
                    importance INTEGER NOT NULL DEFAULT 3,
                    keywords_json TEXT NOT NULL DEFAULT '[]',
                    source_review_id TEXT,
                    active INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(user_id, content_fingerprint)
                );

                CREATE INDEX IF NOT EXISTS idx_review_memories_user_active
                ON review_memories(user_id, active, updated_at DESC);
                """
            )

    def upsert_profile(self, user_id: str, profile: AgentProfileInput) -> datetime:
        updated_at = _iso_now()
        with self._connection() as connection:
            connection.execute(
                """
                INSERT INTO review_profiles(
                    user_id, assistant_name, personality, tone, goals_json, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    assistant_name = excluded.assistant_name,
                    personality = excluded.personality,
                    tone = excluded.tone,
                    goals_json = excluded.goals_json,
                    updated_at = excluded.updated_at
                """,
                (
                    user_id,
                    profile.assistant_name,
                    profile.personality,
                    profile.tone,
                    _json_dump(profile.goals),
                    updated_at,
                ),
            )
        return datetime.fromisoformat(updated_at)

    def get_profile(self, user_id: str) -> Optional[tuple[AgentProfileInput, datetime]]:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM review_profiles WHERE user_id = ?",
                (user_id,),
            ).fetchone()
        if row is None:
            return None
        profile = AgentProfileInput(
            assistant_name=row["assistant_name"],
            personality=row["personality"],
            tone=row["tone"],
            goals=_json_load(row["goals_json"], []),
        )
        return profile, datetime.fromisoformat(row["updated_at"])

    def save_review(
        self,
        *,
        review_id: str,
        user_id: str,
        review_date: str,
        timezone_name: str,
        request_payload: dict[str, Any],
        result: ReviewResult,
        model: str,
        generated_by: str,
        created_at: datetime,
    ) -> None:
        with self._connection() as connection:
            connection.execute(
                """
                INSERT INTO schedule_reviews(
                    id, user_id, review_date, timezone, request_json,
                    result_json, model, generated_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    review_id,
                    user_id,
                    review_date,
                    timezone_name,
                    _json_dump(request_payload),
                    _json_dump(result.model_dump()),
                    model,
                    generated_by,
                    created_at.isoformat(),
                ),
            )

    def get_review(self, review_id: str) -> Optional[ReviewHistoryItem]:
        with self._connection() as connection:
            row = connection.execute(
                "SELECT * FROM schedule_reviews WHERE id = ?",
                (review_id,),
            ).fetchone()
        return self._row_to_review(row) if row is not None else None

    def list_reviews(self, user_id: str, limit: int = 20) -> list[ReviewHistoryItem]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT * FROM schedule_reviews
                WHERE user_id = ?
                ORDER BY review_date DESC, created_at DESC
                LIMIT ?
                """,
                (user_id, max(1, min(limit, 100))),
            ).fetchall()
        return [self._row_to_review(row) for row in rows]

    def _row_to_review(self, row: sqlite3.Row) -> ReviewHistoryItem:
        return ReviewHistoryItem(
            review_id=row["id"],
            user_id=row["user_id"],
            review_date=row["review_date"],
            generated_by=row["generated_by"],
            model=row["model"],
            result=ReviewResult.model_validate(_json_load(row["result_json"], {})),
            created_at=datetime.fromisoformat(row["created_at"]),
        )

    def add_memories(
        self,
        *,
        user_id: str,
        source_review_id: str,
        memories: Iterable[dict[str, Any]],
    ) -> list[MemoryItem]:
        saved_ids: list[str] = []
        now = _iso_now()
        with self._connection() as connection:
            for candidate in memories:
                content = str(candidate.get("content") or "").strip()
                if len(content) < 4:
                    continue
                kind = str(candidate.get("kind") or "pattern").strip()[:40] or "pattern"
                try:
                    importance = int(candidate.get("importance") or 3)
                except (TypeError, ValueError):
                    importance = 3
                importance = max(1, min(importance, 5))
                keywords = candidate.get("keywords") or sorted(_tokens(content))[:30]
                if not isinstance(keywords, list):
                    keywords = []
                memory_id = str(uuid4())
                fingerprint = _fingerprint(content)
                connection.execute(
                    """
                    INSERT INTO review_memories(
                        id, user_id, kind, content, content_fingerprint, importance,
                        keywords_json, source_review_id, active, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                    ON CONFLICT(user_id, content_fingerprint) DO UPDATE SET
                        kind = excluded.kind,
                        importance = MAX(review_memories.importance, excluded.importance),
                        keywords_json = excluded.keywords_json,
                        source_review_id = excluded.source_review_id,
                        active = 1,
                        updated_at = excluded.updated_at
                    """,
                    (
                        memory_id,
                        user_id,
                        kind,
                        content,
                        fingerprint,
                        importance,
                        _json_dump(keywords[:30]),
                        source_review_id,
                        now,
                        now,
                    ),
                )
                row = connection.execute(
                    """
                    SELECT id FROM review_memories
                    WHERE user_id = ? AND content_fingerprint = ?
                    """,
                    (user_id, fingerprint),
                ).fetchone()
                if row is not None:
                    saved_ids.append(row["id"])

        return self.get_memories_by_ids(user_id, saved_ids)

    def get_memories_by_ids(self, user_id: str, memory_ids: Iterable[str]) -> list[MemoryItem]:
        ids = list(dict.fromkeys(memory_ids))
        if not ids:
            return []
        placeholders = ",".join("?" for _ in ids)
        with self._connection() as connection:
            rows = connection.execute(
                f"""
                SELECT * FROM review_memories
                WHERE user_id = ? AND id IN ({placeholders}) AND active = 1
                """,
                [user_id, *ids],
            ).fetchall()
        mapped = {row["id"]: self._row_to_memory(row) for row in rows}
        return [mapped[memory_id] for memory_id in ids if memory_id in mapped]

    def list_memories(self, user_id: str, limit: int = 50) -> list[MemoryItem]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT * FROM review_memories
                WHERE user_id = ? AND active = 1
                ORDER BY importance DESC, updated_at DESC
                LIMIT ?
                """,
                (user_id, max(1, min(limit, 200))),
            ).fetchall()
        return [self._row_to_memory(row) for row in rows]

    def search_memories(self, user_id: str, query: str, limit: int = 8) -> list[MemoryItem]:
        with self._connection() as connection:
            rows = connection.execute(
                """
                SELECT * FROM review_memories
                WHERE user_id = ? AND active = 1
                ORDER BY updated_at DESC
                LIMIT 300
                """,
                (user_id,),
            ).fetchall()

        query_tokens = _tokens(query)
        now = _utc_now()
        scored: list[tuple[float, sqlite3.Row]] = []
        for row in rows:
            memory_tokens = _tokens(row["content"])
            memory_tokens.update(_json_load(row["keywords_json"], []))
            overlap = len(query_tokens.intersection(memory_tokens))
            union = max(1, len(query_tokens.union(memory_tokens)))
            lexical_score = overlap / union
            try:
                age_days = max(0.0, (now - datetime.fromisoformat(row["updated_at"])).total_seconds() / 86400)
            except ValueError:
                age_days = 365
            recency_score = math.exp(-age_days / 45.0)
            importance_score = int(row["importance"]) / 5.0
            score = lexical_score * 5.0 + recency_score * 0.8 + importance_score
            scored.append((score, row))

        scored.sort(key=lambda item: item[0], reverse=True)
        selected = [self._row_to_memory(row) for _, row in scored[: max(1, min(limit, 30))]]
        return selected

    def delete_memory(self, user_id: str, memory_id: str) -> int:
        with self._connection() as connection:
            cursor = connection.execute(
                """
                UPDATE review_memories SET active = 0, updated_at = ?
                WHERE user_id = ? AND id = ? AND active = 1
                """,
                (_iso_now(), user_id, memory_id),
            )
        return cursor.rowcount

    def clear_memories(self, user_id: str) -> int:
        with self._connection() as connection:
            cursor = connection.execute(
                """
                UPDATE review_memories SET active = 0, updated_at = ?
                WHERE user_id = ? AND active = 1
                """,
                (_iso_now(), user_id),
            )
        return cursor.rowcount

    @staticmethod
    def _row_to_memory(row: sqlite3.Row) -> MemoryItem:
        return MemoryItem(
            id=row["id"],
            user_id=row["user_id"],
            kind=row["kind"],
            content=row["content"],
            importance=int(row["importance"]),
            source_review_id=row["source_review_id"],
            created_at=datetime.fromisoformat(row["created_at"]),
            updated_at=datetime.fromisoformat(row["updated_at"]),
        )
