from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any, List, Optional
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.schemas.chat import PlanningTask
from app.services.emotion_llm_service import emotion_llm_service
from app.services.schedule_llm_service import schedule_llm_service
from app.tool_calling_agent.tool_registry import ToolRegistry, array_of, object_schema
from app.utils.emotion_schedule_planner import extract_tasks_from_text

NEGATIVE_OR_STRAINED_EMOTIONS = {"anxious", "depressed", "tired", "low_motivation", "excited_irritable"}


async def analyze_user_emotion(message: str, history: Optional[List[str]] = None) -> dict[str, Any]:
    """Detect the user's current emotional state using an LLM structured recognizer."""
    return await emotion_llm_service.analyze(message=message, history=history or [])


def extract_planning_tasks(message: str) -> dict[str, Any]:
    """Extract rough planning tasks from natural language."""
    tasks = extract_tasks_from_text(message)
    return {"tasks": [task.model_dump() for task in tasks]}


def complete_schedule_fields(
    message: str,
    items: Optional[List[dict[str, Any]]] = None,
    timezone: str = "Asia/Shanghai",
    default_duration_minutes: int = 60,
) -> dict[str, Any]:
    """Validate and normalize calendar-event fields extracted by the tool-calling model.

    Long-term agent design: the LLM should put extracted event fields directly into
    the tool arguments. This tool does deterministic validation, default end-time
    completion, missing-field detection, and confirmation text preparation. It does
    not call another model internally.
    """
    normalized_items = [
        _normalize_calendar_item(item, timezone, default_duration_minutes)
        for item in (items or [])
        if isinstance(item, dict)
    ]

    if not normalized_items:
        normalized_items = [
            _normalize_calendar_item(
                {"title": _fallback_event_title(message), "description": message},
                timezone,
                default_duration_minutes,
            )
        ]

    needs_follow_up = any(item["missing_fields"] for item in normalized_items)
    follow_up_question = _build_calendar_follow_up_question(normalized_items) if needs_follow_up else ""

    return {
        "source_message": message,
        "timezone": timezone,
        "default_duration_minutes": max(int(default_duration_minutes or 60), 1),
        "items": normalized_items,
        "needs_follow_up": needs_follow_up,
        "follow_up_question": follow_up_question,
        "confirmation_text": _build_confirmation_text(normalized_items, needs_follow_up),
    }


def prepare_calendar_event_confirmation(
    items: List[dict[str, Any]],
    timezone: str = "Asia/Shanghai",
    default_duration_minutes: int = 60,
) -> dict[str, Any]:
    """Prepare a safe confirmation payload before any future calendar write action."""
    completed = complete_schedule_fields(
        message="",
        items=items,
        timezone=timezone,
        default_duration_minutes=default_duration_minutes,
    )
    can_create = not completed["needs_follow_up"]
    return {
        "confirmation_required": True,
        "can_create_after_user_confirms": can_create,
        "items": completed["items"],
        "confirmation_text": completed["confirmation_text"],
        "next_action": "ask_user_to_confirm" if can_create else "ask_user_for_missing_fields",
        "follow_up_question": completed["follow_up_question"],
        "safety_note": "\u8fd9\u4e2a\u5de5\u5177\u53ea\u51c6\u5907\u786e\u8ba4\u4fe1\u606f\uff0c\u4e0d\u4f1a\u76f4\u63a5\u5199\u5165\u6216\u4fee\u6539\u7528\u6237\u65e5\u5386\u3002",
    }


