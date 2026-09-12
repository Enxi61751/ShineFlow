from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from app.core.config import settings
from app.schemas.review import (
    AgentProfileInput,
    AgentProfileResponse,
    MemoryItem,
    ReviewGenerateRequest,
    ReviewGenerateResponse,
    ReviewResult,
)
from app.services.llm_service import llm_service
from app.services.review_memory_repository import ReviewMemoryRepository


_REVIEW_SYSTEM_PROMPT = """
你是 ShineFlow 的日程复盘智能体。你的任务不是批评用户，而是结合当天日程、用户主观感受、智能体个性和长期记忆，给出诚实、温暖、可执行的复盘。

必须遵守：
1. 只返回一个 JSON 对象，不要 Markdown，不要解释 JSON。
2. 不要编造用户完成了某件事；completion_status=unknown 时，只能表述为“需要用户确认”。
3. 区分事实、推测与建议。避免医学诊断和夸大承诺。
4. 复盘要体现智能体的名称、性格和语气，但不要角色扮演过度。
5. 从长期记忆中寻找重复模式：有效策略、拖延诱因、精力规律、偏好和长期目标。
6. 建议最多 5 条，每条具体到下一次可以执行的动作。
7. 只把跨天仍有价值、未来能用于个性化的内容写入 memory_candidates；不要记住密码、联系方式等敏感信息，也不要把一次性的日程标题当作长期偏好。

输出结构：
{
  "headline": "一句简短标题",
  "summary": "2-4句中文总结",
  "achievements": ["已完成或值得肯定的事实"],
  "unfinished_items": ["未完成、部分完成或待确认事项"],
  "patterns": ["从今日和历史记忆观察到的模式；不确定时明确说可能"],
  "suggestions": ["下次可直接执行的建议"],
  "tomorrow_focus": ["明天最重要的1-3个关注点"],
  "encouragement": "符合智能体个性的结束语",
  "completion_score": 0,
  "memory_candidates": [
    {
      "kind": "preference|habit|challenge|effective_strategy|goal|energy_pattern",
      "content": "一条独立、长期有价值的中文记忆",
      "importance": 1,
      "keywords": ["关键词"]
    }
  ]
}
completion_score 必须为 0-100 的整数，memory_candidates 最多 6 条。
""".strip()


