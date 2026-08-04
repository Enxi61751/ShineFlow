from __future__ import annotations

import json
import re
from datetime import datetime, timedelta, timezone as dt_timezone
from typing import Any, List, Optional, Sequence
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx

from app.core.config import settings
from app.schemas.chat import EmotionInsight, PlanSlot, PlanningTask
from app.utils.emotion_schedule_planner import create_emotion_schedule_plan


ALLOWED_SLOT_TYPES = {"task", "break", "rest", "planning", "buffer"}
NEGATIVE_OR_STRAINED = {"anxious", "depressed", "tired", "low_motivation", "excited_irritable"}


class ScheduleLLMService:
    """LLM-backed adaptive schedule planner with deterministic validation and rule fallback."""

    async def create_plan(
        self,
        *,
        message: str,
        tasks: Sequence[PlanningTask],
        emotion: dict[str, Any],
        timezone: str = "Asia/Shanghai",
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
    ) -> dict[str, Any]:
        emotion_insight = EmotionInsight.model_validate(emotion)

        if not settings.REMOTE_LLM_BASE_URL.strip():
            return _rule_fallback(
                message=message,
                tasks=tasks,
                emotion_payload=emotion,
                emotion_insight=emotion_insight,
                timezone=timezone,
                start_time=start_time,
                end_time=end_time,
                reason="REMOTE_LLM_BASE_URL is not configured for LLM schedule planning.",
            )

        try:
            raw = await self._call_llm(
                message=message,
                tasks=tasks,
                emotion=emotion,
                timezone=timezone,
                start_time=start_time,
                end_time=end_time,
            )
            parsed = _extract_json_object(raw)
            normalized = _normalize_schedule_payload(
                payload=parsed,
                emotion_payload=emotion,
                timezone=timezone,
                start_time=start_time,
                end_time=end_time,
            )
            return normalized
        except Exception as exc:
            return _rule_fallback(
                message=message,
                tasks=tasks,
                emotion_payload=emotion,
                emotion_insight=emotion_insight,
                timezone=timezone,
                start_time=start_time,
                end_time=end_time,
                reason=f"LLM schedule planning is temporarily unavailable or invalid: {exc}",
            )

    async def _call_llm(
        self,
        *,
        message: str,
        tasks: Sequence[PlanningTask],
        emotion: dict[str, Any],
        timezone: str,
        start_time: Optional[str],
        end_time: Optional[str],
    ) -> str:
        base_url = settings.REMOTE_LLM_BASE_URL.rstrip("/")
        chat_path = settings.REMOTE_LLM_CHAT_PATH.strip() or "/v1/chat/completions"
        if not chat_path.startswith("/"):
            chat_path = "/" + chat_path

        payload: dict[str, Any] = {
            "messages": [
                {"role": "system", "content": _SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": json.dumps(
                        {
                            "timezone": timezone,
                            "schedule_start_time": start_time,
                            "schedule_end_time": end_time,
                            "latest_message": message,
                            "emotion": emotion,
                            "tasks": [task.model_dump() for task in tasks],
                        },
                        ensure_ascii=False,
                    ),
                },
            ],
            "temperature": 0.2,
            "top_p": 0.9,
            "max_tokens": 1800,
        }
        if settings.REMOTE_LLM_MODEL.strip():
            payload["model"] = settings.REMOTE_LLM_MODEL.strip()

        headers = {"Content-Type": "application/json"}
        if settings.REMOTE_LLM_API_KEY.strip():
            headers["Authorization"] = f"Bearer {settings.REMOTE_LLM_API_KEY.strip()}"

        async with httpx.AsyncClient(timeout=settings.REMOTE_LLM_TIMEOUT_SECONDS) as client:
            response = await client.post(f"{base_url}{chat_path}", headers=headers, json=payload)
            response.raise_for_status()
            data = response.json()

        try:
            content = data["choices"][0]["message"].get("content") or ""
        except (KeyError, IndexError, TypeError) as exc:
            raise RuntimeError(f"Unexpected schedule LLM response format: {data}") from exc

        if not content.strip():
            raise RuntimeError("Schedule LLM returned empty content.")
        return content.strip()