def split_high_pressure_task(
    title: str,
    difficulty: str = "high",
    duration_minutes: int = 120,
    emotion_type: str = "anxious",
    emotion_level: int = 3,
) -> dict[str, Any]:
    """Split a demanding task into lower-pressure subtasks adapted to emotion."""
    safe_title = (title or "high-pressure task").strip()[:80]
    safe_difficulty = (difficulty or "medium").lower()
    safe_duration = min(max(int(duration_minutes or 45), 10), 480)
    safe_level = min(max(int(emotion_level or 3), 1), 5)
    safe_emotion = (emotion_type or "unclear").lower()

    chunk_minutes = _recommended_chunk_minutes(safe_emotion, safe_difficulty, safe_level)
    break_minutes = _recommended_break_minutes(safe_emotion, safe_level)
    subtasks = _build_subtasks(safe_title, safe_duration, chunk_minutes, safe_emotion, safe_difficulty)

    return {
        "original_task": {
            "title": safe_title,
            "difficulty": safe_difficulty,
            "duration_minutes": safe_duration,
        },
        "emotion": {
            "emotion_type": safe_emotion,
            "emotion_level": safe_level,
        },
        "recommended_focus_minutes": chunk_minutes,
        "recommended_break_minutes": break_minutes,
        "subtasks": subtasks,
        "planning_note": _split_note(safe_emotion, safe_difficulty),
    }


async def create_adaptive_schedule_plan(
    message: str,
    tasks: Optional[List[dict[str, Any]]] = None,
    timezone: str = "Asia/Shanghai",
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
) -> dict[str, Any]:
    """Create an emotion-adaptive time-blocked schedule using LLM planning plus code validation."""
    parsed_tasks = [PlanningTask.model_validate(task) for task in (tasks or [])]
    emotion_payload = await emotion_llm_service.analyze(message=message, history=[], timezone=timezone)
    return await schedule_llm_service.create_plan(
        message=message,
        tasks=parsed_tasks,
        emotion=emotion_payload,
        timezone=timezone,
        start_time=start_time,
        end_time=end_time,
    )