class ScheduleReviewService:
    def __init__(self) -> None:
        self.repository = ReviewMemoryRepository(settings.REVIEW_MEMORY_DB_PATH)

    async def generate(self, request: ReviewGenerateRequest) -> ReviewGenerateResponse:
        self.repository.upsert_profile(request.user_id, request.agent_profile)
        query = self._build_memory_query(request)
        used_memories = self.repository.search_memories(
            request.user_id,
            query,
            limit=settings.REVIEW_MEMORY_RETRIEVAL_LIMIT,
        )

        generated_by = "llm"
        model_name = llm_service.model_name
        memory_candidates: list[dict[str, Any]] = []
        try:
            raw = await llm_service.chat(
                self._build_prompt(request, used_memories),
                clean=False,
                max_tokens=settings.REVIEW_LLM_MAX_TOKENS,
                temperature=0.25,
                top_p=0.9,
            )
            payload = _extract_json_object(raw)
            result = _normalize_result(payload)
            memory_candidates = _normalize_memory_candidates(payload.get("memory_candidates"))
        except Exception:
            generated_by = "rule_fallback"
            result, memory_candidates = _build_rule_fallback(request, used_memories)

        review_id = str(uuid4())
        created_at = datetime.now(timezone.utc)
        self.repository.save_review(
            review_id=review_id,
            user_id=request.user_id,
            review_date=request.review_date.isoformat(),
            timezone_name=request.timezone,
            request_payload=request.model_dump(mode="json"),
            result=result,
            model=model_name,
            generated_by=generated_by,
            created_at=created_at,
        )
        saved_memories = self.repository.add_memories(
            user_id=request.user_id,
            source_review_id=review_id,
            memories=memory_candidates,
        )

        return ReviewGenerateResponse(
            review_id=review_id,
            generated_by=generated_by,
            model=model_name,
            result=result,
            used_memories=used_memories,
            saved_memories=saved_memories,
            created_at=created_at,
        )

    def get_profile(self, user_id: str) -> AgentProfileResponse:
        stored = self.repository.get_profile(user_id)
        if stored is None:
            profile = AgentProfileInput()
            updated_at = self.repository.upsert_profile(user_id, profile)
        else:
            profile, updated_at = stored
        return AgentProfileResponse(user_id=user_id, profile=profile, updated_at=updated_at)

    def save_profile(self, user_id: str, profile: AgentProfileInput) -> AgentProfileResponse:
        updated_at = self.repository.upsert_profile(user_id, profile)
        return AgentProfileResponse(user_id=user_id, profile=profile, updated_at=updated_at)

    @staticmethod
    def _build_memory_query(request: ReviewGenerateRequest) -> str:
        parts = [
            request.reflection,
            request.mood,
            request.agent_profile.personality,
            " ".join(request.agent_profile.goals),
        ]
        parts.extend(event.title for event in request.events)
        parts.extend(event.completion_note for event in request.events if event.completion_note)
        return "\n".join(part for part in parts if part)

    @staticmethod
    def _build_prompt(request: ReviewGenerateRequest, memories: list[MemoryItem]) -> str:
        profile = request.agent_profile
        payload = {
            "review_date": request.review_date.isoformat(),
            "timezone": request.timezone,
            "agent_profile": profile.model_dump(),
            "user_state": {
                "reflection": request.reflection,
                "mood": request.mood,
                "energy_level": request.energy_level,
                "satisfaction_score": request.satisfaction_score,
            },
            "events": [event.model_dump() for event in request.events],
            "relevant_long_term_memories": [
                {
                    "kind": memory.kind,
                    "content": memory.content,
                    "importance": memory.importance,
                    "updated_at": memory.updated_at.isoformat(),
                }
                for memory in memories
            ],
        }
        return (
            f"System instructions:\n{_REVIEW_SYSTEM_PROMPT}\n\n"
            f"Review input JSON:\n{json.dumps(payload, ensure_ascii=False)}\n\n"
            "请严格输出指定 JSON："
        )


schedule_review_service = ScheduleReviewService()


def _extract_json_object(raw: str) -> dict[str, Any]:
    text = (raw or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE)
        text = re.sub(r"\s*```$", "", text)
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if not match:
            raise ValueError("Review model did not return JSON.")
        payload = json.loads(match.group(0))
    if not isinstance(payload, dict):
        raise ValueError("Review model output must be an object.")
    return payload


def _string_list(value: Any, *, limit: int = 8, max_length: int = 300) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for item in value:
        text = str(item or "").strip()
        if text and text not in result:
            result.append(text[:max_length])
        if len(result) >= limit:
            break
    return result


def _normalize_result(payload: dict[str, Any]) -> ReviewResult:
    try:
        completion_score = int(payload.get("completion_score") or 0)
    except (TypeError, ValueError):
        completion_score = 0
    return ReviewResult(
        headline=str(payload.get("headline") or "今日复盘").strip()[:80],
        summary=str(payload.get("summary") or "").strip()[:1600],
        achievements=_string_list(payload.get("achievements")),
        unfinished_items=_string_list(payload.get("unfinished_items")),
        patterns=_string_list(payload.get("patterns")),
        suggestions=_string_list(payload.get("suggestions"), limit=5),
        tomorrow_focus=_string_list(payload.get("tomorrow_focus"), limit=3),
        encouragement=str(payload.get("encouragement") or "").strip()[:500],
        completion_score=max(0, min(completion_score, 100)),
    )