_SYSTEM_PROMPT = """You are ShineFlow's emotion-adaptive schedule planning module.

Task:
Generate a concrete, warm, emotion-adaptive schedule plan from the user's message, tasks, time range, and structured emotion insight.

Hard rules:
- Return JSON only. No Markdown.
- Every plan item must include start_time, end_time, title, duration_minutes, slot_type, and suggestion.
- Use HH:MM for start_time/end_time.
- Do not overlap slots.
- Do not place slots outside the requested time range.
- duration_minutes must match end_time - start_time.
- slot_type must be one of: task, break, rest, planning, buffer.
- Do not claim calendar events are created.

Emotion adaptation rules:
- anxious/depressed/tired: split hard tasks, shorten focus blocks, add water/stretch/walk breaks, reduce total load, start with easier tasks, avoid continuous high-pressure blocks.
- low_motivation: lower task standards, include small starter steps, add leisure/blank space, use light healing activities.
- positive_calm: balance work, study, exercise, entertainment, and rest.
- excited_irritable: add 10-15 minute calming gaps, slow transitions, avoid an over-tight schedule.
- unclear: use a gentle balanced plan and ask for more state info if needed.

Human energy rhythm:
- Avoid heavy pressure around lunch.
- Prefer deep work in higher-energy blocks when the emotion allows it.
- Keep a closing/rest slot when possible.

Return exactly one JSON object:
{
  "planning_strategy": "one concise Chinese sentence",
  "load_adjustment": {"should_reduce_load": true, "reason": "Chinese reason"},
  "plan": [
    {
      "start_time": "09:00",
      "end_time": "09:10",
      "title": "Chinese title",
      "duration_minutes": 10,
      "slot_type": "buffer",
      "suggestion": "Chinese emotion-regulation suggestion"
    }
  ],
  "reply": "Warm Chinese reply that first empathizes, then lists the schedule with durations and tips, then asks whether to adjust."
}
"""


schedule_llm_service = ScheduleLLMService()


def _extract_json_object(raw: str) -> dict[str, Any]:
    text = (raw or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE)
        text = re.sub(r"\s*```$", "", text)
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        if not match:
            raise
        data = json.loads(match.group(0))
    if not isinstance(data, dict):
        raise ValueError("Schedule LLM output must be a JSON object.")
    return data


