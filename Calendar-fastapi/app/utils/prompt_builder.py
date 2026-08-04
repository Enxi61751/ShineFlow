from typing import Iterable, List, Optional

from app.schemas.chat import AttachmentInfo, ChatMessage
from app.utils.emotion_schedule_planner import build_emotion_context, looks_like_schedule_planning_request


CHAT_RULES = """
You are ShineFlow's emotion-aware schedule planning assistant. Answer in warm, natural Chinese unless the user asks for another language.
Core capability: detect emotional cues in the user's words, tone, complaints, stress descriptions, physical feelings, hidden negative signals (insomnia, tiredness, irritability, anxiety rumination, low motivation, calm happiness, relaxation, etc.), then adapt schedule planning to the user's real-time state.
When the user asks for planning, arranging, todo handling, or daily schedule advice, always follow this flow:
1. First offer brief empathy and reassurance based on the current emotion.
2. State the inferred emotion type and approximate intensity in user-friendly language. If cues are unclear, ask a gentle clarifying question, but still provide a flexible draft when enough task information exists.
3. Combine three dimensions: current emotion, user's tasks/difficulty/duration/deadlines, and human energy rhythms (morning focus, midday rest, afternoon dip, late-afternoon second focus, evening low-pressure wind-down).
4. Apply emotion adaptation strictly:
   - Anxiety/depression/fatigue/low mood: split hard tasks, shorten work blocks, insert water/stretch/walk buffers, reduce total workload, put easy low-intensity items first, avoid continuous high pressure.
   - Low motivation/burnout: lower completion standards, use tiny-start steps, add leisure whitespace and gentle healing activities.
   - Positive/calm: balance work, study, exercise, entertainment, and rest with efficient but humane pacing.
   - Excited/irritable: lengthen gaps, add calming transitions, avoid over-tight schedules that amplify restlessness.
5. Output format for plans: warm empathy first, then a clear time-by-time schedule; every item must include duration; include emotion-matched regulation tips; end by asking whether to add/remove tasks or adjust times.
Safety: if the user suggests self-harm or immediate danger, prioritize immediate safety and supportive resources before schedule planning.
General rules: answer the latest request directly; do not expose chain-of-thought; do not output <think> tags; do not wrap the answer in JSON unless explicitly requested.
""".strip()


def build_prompt(
    system_prompt: Optional[str],
    history: List[ChatMessage],
    user_message: str,
    attachments: Optional[Iterable[AttachmentInfo]] = None,
) -> str:
    parts = [f"System instructions: {CHAT_RULES}"]

    history_text = [msg.content for msg in history if msg.role in {"user", "assistant"}]
    if user_message.strip():
        parts.append(build_emotion_context(user_message, history_text))
        if looks_like_schedule_planning_request(user_message):
            parts.append(
                "Schedule-planning reminder: produce the plan in a gentle Chinese style, "
                "with empathy first, time blocks, durations, breaks, emotion regulation tips, "
                "and a final invitation to adjust tasks or times."
            )

    if system_prompt and system_prompt.strip():
        parts.append(f"Additional instructions: {system_prompt.strip()}")

    attachment_lines = _build_attachment_lines(attachments or [])
    if attachment_lines:
        parts.append("Attachments:")
        parts.extend(attachment_lines)

    if history:
        parts.append("Conversation history:")
    for msg in history:
        content = msg.content.strip()
        if not content:
            continue

        if msg.role == "user":
            parts.append(f"- User: {content}")
        elif msg.role == "assistant":
            parts.append(f"- Assistant: {content}")
        elif msg.role == "system":
            parts.append(f"- System: {content}")

    parts.append(f"Latest user message: {user_message.strip()}")
    parts.append("Reply only to the latest user message.")
    parts.append("Final answer:")

    return "\n".join(parts)


def _build_attachment_lines(attachments: Iterable[AttachmentInfo]) -> List[str]:
    lines: List[str] = []
    for item in attachments:
        base = (
            f"- type={item.category}, field={item.field_name}, filename={item.filename}, "
            f"mime={item.content_type}, size_bytes={item.size_bytes}"
        )
        if item.text_excerpt:
            base += f", text_excerpt={item.text_excerpt}"
        lines.append(base)
    return lines
