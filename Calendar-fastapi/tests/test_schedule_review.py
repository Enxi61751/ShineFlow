import asyncio
import os
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path
from unittest.mock import AsyncMock, patch

# Prevent module-level services from loading a GGUF model or creating data in the repo.
_MODULE_DB_DIR = tempfile.TemporaryDirectory()
os.environ.setdefault("REMOTE_LLM_BASE_URL", "http://review-test.invalid")
os.environ.setdefault("REMOTE_LLM_MODEL", "review-test-model")
os.environ["REVIEW_MEMORY_DB_PATH"] = str(Path(_MODULE_DB_DIR.name) / "module-review.db")

from app.schemas.review import (  # noqa: E402
    AgentProfileInput,
    ReviewEventInput,
    ReviewGenerateRequest,
    ReviewResult,
)
from app.services.review_memory_repository import ReviewMemoryRepository  # noqa: E402
from app.services.schedule_review_service import ScheduleReviewService  # noqa: E402


class ReviewMemoryRepositoryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp_dir.name) / "review_memory.db"
        self.repository = ReviewMemoryRepository(str(self.db_path))

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def test_profile_and_review_are_persisted(self) -> None:
        profile = AgentProfileInput(
            assistant_name="小光",
            personality="理性、结构清晰的行动教练",
            tone="直接、具体",
            goals=["完成毕业设计", "稳定早睡"],
        )
        updated_at = self.repository.upsert_profile("user-1", profile)

        stored = self.repository.get_profile("user-1")
        self.assertIsNotNone(stored)
        stored_profile, stored_updated_at = stored
        self.assertEqual(stored_profile, profile)
        self.assertEqual(stored_updated_at, updated_at)

        result = ReviewResult(
            headline="稳步推进",
            summary="完成了主要任务。",
            achievements=["完成核心编码"],
            completion_score=80,
        )
        created_at = datetime.now(timezone.utc)
        self.repository.save_review(
            review_id="review-1",
            user_id="user-1",
            review_date="2026-08-24",
            timezone_name="Asia/Shanghai",
            request_payload={"reflection": "今天专注不错"},
            result=result,
            model="test-model",
            generated_by="llm",
            created_at=created_at,
        )

        stored_review = self.repository.get_review("review-1")
        self.assertIsNotNone(stored_review)
        self.assertEqual(stored_review.review_id, "review-1")
        self.assertEqual(stored_review.user_id, "user-1")
        self.assertEqual(stored_review.review_date, date(2026, 8, 24))
        self.assertEqual(stored_review.result.completion_score, 80)
        self.assertEqual(len(self.repository.list_reviews("user-1")), 1)

    def test_memories_are_deduplicated_ranked_and_cleared(self) -> None:
        first = self.repository.add_memories(
            user_id="user-1",
            source_review_id="review-1",
            memories=[
                {
                    "kind": "effective_strategy",
                    "content": "早晨先做九十分钟深度工作最有效",
                    "importance": 5,
                    "keywords": ["早晨", "深度工作"],
                },
                {
                    "kind": "preference",
                    "content": "喜欢在晚上整理第二天的任务清单",
                    "importance": 3,
                    "keywords": ["晚上", "任务清单"],
                },
            ],
        )
        duplicate = self.repository.add_memories(
            user_id="user-1",
            source_review_id="review-2",
            memories=[
                {
                    "kind": "effective_strategy",
                    "content": " 早晨先做九十分钟深度工作最有效 ",
                    "importance": 4,
                    "keywords": ["专注"],
                }
            ],
        )

        self.assertEqual(len(first), 2)
        self.assertEqual(len(duplicate), 1)
        self.assertEqual(len(self.repository.list_memories("user-1")), 2)
        self.assertEqual(first[0].id, duplicate[0].id)
        self.assertEqual(duplicate[0].importance, 5)

        ranked = self.repository.search_memories("user-1", "明天早晨安排深度工作", limit=2)
        self.assertEqual(ranked[0].id, first[0].id)

        deleted = self.repository.delete_memory("user-1", first[0].id)
        self.assertEqual(deleted, 1)
        self.assertEqual(len(self.repository.list_memories("user-1")), 1)

        cleared = self.repository.clear_memories("user-1")
        self.assertEqual(cleared, 1)
        self.assertEqual(self.repository.list_memories("user-1"), [])


class ScheduleReviewServiceTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        repository = ReviewMemoryRepository(
            str(Path(self.temp_dir.name) / "review_memory.db")
        )
        self.service = object.__new__(ScheduleReviewService)
        self.service.repository = repository

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    @staticmethod
    def _request() -> ReviewGenerateRequest:
        return ReviewGenerateRequest(
            user_id="user-llm",
            review_date=date(2026, 8, 24),
            timezone="Asia/Shanghai",
            reflection="上午推进顺利，下午容易被消息打断。",
            mood="平静",
            energy_level=4,
            satisfaction_score=4,
            events=[
                ReviewEventInput(title="完成复盘功能", completion_status="completed"),
                ReviewEventInput(title="整理部署文档", completion_status="not_completed"),
            ],
            agent_profile=AgentProfileInput(
                assistant_name="小光",
                personality="关注长期规律的成长伙伴",
                tone="温暖、具体、不说教",
                goals=["持续交付产品功能"],
            ),
        )

    def test_llm_result_and_memory_candidates_are_saved(self) -> None:
        llm_payload = """
        {
          "headline": "专注有成果，也要保护注意力",
          "summary": "主要开发任务已经完成。下午的消息打断值得继续观察。",
          "achievements": ["完成复盘功能"],
          "unfinished_items": ["整理部署文档尚未完成"],
          "patterns": ["下午更容易受到消息干扰"],
          "suggestions": ["明天下午关闭通知25分钟"],
          "tomorrow_focus": ["补齐部署文档"],
          "encouragement": "小光会陪你继续积累稳定节奏。",
          "completion_score": 78,
          "memory_candidates": [
            {
              "kind": "challenge",
              "content": "用户下午容易被即时消息打断专注",
              "importance": 4,
              "keywords": ["下午", "消息", "专注"]
            }
          ]
        }
        """

        with patch(
            "app.services.schedule_review_service.llm_service.chat",
            new=AsyncMock(return_value=llm_payload),
        ):
            response = asyncio.run(self.service.generate(self._request()))

        self.assertEqual(response.generated_by, "llm")
        self.assertEqual(response.result.completion_score, 78)
        self.assertEqual(len(response.saved_memories), 1)
        self.assertIn("即时消息", response.saved_memories[0].content)
        self.assertIsNotNone(self.service.repository.get_review(response.review_id))

    def test_llm_failure_uses_rule_fallback_and_still_persists(self) -> None:
        with patch(
            "app.services.schedule_review_service.llm_service.chat",
            new=AsyncMock(side_effect=RuntimeError("model unavailable")),
        ):
            response = asyncio.run(self.service.generate(self._request()))

        self.assertEqual(response.generated_by, "rule_fallback")
        self.assertEqual(response.result.completion_score, 50)
        self.assertTrue(any("完成复盘功能" in item for item in response.result.achievements))
        self.assertTrue(any(memory.kind == "goal" for memory in response.saved_memories))
        self.assertIsNotNone(self.service.repository.get_review(response.review_id))


if __name__ == "__main__":
    unittest.main()