def _normalize_schedule_payload(
    *,
    payload: dict[str, Any],
    emotion_payload: dict[str, Any],
    timezone: str,
    start_time: Optional[str],
    end_time: Optional[str],
) -> dict[str, Any]:
    day_start = _parse_or_default_time(start_time, timezone, default_hour=8, default_minute=30)
    day_end = _parse_or_default_time(end_time, timezone, default_hour=21, default_minute=30)
    if day_end <= day_start:
        day_end = day_start + timedelta(hours=8)

    raw_plan = payload.get("plan") or []
    if not isinstance(raw_plan, list) or not raw_plan:
        raise ValueError("Schedule LLM returned an empty plan.")

    slots: List[PlanSlot] = []
    previous_end: Optional[datetime] = None
    issues: List[str] = []

    for index, raw_slot in enumerate(raw_plan, start=1):
        if not isinstance(raw_slot, dict):
            issues.append(f"slot {index} is not an object")
            continue

        start_dt = _parse_slot_time(raw_slot.get("start_time"), day_start, timezone)
        end_dt = _parse_slot_time(raw_slot.get("end_time"), day_start, timezone)
        if start_dt is None or end_dt is None:
            issues.append(f"slot {index} has invalid time")
            continue
        if end_dt <= start_dt:
            issues.append(f"slot {index} ends before it starts")
            continue
        if start_dt < day_start or end_dt > day_end:
            issues.append(f"slot {index} is outside requested range")
            continue
        if previous_end is not None and start_dt < previous_end:
            issues.append(f"slot {index} overlaps previous slot")
            continue

        duration = int(round((end_dt - start_dt).total_seconds() / 60))
        raw_duration = raw_slot.get("duration_minutes")
        if raw_duration is not None:
            try:
                provided_duration = int(raw_duration)
            except (TypeError, ValueError):
                issues.append(f"slot {index} has invalid duration")
                continue
            if abs(provided_duration - duration) > 1:
                issues.append(f"slot {index} duration does not match its start/end time")
                continue

        title = str(raw_slot.get("title") or "").strip()[:80]
        if not title:
            issues.append(f"slot {index} has empty title")
            continue
        slot_type = str(raw_slot.get("slot_type") or "task").strip().lower()
        if slot_type not in ALLOWED_SLOT_TYPES:
            slot_type = "task"
        suggestion = str(raw_slot.get("suggestion") or "").strip()[:200]
        if not suggestion:
            suggestion = _default_suggestion(emotion_payload.get("emotion_type"), slot_type)

        slots.append(
            PlanSlot(
                start_time=start_dt.strftime("%H:%M"),
                end_time=end_dt.strftime("%H:%M"),
                title=title,
                duration_minutes=duration,
                slot_type=slot_type,
                suggestion=suggestion,
            )
        )
        previous_end = end_dt

    if issues:
        raise ValueError("; ".join(issues[:5]))
    if not slots:
        raise ValueError("No valid schedule slots after validation.")

    emotion_type = str(emotion_payload.get("emotion_type") or "unclear")
    total_task_minutes = sum(slot.duration_minutes for slot in slots if slot.slot_type == "task")
    has_break = any(slot.slot_type in {"break", "rest", "buffer"} for slot in slots)
    if emotion_type in NEGATIVE_OR_STRAINED and total_task_minutes >= 60 and not has_break:
        raise ValueError("Negative/strained emotion plan must include breaks or buffers.")

    strategy = str(payload.get("planning_strategy") or "").strip()[:200]
    if not strategy:
        strategy = _default_strategy(emotion_type)

    load_adjustment = payload.get("load_adjustment") if isinstance(payload.get("load_adjustment"), dict) else {}
    load_adjustment = {
        "should_reduce_load": bool(load_adjustment.get("should_reduce_load")),
        "reason": str(load_adjustment.get("reason") or "").strip()[:160],
    }

    reply = _build_validated_reply(emotion_payload, slots, strategy)

    return {
        "success": True,
        "emotion": emotion_payload,
        "plan": [slot.model_dump() for slot in slots],
        "reply": reply,
        "planning_strategy": strategy,
        "load_adjustment": load_adjustment,
        "recognizer": "schedule_llm_structured",
        "fallback_used": False,
    }


def _rule_fallback(
    *,
    message: str,
    tasks: Sequence[PlanningTask],
    emotion_payload: dict[str, Any],
    emotion_insight: EmotionInsight,
    timezone: str,
    start_time: Optional[str],
    end_time: Optional[str],
    reason: str,
) -> dict[str, Any]:
    response = create_emotion_schedule_plan(
        message=message,
        tasks=tasks,
        timezone=timezone,
        start_time=start_time,
        end_time=end_time,
        emotion=emotion_insight,
    ).model_dump()
    response["emotion"] = emotion_payload
    response.update(
        {
            "planning_strategy": "\u5927\u6a21\u578b\u65e5\u7a0b\u89c4\u5212\u6682\u4e0d\u53ef\u7528\uff0c\u5df2\u4f7f\u7528\u7a33\u5b9a\u89c4\u5219\u89c4\u5212\u5668\u751f\u6210\u4fdd\u5b88\u65b9\u6848\u3002",
            "load_adjustment": {"should_reduce_load": emotion_insight.emotion_type in NEGATIVE_OR_STRAINED, "reason": reason},
            "recognizer": "schedule_rule_fallback",
            "fallback_used": True,
            "fallback_reason": reason,
        }
    )
    return response