def build_default_registry() -> ToolRegistry:
    registry = ToolRegistry()

    registry.register(
        name="analyze_user_emotion",
        description=(
            "Use the LLM-backed emotion recognizer to detect the user's current emotional type, "
            "intensity, confidence, visible/hidden cues, secondary emotions, and whether a gentle "
            "follow-up question is needed."
        ),
        parameters=object_schema(
            {
                "message": {"type": "string", "description": "The latest user message."},
                "history": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Recent conversation text snippets, newest last.",
                },
            },
            required=["message"],
        ),
        handler=analyze_user_emotion,
    )

    registry.register(
        name="extract_planning_tasks",
        description=(
            "Extract task title, difficulty and duration from the user's text. "
            "Use this before schedule planning when tasks are not already structured."
        ),
        parameters=object_schema(
            {"message": {"type": "string", "description": "The user text that may contain todo items."}},
            required=["message"],
        ),
        handler=extract_planning_tasks,
    )

    calendar_event_schema = object_schema(
        {
            "title": {
                "type": ["string", "null"],
                "description": "Event title extracted from the user's request. Use null if unknown.",
            },
            "location": {"type": ["string", "null"], "description": "Event location if provided."},
            "start_time": {
                "type": ["string", "null"],
                "description": "Start time, preferably YYYY-MM-DD HH:MM:SS. Use null if unknown.",
            },
            "end_time": {
                "type": ["string", "null"],
                "description": "End time, preferably YYYY-MM-DD HH:MM:SS. Use null if unknown.",
            },
            "participants": {
                "type": "array",
                "items": {"type": "string"},
                "description": "People involved in the event.",
            },
            "description": {"type": ["string", "null"], "description": "Extra event notes."},
        },
        required=[],
    )

    registry.register(
        name="complete_schedule_fields",
        description=(
            "Validate and normalize structured calendar event fields that you extracted "
            "from the user's message. Long-term design: YOU should fill the items argument "
            "with extracted title/location/start_time/end_time/participants/description; "
            "this tool only normalizes times, fills default end_time, and reports missing fields. "
            "Use this for adding/saving/completing concrete calendar events, not for whole-day planning."
        ),
        parameters=object_schema(
            {
                "message": {"type": "string", "description": "The original user request."},
                "items": {
                    "type": "array",
                    "items": calendar_event_schema,
                    "description": "Calendar event drafts extracted by the model from the user request.",
                },
                "timezone": {"type": "string", "default": "Asia/Shanghai"},
                "default_duration_minutes": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 1440,
                    "default": 60,
                    "description": "Default duration when start_time exists but end_time is missing.",
                },
            },
            required=["message", "items"],
        ),
        handler=complete_schedule_fields,
    )

    registry.register(
        name="prepare_calendar_event_confirmation",
        description=(
            "Prepare a safe confirmation payload before any future calendar write action. "
            "Use this after complete_schedule_fields when the user wants the event added to a calendar. "
            "This tool does not create or modify events; it only prepares confirmation text and says "
            "whether the event is ready to create after user confirmation."
        ),
        parameters=object_schema(
            {
                "items": {
                    "type": "array",
                    "items": calendar_event_schema,
                    "description": "Normalized or extracted calendar event drafts.",
                },
                "timezone": {"type": "string", "default": "Asia/Shanghai"},
                "default_duration_minutes": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": 1440,
                    "default": 60,
                },
            },
            required=["items"],
        ),
        handler=prepare_calendar_event_confirmation,
    )

    task_schema = object_schema(
        {
            "title": {"type": "string"},
            "difficulty": {"type": "string", "enum": ["low", "medium", "high"]},
            "duration_minutes": {"type": "integer", "minimum": 5, "maximum": 480},
            "deadline": {"type": ["string", "null"]},
        },
        required=["title", "difficulty", "duration_minutes"],
    )

    registry.register(
        name="split_high_pressure_task",
        description=(
            "Split a high-difficulty or long task into smaller low-pressure subtasks. "
            "Use this before schedule planning when the user is anxious, depressed, tired, "
            "low-motivation, irritable, or when a task feels overwhelming. The returned "
            "subtasks can be passed into create_adaptive_schedule_plan."
        ),
        parameters=object_schema(
            {
                "title": {"type": "string", "description": "Original task title."},
                "difficulty": {"type": "string", "enum": ["low", "medium", "high"], "default": "high"},
                "duration_minutes": {"type": "integer", "minimum": 10, "maximum": 480, "default": 120},
                "emotion_type": {
                    "type": "string",
                    "enum": [
                        "anxious",
                        "depressed",
                        "tired",
                        "low_motivation",
                        "positive_calm",
                        "excited_irritable",
                        "unclear",
                    ],
                    "default": "anxious",
                },
                "emotion_level": {"type": "integer", "minimum": 1, "maximum": 5, "default": 3},
            },
            required=["title"],
        ),
        handler=split_high_pressure_task,
    )

    registry.register(
        name="create_adaptive_schedule_plan",
        description=(
            "Generate a warm, emotion-adaptive schedule with time blocks, durations, "
            "breaks, buffers, and regulation suggestions. Use this after emotion analysis "
            "and task extraction/splitting when the user asks for a daily plan or adaptive schedule."
        ),
        parameters=object_schema(
            {
                "message": {"type": "string", "description": "The latest user message and emotional context."},
                "tasks": array_of(task_schema),
                "timezone": {"type": "string", "default": "Asia/Shanghai"},
                "start_time": {
                    "type": ["string", "null"],
                    "description": "Optional start time, for example 09:00 or 2026-08-04 09:00.",
                },
                "end_time": {
                    "type": ["string", "null"],
                    "description": "Optional end time, for example 18:00 or 2026-08-04 18:00.",
                },
            },
            required=["message"],
        ),
        handler=create_adaptive_schedule_plan,
    )

    return registry


