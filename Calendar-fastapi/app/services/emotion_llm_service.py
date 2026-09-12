from __future__ import annotations

import json
import re
from typing import Any, List, Optional, Sequence

import httpx

from app.core.config import settings
from app.schemas.chat import EmotionInsight
from app.utils.emotion_schedule_planner import EMOTION_LABELS, SEVERE_CUES


ALLOWED_EMOTIONS = {
    "anxious",
    "depressed",
    "tired",
    "low_motivation",
    "positive_calm",
    "excited_irritable",
    "unclear",
}


class EmotionLLMService:
    """LLM-backed emotion recognizer with strict structured-output validation.

    The LLM is the primary classifier. The Python side only does prompt construction,
    JSON parsing, schema/value validation, severe-safety fallback, and unavailable-model fallback.
    """

    async def analyze(
        self,
        *,
        message: str,
        history: Optional[Sequence[str]] = None,
        timezone: str = "Asia/Shanghai",
    ) -> dict[str, Any]:
        message = (message or "").strip()
        history_items = [str(item).strip() for item in list(history or [])[-6:] if str(item).strip()]
        combined = "\n".join(history_items + [message]).lower()

        severe_hits = [cue for cue in SEVERE_CUES if cue.lower() in combined]
        if severe_hits:
            return _severe_safety_result(severe_hits)

        if not settings.REMOTE_LLM_BASE_URL.strip():
            return _llm_unavailable_result(
                message="REMOTE_LLM_BASE_URL is not configured for LLM emotion recognition.",
            )

        try:
            raw = await self._call_llm(message=message, history=history_items, timezone=timezone)
            parsed = _extract_json_object(raw)
            return _normalize_emotion_payload(parsed, raw_reply=raw)
        except Exception as exc:
            return _llm_unavailable_result(
                message=f"LLM emotion recognition is temporarily unavailable: {exc}",
            )

    async def _call_llm(self, *, message: str, history: List[str], timezone: str) -> str:
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
                            "recent_history": history,
                            "latest_message": message,
                        },
                        ensure_ascii=False,
                    ),
                },
            ],
            "temperature": 0.1,
            "top_p": 0.9,
            "max_tokens": 700,
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
            raise RuntimeError(f"Unexpected emotion LLM response format: {data}") from exc

        if not content.strip():
            raise RuntimeError("Emotion LLM returned empty content.")
        return content.strip()


_SYSTEM_PROMPT = """You are ShineFlow's dedicated emotion recognition module.

Your job is to infer the user's current emotional and body-energy state from the latest message and recent conversation context.

Boundaries:
- You are not a therapist and must not make medical diagnoses.
- Judge only the current conversational state.
- Do not comfort or chat. Do not output Markdown. Return JSON only.
- If emotional evidence is insufficient, return emotion_type=unclear and provide one gentle Chinese follow-up question.
- Pay attention to hidden negative signals such as insomnia, mental exhaustion, avoidance, feeling unable to think, urgency, rumination, low motivation, and overwhelm.
- If there is self-harm/suicide/"cannot go on" type content, set risk_level=high and provide a concise Chinese safety support_note.

Choose exactly one primary emotion_type from:
- anxious: anxiety / high pressure
- depressed: depressed or low mood
- tired: fatigue / low physical or cognitive energy
- low_motivation: low motivation / burnout / avoidance
- positive_calm: positive and calm
- excited_irritable: over-excited, restless, impulsive, or irritable
- unclear: not enough emotional evidence

Level scale:
1 = very mild or stable
2 = light; normal planning is mostly okay
3 = moderate; schedule should be adjusted
4 = clear; reduce pressure, split tasks, or add rest
5 = intense or risky; significantly reduce load and consider safety/support guidance

Confidence scale:
0.80-1.00 = strong evidence
0.60-0.79 = reasonably confident
0.40-0.59 = limited evidence; follow-up is useful
0.00-0.39 = uncertain

Return exactly one JSON object with these fields:
{
  "emotion_type": "anxious|depressed|tired|low_motivation|positive_calm|excited_irritable|unclear",
  "emotion_label": "Chinese label",
  "level": 1,
  "confidence": 0.0,
  "cues": ["short evidence phrases from the user's text; do not invent"],
  "secondary_emotions": [{"emotion_type": "tired", "level": 3, "evidence": "brief evidence"}],
  "needs_follow_up": false,
  "follow_up_question": "Chinese question or empty string",
  "support_note": "Chinese safety/support note or empty string",
  "risk_level": "low|medium|high",
  "reason_summary": "one concise Chinese sentence explaining the judgment",
  "planning_hint": "one concise Chinese sentence for emotion-adaptive scheduling"
}
"""


emotion_llm_service = EmotionLLMService()


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
        raise ValueError("Emotion LLM output must be a JSON object.")
    return data