def _build_validated_reply(emotion: dict[str, Any], slots: Sequence[PlanSlot], strategy: str) -> str:
    label = str(emotion.get("emotion_label") or emotion.get("emotion_type") or "").strip()
    level = emotion.get("level", 2)
    cues = emotion.get("cues") or []
    empathy = _empathy_sentence(str(emotion.get("emotion_type") or "unclear"))
    lines = [empathy]
    lines.append(f"\u6211\u5148\u6309\u4f60\u5f53\u524d\u7684\u72b6\u6001\u5224\u65ad\uff1a{label}\uff0c\u5f3a\u5ea6\u7ea6 {level}/5\u3002")
    if cues:
        lines.append("\u6211\u7559\u610f\u5230\u7684\u7ebf\u7d22\uff1a" + "\u3001".join(str(cue) for cue in cues[:5]) + "\u3002")
    if strategy:
        lines.append("\u89c4\u5212\u601d\u8def\uff1a" + strategy)
    lines.append("\n\u4eca\u5929\u7684\u9002\u914d\u65e5\u7a0b\uff1a")
    for slot in slots:
        lines.append(
            f"- {slot.start_time}-{slot.end_time}\uff08{slot.duration_minutes}\u5206\u949f\uff09{slot.title}\uff5c{slot.suggestion}"
        )
    lines.append("\n\u8fd9\u4efd\u5b89\u6392\u662f\u53ef\u8c03\u6574\u7684\u3002\u4f60\u60f3\u8981\u6211\u5e2e\u4f60\u51cf\u5c11\u4efb\u52a1\u3001\u6539\u65f6\u95f4\uff0c\u8fd8\u662f\u628a\u67d0\u4e2a\u4efb\u52a1\u518d\u62c6\u5f97\u66f4\u7ec6\uff1f")
    return "\n".join(lines)


def _empathy_sentence(emotion_type: str) -> str:
    if emotion_type == "anxious":
        return "\u542c\u8d77\u6765\u4f60\u73b0\u5728\u538b\u529b\u4e0d\u5c0f\uff0c\u6211\u4f1a\u5148\u628a\u8282\u594f\u653e\u7f13\uff0c\u8ba9\u4efb\u52a1\u66f4\u5bb9\u6613\u843d\u5730\u3002"
    if emotion_type == "depressed":
        return "\u4f60\u73b0\u5728\u50cf\u662f\u6709\u4e9b\u4f4e\u843d\u548c\u5403\u529b\uff0c\u4eca\u5929\u6211\u4eec\u5148\u4ee5\u51cf\u8d1f\u548c\u53ef\u5b8c\u6210\u4e3a\u4e3b\u3002"
    if emotion_type == "tired":
        return "\u611f\u89c9\u4f60\u7684\u7cbe\u529b\u9700\u8981\u88ab\u7167\u987e\u4e00\u4e0b\uff0c\u6211\u4f1a\u7528\u66f4\u77ed\u7684\u4e13\u6ce8\u6bb5\u6765\u5b89\u6392\u3002"
    if emotion_type == "low_motivation":
        return "\u6ca1\u52a8\u529b\u7684\u65f6\u5019\u4e0d\u9002\u5408\u786c\u903c\u81ea\u5df1\u6ee1\u8d1f\u8377\uff0c\u6211\u4eec\u5148\u628a\u6807\u51c6\u964d\u5230\u80fd\u542f\u52a8\u3002"
    if emotion_type == "excited_irritable":
        return "\u4f60\u73b0\u5728\u7684\u80fd\u91cf\u6bd4\u8f83\u6ee1\u4e5f\u6bd4\u8f83\u6025\uff0c\u6211\u4f1a\u523b\u610f\u7559\u51fa\u7f13\u51b2\uff0c\u8ba9\u8282\u594f\u7a33\u4e00\u70b9\u3002"
    if emotion_type == "positive_calm":
        return "\u4f60\u73b0\u5728\u72b6\u6001\u6bd4\u8f83\u5e73\u7a33\uff0c\u9002\u5408\u505a\u6548\u7387\u548c\u4f11\u606f\u517c\u987e\u7684\u5b89\u6392\u3002"
    return "\u6211\u4f1a\u5148\u7528\u6e29\u548c\u4fdd\u5b88\u7684\u65b9\u5f0f\u5e2e\u4f60\u5b89\u6392\uff0c\u540e\u9762\u53ef\u4ee5\u518d\u6839\u636e\u72b6\u6001\u8c03\u6574\u3002"