def _normalize_calendar_item(
    raw: dict[str, Any],
    timezone: str,
    default_duration_minutes: int,
) -> dict[str, Any]:
    title = _as_clean_string(raw.get("title"))
    location = _as_clean_string(raw.get("location"))
    start_time = _normalize_datetime_text(raw.get("start_time"), timezone)
    end_time = _normalize_datetime_text(raw.get("end_time"), timezone)
    participants = _normalize_participants(raw.get("participants"))
    description = _as_clean_string(raw.get("description"))

    duration = max(int(default_duration_minutes or 60), 1)
    if start_time and not end_time:
        parsed_start = _parse_iso_datetime(start_time)
        if parsed_start is not None:
            end_time = (parsed_start + timedelta(minutes=duration)).strftime("%Y-%m-%d %H:%M:%S")
    elif start_time and end_time:
        parsed_start = _parse_iso_datetime(start_time)
        parsed_end = _parse_iso_datetime(end_time)
        if parsed_start is not None and parsed_end is not None and parsed_end <= parsed_start:
            end_time = (parsed_start + timedelta(minutes=duration)).strftime("%Y-%m-%d %H:%M:%S")

    missing_fields: List[str] = []
    if not title:
        missing_fields.append("title")
    if not start_time:
        missing_fields.append("start_time")

    return {
        "title": title,
        "location": location,
        "start_time": start_time,
        "end_time": end_time,
        "participants": participants,
        "description": description,
        "missing_fields": missing_fields,
        "is_ready_to_create": not missing_fields,
    }


def _normalize_datetime_text(value: Any, timezone: str) -> Optional[str]:
    text = _as_clean_string(value)
    if not text:
        return None

    normalized = text.replace("T", " ").replace("/", "-").strip()
    if normalized.endswith("Z"):
        normalized = normalized[:-1].strip()
    if len(normalized) == 16 and normalized[4] == "-" and normalized[13] == ":":
        normalized = normalized + ":00"

    parsed = _parse_iso_datetime(normalized)
    if parsed is not None:
        return parsed.strftime("%Y-%m-%d %H:%M:%S")

    # Keep unresolved natural-language time so the model can ask a precise follow-up.
    return text


def _parse_iso_datetime(value: str) -> Optional[datetime]:
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