def _normalize_memory_candidates(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    allowed_kinds = {
        "preference",
        "habit",
        "challenge",
        "effective_strategy",
        "goal",
        "energy_pattern",
    }
    result: list[dict[str, Any]] = []
    for item in value[:6]:
        if not isinstance(item, dict):
            continue
        content = str(item.get("content") or "").strip()
        if len(content) < 6:
            continue
        kind = str(item.get("kind") or "habit").strip()
        if kind not in allowed_kinds:
            kind = "habit"
        try:
            importance = int(item.get("importance") or 3)
        except (TypeError, ValueError):
            importance = 3
        keywords = item.get("keywords") if isinstance(item.get("keywords"), list) else []
        result.append(
            {
                "kind": kind,
                "content": content[:500],
                "importance": max(1, min(importance, 5)),
                "keywords": [str(keyword)[:40] for keyword in keywords[:12] if str(keyword).strip()],
            }
        )
    return result


def _build_rule_fallback(
    request: ReviewGenerateRequest,
    used_memories: list[MemoryItem],
) -> tuple[ReviewResult, list[dict[str, Any]]]:
    completed = [event.title for event in request.events if event.completion_status == "completed"]
    partial = [event.title for event in request.events if event.completion_status == "partial"]
    unfinished = [
        event.title
        for event in request.events
        if event.completion_status in {"not_completed", "cancelled"}
    ]
    unknown = [event.title for event in request.events if event.completion_status == "unknown"]

    known_weight = len(completed) + len(partial) + len(unfinished)
    if known_weight:
        score = round((len(completed) + len(partial) * 0.5) / known_weight * 100)
    elif request.satisfaction_score is not None:
        score = request.satisfaction_score * 20
    else:
        score = 60 if request.events else 50

    achievements = [f"完成了「{title}」" for title in completed[:6]]
    if not achievements:
        achievements = ["愿意停下来复盘，本身就是在建立更稳定的行动系统。"]

    unfinished_items = [f"「{title}」尚未完成" for title in unfinished[:6]]
    unfinished_items.extend(f"「{title}」只完成了一部分" for title in partial[:4])
    unfinished_items.extend(f"「{title}」的完成情况还需要确认" for title in unknown[:4])

    patterns = []
    if request.energy_level is not None:
        patterns.append(f"今天自评精力为 {request.energy_level}/5，可继续观察精力与任务难度的关系。")
    if used_memories:
        patterns.append(f"记忆库中已有 {len(used_memories)} 条相关经验，本次建议会优先保持与既往有效做法一致。")
    if not patterns:
        patterns.append("目前历史样本还不多，连续复盘几天后才能更可靠地识别规律。")

    suggestions = []
    if unfinished or partial:
        suggestions.append("把未完成事项缩小为一个 10-20 分钟的下一步，并为它指定明确开始时间。")
    suggestions.append("明天只设一个必须完成项，其余事项按精力分为“可选”和“延后”。")
    if request.energy_level is not None and request.energy_level <= 2:
        suggestions.append("低精力时减少连续高压任务，在两个任务之间预留饮水、走动或放空时间。")
    else:
        suggestions.append("在高专注任务后安排短休息，避免把有效状态一次性透支。")

    focus_source = unfinished + partial
    tomorrow_focus = focus_source[:2] or ["确定明天最重要的一件事", "为高难度任务预留不被打断的时间"]
    name = request.agent_profile.assistant_name
    reflection_summary = request.reflection.strip()
    if reflection_summary:
        summary = f"你对今天的补充是：{reflection_summary[:280]}。结合日程来看，今天既有推进，也有一些值得调整的空间。"
    else:
        summary = "结合今天的日程，目前能看到任务安排情况，但完成细节仍有限。补充真实完成情况和感受后，复盘会更准确。"

    memories: list[dict[str, Any]] = []
    if request.energy_level is not None:
        memories.append(
            {
                "kind": "energy_pattern",
                "content": f"用户在 {request.review_date.isoformat()} 的自评精力为 {request.energy_level}/5，需结合后续复盘判断稳定规律。",
                "importance": 2,
                "keywords": ["精力", "复盘"],
            }
        )
    if request.agent_profile.goals:
        for goal in request.agent_profile.goals[:2]:
            memories.append(
                {
                    "kind": "goal",
                    "content": f"用户当前关注的长期目标：{goal}",
                    "importance": 4,
                    "keywords": ["目标"],
                }
            )

    result = ReviewResult(
        headline="今天不是打分，而是校准",
        summary=summary,
        achievements=achievements,
        unfinished_items=unfinished_items,
        patterns=patterns,
        suggestions=suggestions[:5],
        tomorrow_focus=tomorrow_focus[:3],
        encouragement=f"我是{name}。不用用一天的结果定义自己，我们只需要让明天比今天更清楚一点。",
        completion_score=max(0, min(score, 100)),
    )
    return result, memories