def _default_strategy(emotion_type: str) -> str:
    if emotion_type in {"anxious", "depressed", "tired"}:
        return "\u5148\u62c6\u5206\u9ad8\u538b\u4efb\u52a1\uff0c\u7f29\u77ed\u5355\u6b21\u5de5\u4f5c\u65f6\u957f\uff0c\u5e76\u63d2\u5165\u77ed\u4f11\u606f\u3002"
    if emotion_type == "low_motivation":
        return "\u5148\u964d\u4f4e\u5f00\u59cb\u96be\u5ea6\uff0c\u7528\u5c0f\u6b65\u9aa4\u5e26\u52a8\u4efb\u52a1\u542f\u52a8\u3002"
    if emotion_type == "excited_irritable":
        return "\u62c9\u957f\u4efb\u52a1\u95f4\u9694\uff0c\u52a0\u5165\u9759\u5fc3\u7f13\u51b2\uff0c\u907f\u514d\u8fc7\u5ea6\u7d27\u51d1\u3002"
    return "\u91c7\u7528\u5747\u8861\u7248\u65e5\u7a0b\uff0c\u517c\u987e\u4efb\u52a1\u63a8\u8fdb\u548c\u4f11\u606f\u6062\u590d\u3002"


def _default_suggestion(emotion_type: Any, slot_type: str) -> str:
    if slot_type in {"break", "rest", "buffer"}:
        return "\u77ed\u6682\u79bb\u5c4f\u3001\u559d\u6c34\u6216\u8fdc\u773a\uff0c\u8ba9\u8eab\u4f53\u548c\u6ce8\u610f\u529b\u7a0d\u5fae\u56de\u6765\u3002"
    if emotion_type in NEGATIVE_OR_STRAINED:
        return "\u53ea\u5173\u6ce8\u5f53\u524d\u5c0f\u6b65\u9aa4\uff0c\u5b8c\u6210\u4e00\u90e8\u5206\u4e5f\u7b97\u6709\u6548\u63a8\u8fdb\u3002"
    return "\u4fdd\u6301\u4e13\u6ce8\uff0c\u5230\u70b9\u5c31\u505c\u4e0b\u6765\u4f11\u606f\u3002"


def _parse_or_default_time(value: Optional[str], timezone: str, default_hour: int, default_minute: int) -> datetime:
    tz = _safe_zoneinfo(timezone)
    now = datetime.now(tz)
    if value:
        for fmt in ["%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%H:%M"]:
            try:
                parsed = datetime.strptime(value, fmt)
                if fmt == "%H:%M":
                    parsed = parsed.replace(year=now.year, month=now.month, day=now.day)
                return parsed.replace(tzinfo=tz)
            except ValueError:
                continue
    return now.replace(hour=default_hour, minute=default_minute, second=0, microsecond=0)


def _parse_slot_time(value: Any, day_start: datetime, timezone: str) -> Optional[datetime]:
    text = str(value or "").strip()
    if not text:
        return None
    tz = _safe_zoneinfo(timezone)
    for fmt in ["%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%H:%M"]:
        try:
            parsed = datetime.strptime(text, fmt)
            if fmt == "%H:%M":
                parsed = parsed.replace(year=day_start.year, month=day_start.month, day=day_start.day)
            return parsed.replace(tzinfo=tz)
        except ValueError:
            continue
    return None


def _safe_zoneinfo(timezone: str):
    try:
        return ZoneInfo(timezone)
    except ZoneInfoNotFoundError:
        if timezone in {"Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin"}:
            return dt_timezone(timedelta(hours=8), name="Asia/Shanghai")
        return dt_timezone.utc
