import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

# Keep module-level services isolated from the repository workspace and local GGUF files.
_MODULE_DB_DIR = tempfile.TemporaryDirectory()
os.environ.setdefault("REMOTE_LLM_BASE_URL", "http://review-api-test.invalid")
os.environ.setdefault("REMOTE_LLM_MODEL", "review-api-test-model")
os.environ["REVIEW_MEMORY_DB_PATH"] = str(Path(_MODULE_DB_DIR.name) / "module-review.db")

from app.api import review as review_api  # noqa: E402
from app.services.review_memory_repository import ReviewMemoryRepository  # noqa: E402


class ReviewApiIntegrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.repository = ReviewMemoryRepository(
            str(Path(self.temp_dir.name) / "review-api.db")
        )
        self.previous_repository = review_api.schedule_review_service.repository
        review_api.schedule_review_service.repository = self.repository

        app = FastAPI()
        app.include_router(review_api.router)
        self.client = TestClient(app)

    def tearDown(self) -> None:
        self.client.close()
        review_api.schedule_review_service.repository = self.previous_repository
        self.temp_dir.cleanup()

    def test_generate_profile_history_memory_and_clear_flow(self) -> None:
        payload = {
            "user_id": "api-user-1",
            "review_date": "2026-08-24",
            "timezone": "Asia/Shanghai",
            "reflection": "上午完成了开发，下午精力偏低。",
            "mood": "平静",
            "energy_level": 3,
            "satisfaction_score": 4,
            "events": [
                {
                    "event_id": "event-1",
                    "title": "完成复盘接口",
                    "completion_status": "completed"
                },
                {
                    "event_id": "event-2",
                    "title": "补充部署文档",
                    "completion_status": "not_completed"
                }
            ],
            "agent_profile": {
                "assistant_name": "小光",
                "personality": "关注长期规律的成长伙伴",
                "tone": "温暖、具体、不说教",
                "goals": ["稳定交付产品功能"]
            }
        }

        with patch(
            "app.services.schedule_review_service.llm_service.chat",
            new=AsyncMock(side_effect=RuntimeError("model offline")),
        ):
            generated = self.client.post("/api/reviews/generate", json=payload)

        self.assertEqual(generated.status_code, 200, generated.text)
        generated_body = generated.json()
        self.assertTrue(generated_body["success"])
        self.assertEqual(generated_body["generated_by"], "rule_fallback")
        self.assertEqual(generated_body["result"]["completion_score"], 50)
        review_id = generated_body["review_id"]

        profile = self.client.get("/api/reviews/profile/api-user-1")
        self.assertEqual(profile.status_code, 200, profile.text)
        self.assertEqual(profile.json()["profile"]["assistant_name"], "小光")

        memories = self.client.get("/api/reviews/memories/api-user-1")
        self.assertEqual(memories.status_code, 200, memories.text)
        self.assertGreaterEqual(len(memories.json()["items"]), 2)

        history = self.client.get("/api/reviews/history/api-user-1")
        self.assertEqual(history.status_code, 200, history.text)
        self.assertEqual(history.json()["items"][0]["review_id"], review_id)

        detail = self.client.get(f"/api/reviews/{review_id}")
        self.assertEqual(detail.status_code, 200, detail.text)
        self.assertEqual(detail.json()["user_id"], "api-user-1")

        cleared = self.client.delete("/api/reviews/memories/api-user-1")
        self.assertEqual(cleared.status_code, 200, cleared.text)
        self.assertGreaterEqual(cleared.json()["deleted"], 2)

        empty = self.client.get("/api/reviews/memories/api-user-1")
        self.assertEqual(empty.status_code, 200, empty.text)
        self.assertEqual(empty.json()["items"], [])

        missing = self.client.get("/api/reviews/not-a-review")
        self.assertEqual(missing.status_code, 404)


if __name__ == "__main__":
    unittest.main()
