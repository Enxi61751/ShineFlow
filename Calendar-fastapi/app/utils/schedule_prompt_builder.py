import json
import re
from typing import Any, Iterable, List, Optional

from app.schemas.chat import AttachmentInfo, ScheduleCompletionData, ScheduleCompletionItem


JSON_BLOCK_RE = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL | re.IGNORECASE)


def build_schedule_completion_prompt(
    text: str,
    attachments: Iterable[AttachmentInfo],
    timezone: str,
    duration_minutes: int,
    now_iso: Optional[str] = None,
) -> str:
    attachment_lines = _build_attachment_lines(attachments)
    prompt_parts = [
        "Extract calendar events from the user's latest input.",
        "Return one-line valid JSON only. No markdown. No code fences. No explanations.",
        "Keep the JSON compact because the output budget is small.",
        "Use the current time reference when resolving relative dates like tomorrow.",
        "If only a start time is given, set end_time using the default duration.",
        "If a field is unknown, use null or an empty string and add it to missing_fields.",
        "Do not invent OCR, ASR, or visual details.",
        "Use this compact schema exactly:",
        json.dumps(
            {
                "items": [
                    {
                        "title": "string",
                        "location": "string",
                        "start_time": "YYYY-MM-DD HH:MM:SS or null",
                        "end_time": "YYYY-MM-DD HH:MM:SS or null",
                        "participants": [],
                        "description": "string",
                        "missing_fields": [],
                    }
                ]
            },
            ensure_ascii=True,
            separators=(",", ":"),
        ),
        f"Timezone: {timezone}",
        f"Default duration minutes if only one start time is provided: {duration_minutes}",
    ]

    if now_iso:
        prompt_parts.append(f"Current time reference: {now_iso}")

    if attachment_lines:
        prompt_parts.append("Attachment metadata:")
        prompt_parts.extend(attachment_lines)

    prompt_parts.append("User input:")
    prompt_parts.append(text.strip() or "(empty)")
    prompt_parts.append("Output now.")

    return "\n".join(prompt_parts)


def parse_schedule_completion_reply(raw_reply: str) -> ScheduleCompletionData:
    payload = _extract_json_object(raw_reply)
    if payload is None:
        fallback_item = _extract_truncated_first_item(raw_reply)
        if fallback_item is not None:
            return ScheduleCompletionData(items=[fallback_item], follow_up_question="", notes="")
        return ScheduleCompletionData(items=[], follow_up_question="", notes="")

    if isinstance(payload, dict) and isinstance(payload.get("data"), dict):
        payload = payload["data"]

    items_payload = []
    follow_up_question = ""
    notes = ""

    if isinstance(payload, dict):
        items_payload = payload.get("items") or payload.get("schedule_items") or payload.get("日程列表") or []
        follow_up_question = _as_string(
            payload.get("follow_up_question") or payload.get("question") or payload.get("追问")
        )
        notes = _as_string(payload.get("notes") or payload.get("remark") or payload.get("备注"))

    items = [_normalize_item(item) for item in items_payload if isinstance(item, dict)]
    if not items:
        fallback_item = _extract_truncated_first_item(raw_reply)
        if fallback_item is not None:
            items = [fallback_item]
    return ScheduleCompletionData(items=items, follow_up_question=follow_up_question, notes=notes)


def _build_attachment_lines(attachments: Iterable[AttachmentInfo]) -> List[str]:
    lines: List[str] = []
    for attachment in attachments:
        line = (
            f"- category={attachment.category}, filename={attachment.filename}, "
            f"mime={attachment.content_type}, size_bytes={attachment.size_bytes}"
        )
        if attachment.text_excerpt:
            line += f", text_excerpt={attachment.text_excerpt}"
        lines.append(line)
    return lines


def _extract_json_object(raw_reply: str) -> Optional[Any]:
    if not raw_reply:
        return None

    candidates: List[str] = []
    match = JSON_BLOCK_RE.search(raw_reply)
    if match:
        candidates.append(match.group(1))

    stripped = raw_reply.strip()
    if stripped.startswith("{") and stripped.endswith("}"):
        candidates.append(stripped)

    start = stripped.find("{")
    end = stripped.rfind("}")
    if start != -1 and end != -1 and end > start:
        candidates.append(stripped[start : end + 1])

    for candidate in candidates:
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            continue

    return None


def _extract_truncated_first_item(raw_reply: str) -> Optional[ScheduleCompletionItem]:
    if not raw_reply:
        return None

    title = _extract_string_field(raw_reply, "title")
    location = _extract_string_field(raw_reply, "location")
    start_time = _extract_string_field(raw_reply, "start_time")
    end_time = _extract_string_field(raw_reply, "end_time")
    description = _extract_string_field(raw_reply, "description")
    participants = _extract_string_array_field(raw_reply, "participants")

    if not any([title, location, start_time, end_time, description, participants]):
        return None

    missing_fields: List[str] = []
    if not title:
        missing_fields.append("title")
    if not start_time:
        missing_fields.append("time")
    if not location:
        missing_fields.append("location")

    return ScheduleCompletionItem(
        title=title,
        location=location,
        start_time=start_time or None,
        end_time=end_time or None,
        participants=participants,
        description=description,
        missing_fields=missing_fields,
        confidence=None,
    )


def _extract_string_field(raw_reply: str, key: str) -> str:
    match = re.search(rf'"{re.escape(key)}"\s*:\s*"([^"]*)"', raw_reply)
    if not match:
        return ""
    return match.group(1).strip()


def _extract_string_array_field(raw_reply: str, key: str) -> List[str]:
    match = re.search(rf'"{re.escape(key)}"\s*:\s*\[([^\]]*)', raw_reply)
    if not match:
        return []

    values = re.findall(r'"([^"]*)"', match.group(1))
    return [value.strip() for value in values if value.strip()]


def _normalize_item(item: dict[str, Any]) -> ScheduleCompletionItem:
    title = _as_string(item.get("title") or item.get("event") or item.get("事件"))
    location = _as_string(item.get("location") or item.get("place") or item.get("地点"))
    start_time = _as_optional_string(
        item.get("start_time") or item.get("time") or item.get("时间") or item.get("start")
    )
    end_time = _as_optional_string(item.get("end_time") or item.get("end"))
    description = _as_string(item.get("description") or item.get("summary") or item.get("备注"))
    participants = _normalize_participants(item.get("participants") or item.get("people") or item.get("人物"))

    missing_fields = item.get("missing_fields")
    if not isinstance(missing_fields, list):
        missing_fields = []
    missing_fields = [_as_string(field) for field in missing_fields if _as_string(field)]

    if not title and "title" not in missing_fields:
        missing_fields.append("title")
    if not start_time and "time" not in missing_fields:
        missing_fields.append("time")
    if not location and "location" not in missing_fields:
        missing_fields.append("location")

    confidence = item.get("confidence")
    if isinstance(confidence, (int, float)):
        confidence = float(confidence)
    else:
        confidence = None

    return ScheduleCompletionItem(
        title=title,
        location=location,
        start_time=start_time,
        end_time=end_time,
        participants=participants,
        description=description,
        missing_fields=missing_fields,
        confidence=confidence,
    )


def _normalize_participants(value: Any) -> List[str]:
    if isinstance(value, list):
        return [_as_string(item) for item in value if _as_string(item)]
    if isinstance(value, str):
        return [part.strip() for part in re.split(r"[,;/，；、]", value) if part.strip()]
    return []


def _as_string(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _as_optional_string(value: Any) -> Optional[str]:
    cleaned = _as_string(value)
    if not cleaned or cleaned.lower() == "null":
        return None
    return cleaned