def _normalize_emotion_payload(payload: dict[str, Any], *, raw_reply: str = "") -> dict[str, Any]:
    emotion_type = str(payload.get("emotion_type") or "unclear").strip().lower()
    if emotion_type not in ALLOWED_EMOTIONS:
        emotion_type = "unclear"

    label = str(payload.get("emotion_label") or EMOTION_LABELS.get(emotion_type, "")).strip()
    level = _clamp_int(payload.get("level"), minimum=1, maximum=5, default=2)
    confidence = _clamp_float(payload.get("confidence"), minimum=0.0, maximum=1.0, default=0.45)

    cues = payload.get("cues") or []
    if not isinstance(cues, list):
        cues = []
    clean_cues = [str(item).strip()[:40] for item in cues if str(item).strip()][:8]

    secondary = payload.get("secondary_emotions") or []
    if not isinstance(secondary, list):
        secondary = []
    clean_secondary = []
    for item in secondary[:4]:
        if not isinstance(item, dict):
            continue
        sub_type = str(item.get("emotion_type") or "").strip().lower()
        if sub_type not in ALLOWED_EMOTIONS or sub_type == emotion_type:
            continue
        clean_secondary.append(
            {
                "emotion_type": sub_type,
                "emotion_label": EMOTION_LABELS.get(sub_type, sub_type),
                "level": _clamp_int(item.get("level"), minimum=1, maximum=5, default=2),
                "evidence": str(item.get("evidence") or "").strip()[:80],
            }
        )

    risk_level = str(payload.get("risk_level") or "low").strip().lower()
    if risk_level not in {"low", "medium", "high"}:
        risk_level = "low"

    needs_follow_up = bool(payload.get("needs_follow_up")) or emotion_type == "unclear" or confidence < 0.55
    follow_up = str(payload.get("follow_up_question") or "").strip()
    if needs_follow_up and not follow_up:
        follow_up = "我还不太确定你现在更偏焦虑、疲惫、低落还是低动力。你愿意用 1-10 分给今天的压力和精力各打个分吗？"

    support_note = str(payload.get("support_note") or "").strip()
    if risk_level == "high" and not support_note:
        support_note = "如果你此刻有伤害自己的冲动，请优先联系身边可信的人或当地紧急援助；我也可以先陪你把接下来 10 分钟安排得更安全。"

    base = EmotionInsight(
        emotion_type=emotion_type,
        emotion_label=label or EMOTION_LABELS.get(emotion_type, emotion_type),
        level=level,
        confidence=round(confidence, 2),
        cues=clean_cues,
        needs_follow_up=needs_follow_up,
        follow_up_question=follow_up,
        support_note=support_note,
    ).model_dump()

    base.update(
        {
            "secondary_emotions": clean_secondary,
            "risk_level": risk_level,
            "reason_summary": str(payload.get("reason_summary") or "").strip()[:200],
            "planning_hint": str(payload.get("planning_hint") or "").strip()[:200],
            "recognizer": "llm_structured",
        }
    )
    return base


def _severe_safety_result(cues: List[str]) -> dict[str, Any]:
    base = EmotionInsight(
        emotion_type="depressed",
        emotion_label=EMOTION_LABELS["depressed"],
        level=5,
        confidence=0.98,
        cues=cues[:6],
        needs_follow_up=False,
        follow_up_question="",
        support_note="如果你此刻有伤害自己的冲动，请优先联系身边可信的人或当地紧急援助；我也可以先陪你把接下来 10 分钟安排得更安全。",
    ).model_dump()
    base.update(
        {
            "secondary_emotions": [],
            "risk_level": "high",
            "reason_summary": "检测到明显自伤或严重风险表达，优先进入安全兜底。",
            "planning_hint": "暂停高压任务，只安排安全陪伴、联系支持资源和短时稳定步骤。",
            "recognizer": "safety_fallback",
        }
    )
    return base


def _llm_unavailable_result(message: str) -> dict[str, Any]:
    base = EmotionInsight(
        emotion_type="unclear",
        emotion_label=EMOTION_LABELS["unclear"],
        level=2,
        confidence=0.0,
        cues=[],
        needs_follow_up=True,
        follow_up_question="我现在还需要一点补充信息来判断状态：你此刻更偏焦虑、疲惫、低落，还是低动力？压力和精力各大概是 1-10 分里的几分？",
        support_note="",
    ).model_dump()
    base.update(
        {
            "secondary_emotions": [],
            "risk_level": "low",
            "reason_summary": message,
            "planning_hint": "先采用温和保守的日程安排，等状态信息更明确后再细化。",
            "recognizer": "llm_unavailable",
        }
    )
    return base


def _clamp_int(value: Any, *, minimum: int, maximum: int, default: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        number = default
    return min(max(number, minimum), maximum)


def _clamp_float(value: Any, *, minimum: float, maximum: float, default: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        number = default
    return min(max(number, minimum), maximum)
