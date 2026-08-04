from typing import Iterable, List, Optional

from app.schemas.chat import AttachmentInfo, ChatMessage


CHAT_RULES = (
    "You are a concise assistant. "
    "Answer the user's latest request directly. "
    "Do not expose chain-of-thought. "
    "Do not output <think> tags. "
    "Do not wrap the answer in JSON unless the user explicitly asks for JSON."
)

PERSONALITY_INSTRUCTIONS = {
    "gentle": "Use a warm, patient, caring companion tone. Be supportive without being patronizing.",
    "lively": "Use an upbeat, energetic companion tone. Keep the response natural and helpful.",
    "cool": "Use a calm, concise, slightly reserved companion tone. Remain respectful and helpful.",
    "tsundere": "Use a light, playful tsundere-style companion tone. Do not insult, shame, or refuse reasonable help.",
}


def build_personality_instruction(personality: Optional[str], custom_personality: Optional[str]) -> str:
    """Turn a user-selected companion style into a bounded prompt instruction."""
    if personality == "custom" and custom_personality and custom_personality.strip():
        return "Act as a caring companion. User-defined personality: " + custom_personality.strip()[:1000]
    return PERSONALITY_INSTRUCTIONS.get(personality or "gentle", PERSONALITY_INSTRUCTIONS["gentle"])


def build_prompt(
    system_prompt: Optional[str],
    history: List[ChatMessage],
    user_message: str,
    attachments: Optional[Iterable[AttachmentInfo]] = None,
    personality: Optional[str] = "gentle",
    custom_personality: Optional[str] = None,
) -> str:
    parts = [f"System instructions: {CHAT_RULES}"]
    parts.append(f"Companion personality: {build_personality_instruction(personality, custom_personality)}")

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