def _normalize_participants(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [item for item in (_as_clean_string(part) for part in value) if item]
    text = _as_clean_string(value)
    if not text:
        return []
    for sep in ["\u3001", "\uff0c", ",", ";", "\uff1b", "|", "/"]:
        text = text.replace(sep, "|")
    return [part.strip() for part in text.split("|") if part.strip()]


def _as_clean_string(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _fallback_event_title(message: str) -> str:
    text = _as_clean_string(message)
    if not text:
        return ""
    return text[:40]


def _build_calendar_follow_up_question(items: List[dict[str, Any]]) -> str:
    missing = []
    for item in items:
        for field in item.get("missing_fields", []):
            if field not in missing:
                missing.append(field)
    labels = ", ".join(missing)
    return f"\u8fd8\u9700\u8981\u786e\u8ba4\u8fd9\u4e9b\u5fc5\u586b\u5b57\u6bb5\uff1a{labels}\u3002" if labels else "\u8fd8\u6709\u5c11\u91cf\u65e5\u7a0b\u4fe1\u606f\u9700\u8981\u786e\u8ba4\u3002"


def _build_confirmation_text(items: List[dict[str, Any]], needs_follow_up: bool) -> str:
    lines = ["\u6211\u5148\u628a\u65e5\u7a0b\u8349\u7a3f\u6574\u7406\u6210\u8fd9\u6837\uff1a"]
    for index, item in enumerate(items, start=1):
        title = item.get("title") or "\u672a\u586b\u5199\u6807\u9898"
        start = item.get("start_time") or "\u672a\u586b\u5199\u5f00\u59cb\u65f6\u95f4"
        end = item.get("end_time") or "\u672a\u586b\u5199\u7ed3\u675f\u65f6\u95f4"
        location = item.get("location") or "\u672a\u6307\u5b9a"
        participants = "\u3001".join(item.get("participants") or []) or "\u65e0"
        lines.append(f"{index}. {title}\uff5c{start} - {end}\uff5c\u5730\u70b9\uff1a{location}\uff5c\u53c2\u4e0e\u4eba\uff1a{participants}")
    if needs_follow_up:
        lines.append(_build_calendar_follow_up_question(items))
    else:
        lines.append("\u4fe1\u606f\u5df2\u9f50\u5168\uff0c\u4f46\u9700\u8981\u7528\u6237\u786e\u8ba4\u540e\u624d\u80fd\u7ee7\u7eed\u521b\u5efa\u65e5\u7a0b\u3002")
    return "\n".join(lines)


def _now_iso(timezone: str) -> str:
    try:
        return datetime.now(ZoneInfo(timezone)).isoformat()
    except ZoneInfoNotFoundError:
        return datetime.now().isoformat()


def _recommended_chunk_minutes(emotion_type: str, difficulty: str, level: int) -> int:
    if emotion_type in {"anxious", "depressed", "tired"}:
        return 20 if level >= 4 or difficulty == "high" else 25
    if emotion_type == "low_motivation":
        return 15 if level >= 3 else 20
    if emotion_type == "excited_irritable":
        return 25
    if difficulty == "high":
        return 40
    return 45


def _recommended_break_minutes(emotion_type: str, level: int) -> int:
    if emotion_type in {"anxious", "depressed", "tired"}:
        return 10 if level >= 4 else 8
    if emotion_type == "low_motivation":
        return 10
    if emotion_type == "excited_irritable":
        return 12
    return 8


def _build_subtasks(
    title: str,
    duration_minutes: int,
    chunk_minutes: int,
    emotion_type: str,
    difficulty: str,
) -> List[dict[str, Any]]:
    starters = _starter_steps(title, emotion_type, difficulty)
    subtasks: List[dict[str, Any]] = []
    remaining = duration_minutes

    for index, starter in enumerate(starters, start=1):
        if remaining <= 0:
            break
        minutes = min(chunk_minutes, remaining)
        subtasks.append(
            {
                "title": starter,
                "difficulty": "low" if emotion_type in NEGATIVE_OR_STRAINED_EMOTIONS else "medium",
                "duration_minutes": minutes,
                "suggestion": _subtask_suggestion(emotion_type, index),
            }
        )
        remaining -= minutes

    index = len(subtasks) + 1
    while remaining > 0:
        minutes = min(chunk_minutes, remaining)
        subtasks.append(
            {
                "title": f"\u7ee7\u7eed\u63a8\u8fdb\uff1a{title}\uff08\u7b2c {index} \u5c0f\u6bb5\uff09",
                "difficulty": "medium" if difficulty == "high" else difficulty,
                "duration_minutes": minutes,
                "suggestion": _subtask_suggestion(emotion_type, index),
            }
        )
        remaining -= minutes
        index += 1

    return subtasks


def _starter_steps(title: str, emotion_type: str, difficulty: str) -> List[str]:
    if emotion_type == "low_motivation":
        return [
            f"\u53ea\u505a\u542f\u52a8\uff1a\u6253\u5f00{title}\u76f8\u5173\u6750\u6599",
            f"\u5217\u51fa{title}\u7684\u6700\u5c0f\u4e0b\u4e00\u6b65",
            f"\u5b8c\u6210{title}\u7684\u4e00\u4e2a\u4f4e\u95e8\u69db\u5c0f\u6bb5",
        ]
    if emotion_type in {"anxious", "depressed", "tired"}:
        return [
            f"\u68b3\u7406{title}\u8981\u6c42\uff0c\u4e0d\u6025\u7740\u4ea7\u51fa",
            "\u5217\u4e00\u4e2a\u7c97\u7565\u63d0\u7eb2\u6216\u6b65\u9aa4\u6e05\u5355",
            f"\u5b8c\u6210{title}\u4e2d\u6700\u5bb9\u6613\u7684\u4e00\u5c0f\u5757",
        ]
    if emotion_type == "excited_irritable":
        return [
            f"\u5148\u786e\u5b9a{title}\u7684\u5b8c\u6210\u6807\u51c6",
            f"\u6309\u987a\u5e8f\u63a8\u8fdb{title}\u7b2c\u4e00\u5c0f\u6bb5",
            "\u68c0\u67e5\u65b9\u5411\uff0c\u907f\u514d\u6025\u7740\u8df3\u6b65",
        ]
    if difficulty == "high":
        return [
            f"\u660e\u786e{title}\u76ee\u6807\u548c\u5b8c\u6210\u6807\u51c6",
            f"\u5904\u7406{title}\u7684\u5173\u952e\u90e8\u5206",
            f"\u68c0\u67e5\u5e76\u5b8c\u5584{title}",
        ]
    return [f"\u63a8\u8fdb{title}\u7b2c\u4e00\u90e8\u5206", f"\u63a8\u8fdb{title}\u7b2c\u4e8c\u90e8\u5206", f"\u6536\u5c3e\u68c0\u67e5{title}"]


def _subtask_suggestion(emotion_type: str, index: int) -> str:
    if emotion_type in {"anxious", "depressed", "tired"}:
        return "\u8fd9\u4e00\u6bb5\u53ea\u8ffd\u6c42\u5f00\u59cb\u548c\u63a8\u8fdb\u4e00\u6b65\uff1b\u7ed3\u675f\u540e\u559d\u6c34\u6216\u79bb\u5c4f 5-10 \u5206\u949f\u3002"
    if emotion_type == "low_motivation":
        return "\u628a\u6807\u51c6\u964d\u5230\u6700\u4f4e\uff0c\u5b8c\u6210\u8fd9\u4e00\u5c0f\u6bb5\u5c31\u7b97\u6709\u6548\u63a8\u8fdb\u3002"
    if emotion_type == "excited_irritable":
        return "\u5f00\u59cb\u524d\u6162\u547c\u5438 3 \u6b21\uff0c\u505a\u5b8c\u5f53\u524d\u5c0f\u6bb5\u518d\u5207\u6362\u3002"
    if index == 1:
        return "\u5148\u7a33\u5b9a\u8fdb\u5165\u72b6\u6001\uff0c\u4e0d\u9700\u8981\u4e00\u5f00\u59cb\u5c31\u51b2\u523a\u3002"
    return "\u4fdd\u6301\u4e13\u6ce8\uff0c\u5230\u70b9\u5c31\u505c\uff0c\u907f\u514d\u8fc7\u5ea6\u6d88\u8017\u3002"


def _split_note(emotion_type: str, difficulty: str) -> str:
    if emotion_type in {"anxious", "depressed", "tired"}:
        return "\u4e0d\u5efa\u8bae\u8fde\u7eed\u786c\u625b\u9ad8\u538b\u4efb\u52a1\uff0c\u62c6\u5c0f\u540e\u6bcf\u6bb5\u4e4b\u95f4\u63d2\u5165\u77ed\u4f11\u606f\u3002"
    if emotion_type == "low_motivation":
        return "\u91cd\u70b9\u4e0d\u662f\u4e00\u6b21\u505a\u5b8c\uff0c\u800c\u662f\u7528\u4f4e\u95e8\u69db\u6b65\u9aa4\u6062\u590d\u542f\u52a8\u611f\u3002"
    if emotion_type == "excited_irritable":
        return "\u62c6\u5206\u7684\u76ee\u7684\u4e0d\u662f\u52a0\u901f\uff0c\u800c\u662f\u8ba9\u8282\u594f\u7a33\u5b9a\u4e0b\u6765\u3002"
    if difficulty == "high":
        return "\u9ad8\u96be\u4efb\u52a1\u9002\u5408\u5148\u5b9a\u6807\u51c6\u3001\u518d\u5206\u6bb5\u63a8\u8fdb\u3002"
    return "\u8fd9\u4e2a\u4efb\u52a1\u538b\u529b\u4e0d\u9ad8\uff0c\u53ef\u4ee5\u6309\u81ea\u7136\u6bb5\u843d\u63a8\u8fdb\u3002"
