import json
import os
import re
from typing import Any, Optional

import httpx

from app.core.config import settings
from app.core.logger import get_logger

try:
    from llama_cpp import Llama
except ImportError:  # pragma: no cover
    Llama = None


logger = get_logger(__name__)


THINK_BLOCK_RE = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)
LEADING_ROLE_RE = re.compile(r"^(assistant|user|system)\s*[:：]\s*", re.IGNORECASE)
ROLE_BOUNDARY_RE = re.compile(r"\n(?:assistant|user|system)\s*[:：]", re.IGNORECASE)
JSON_BLOCK_RE = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL | re.IGNORECASE)
ANSWER_SECTION_RE = re.compile(r"(?:final answer|answer)\s*[:：]\s*(.+)", re.DOTALL | re.IGNORECASE)
SELF_CHECK_LINE_RE = re.compile(r"^(?:-+\s*)?(?:check|self-check|validation)\s*[:：]", re.IGNORECASE)
META_LINE_PREFIXES = (
    "system instructions:",
    "additional instructions:",
    "attachments:",
    "conversation history:",
    "latest user message:",
)
EMPTY_REPLY_FALLBACK = "The model returned an empty reply. Please try again."


class LLMService:
    def __init__(self) -> None:
        self._llm: Optional["Llama"] = None
        self._use_remote = bool(settings.REMOTE_LLM_BASE_URL.strip())

        if self._use_remote:
            logger.info("Using remote LLM endpoint: %s", settings.REMOTE_LLM_BASE_URL)
        else:
            self._load_local_model()

    @property
    def model_name(self) -> str:
        if settings.REMOTE_LLM_MODEL.strip():
            return settings.REMOTE_LLM_MODEL.strip()
        return os.path.basename(settings.MODEL_PATH)

    async def chat(
        self,
        prompt: str,
        clean: bool = True,
        *,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
        top_p: Optional[float] = None,
    ) -> str:
        if self._use_remote:
            raw_reply = await self._chat_remote(
                prompt,
                max_tokens=max_tokens,
                temperature=temperature,
                top_p=top_p,
            )
        else:
            raw_reply = self._chat_local(
                prompt,
                max_tokens=max_tokens,
                temperature=temperature,
                top_p=top_p,
            )

        if clean:
            return self._clean_reply(raw_reply)
        return (raw_reply or "").strip()

    def _load_local_model(self) -> None:
        if Llama is None:
            raise RuntimeError(
                "llama-cpp-python is not installed, and REMOTE_LLM_BASE_URL is not configured."
            )

        logger.info("Loading local GGUF model from %s", settings.MODEL_PATH)
        self._llm = Llama(
            model_path=settings.MODEL_PATH,
            n_ctx=settings.N_CTX,
            verbose=False,
        )
        logger.info("Local GGUF model loaded.")

    def _chat_local(
        self,
        prompt: str,
        *,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
        top_p: Optional[float] = None,
    ) -> str:
        if self._llm is None:
            raise RuntimeError("Local model is not initialized.")

        output = self._llm(
            prompt,
            max_tokens=max_tokens if max_tokens is not None else settings.MAX_TOKENS,
            temperature=temperature if temperature is not None else settings.TEMPERATURE,
            top_p=top_p if top_p is not None else settings.TOP_P,
        )
        return output["choices"][0]["text"]

    async def _chat_remote(
        self,
        prompt: str,
        *,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
        top_p: Optional[float] = None,
    ) -> str:
        base_url = settings.REMOTE_LLM_BASE_URL.rstrip("/")
        chat_path = settings.REMOTE_LLM_CHAT_PATH.strip()
        if not chat_path.startswith("/"):
            chat_path = "/" + chat_path

        payload = {
            "messages": [{"role": "user", "content": prompt}],
            "temperature": temperature if temperature is not None else settings.TEMPERATURE,
            "top_p": top_p if top_p is not None else settings.TOP_P,
            "max_tokens": max_tokens if max_tokens is not None else settings.MAX_TOKENS,
        }
        if settings.REMOTE_LLM_MODEL.strip():
            payload["model"] = settings.REMOTE_LLM_MODEL.strip()

        headers = {"Content-Type": "application/json"}
        if settings.REMOTE_LLM_API_KEY.strip():
            headers["Authorization"] = f"Bearer {settings.REMOTE_LLM_API_KEY.strip()}"

        async with httpx.AsyncClient(timeout=settings.REMOTE_LLM_TIMEOUT_SECONDS) as client:
            response = await client.post(
                f"{base_url}{chat_path}",
                headers=headers,
                json=payload,
            )
            response.raise_for_status()
            data = response.json()

        content = self._extract_remote_content(data)
        if content is None:
            raise RuntimeError(f"Unsupported remote response format: {json.dumps(data)[:500]}")
        return content

    def _extract_remote_content(self, payload: Any) -> Optional[str]:
        if not isinstance(payload, dict):
            return None

        choices = payload.get("choices")
        if isinstance(choices, list) and choices:
            first = choices[0]
            if isinstance(first, dict):
                message = first.get("message")
                if isinstance(message, dict):
                    content = message.get("content")
                    extracted = self._extract_message_content(content)
                    if extracted is not None:
                        return extracted

                text = first.get("text")
                if isinstance(text, str):
                    return text

        output = payload.get("output")
        if isinstance(output, list):
            parts = []
            for item in output:
                if not isinstance(item, dict):
                    continue
                content = item.get("content")
                extracted = self._extract_message_content(content)
                if extracted:
                    parts.append(extracted)
            if parts:
                return "\n".join(parts)

        for key in ("reply", "content", "text", "answer"):
            value = payload.get(key)
            if isinstance(value, str):
                return value

        return None

    def _extract_message_content(self, content: Any) -> Optional[str]:
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts = []
            for item in content:
                if isinstance(item, dict):
                    text = item.get("text")
                    if isinstance(text, str):
                        parts.append(text)
                elif isinstance(item, str):
                    parts.append(item)
            if parts:
                return "\n".join(parts)
        return None

    def _clean_reply(self, text: str) -> str:
        cleaned = THINK_BLOCK_RE.sub("", (text or "")).strip()

        if "</think>" in cleaned:
            cleaned = cleaned.split("</think>")[-1].strip()

        cleaned = cleaned.replace("<think>", "").replace("</think>", "").strip()
        cleaned = self._extract_json_payload(cleaned) or cleaned
        cleaned = self._truncate_at_role_boundary(cleaned)
        cleaned = self._extract_answer_section(cleaned)
        cleaned = LEADING_ROLE_RE.sub("", cleaned).strip()

        lines = []
        for line in cleaned.splitlines():
            stripped = line.strip()
            if not stripped:
                if lines and lines[-1] != "":
                    lines.append("")
                continue
            if self._is_meta_line(stripped) or self._is_self_check_line(stripped):
                continue
            lines.append(stripped)

        lines = self._dedupe_lines(lines)
        lines = self._collapse_redundant_bullets(lines)
        cleaned = "\n".join(lines).strip()
        cleaned = self._strip_wrapping_quotes(cleaned)

        if self._is_invalid_reply(cleaned):
            return EMPTY_REPLY_FALLBACK

        return cleaned

    def _extract_json_payload(self, text: str) -> Optional[str]:
        candidates = []

        fenced = JSON_BLOCK_RE.search(text)
        if fenced:
            candidates.append(fenced.group(1))

        stripped = text.strip()
        if stripped.startswith("{") and "}" in stripped:
            candidates.append(stripped[: stripped.rfind("}") + 1])

        for candidate in candidates:
            parsed = self._try_parse_json(candidate)
            extracted = self._pick_reply_from_json(parsed)
            if extracted is not None:
                return extracted

        return None

    def _truncate_at_role_boundary(self, text: str) -> str:
        match = ROLE_BOUNDARY_RE.search(text)
        if match:
            return text[: match.start()].strip()
        return text.strip()

    def _extract_answer_section(self, text: str) -> str:
        matches = ANSWER_SECTION_RE.findall(text)
        if not matches:
            return text.strip()

        for candidate in reversed(matches):
            cleaned = candidate.strip()
            if cleaned:
                return cleaned

        return text.strip()

    def _is_meta_line(self, line: str) -> bool:
        return any(line.lower().startswith(prefix) for prefix in META_LINE_PREFIXES)

    def _is_self_check_line(self, line: str) -> bool:
        return bool(SELF_CHECK_LINE_RE.search(line))

    def _strip_wrapping_quotes(self, text: str) -> str:
        if len(text) >= 2 and text[0] == text[-1] and text[0] in {'"', "'"}:
            text = text[1:-1].strip()
        return text.lstrip(":：").strip()

    def _dedupe_lines(self, lines: list[str]) -> list[str]:
        deduped = []
        for line in lines:
            if deduped and line == deduped[-1]:
                continue
            deduped.append(line)
        return deduped

    def _collapse_redundant_bullets(self, lines: list[str]) -> list[str]:
        non_empty = [line for line in lines if line]
        if not non_empty or not all(line.startswith("- ") for line in non_empty):
            return lines

        contents = [line[2:].strip() for line in non_empty]
        longest = max(contents, key=len)
        if all(content == longest or content in longest or longest in content for content in contents):
            return [longest]

        return lines

    def _is_invalid_reply(self, text: str) -> bool:
        if not text:
            return True

        lowered = text.lower()
        if lowered in {"{}", "null", "none"}:
            return True

        if "raw_output" in lowered and "{" in lowered:
            return True

        return False

    def _try_parse_json(self, text: str) -> Optional[Any]:
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return None

    def _pick_reply_from_json(self, payload: Any) -> Optional[str]:
        if not isinstance(payload, dict):
            return None

        for key in ("reply", "content", "answer", "text", "raw_output"):
            value = payload.get(key)
            if isinstance(value, str):
                cleaned = value.strip()
                if cleaned:
                    return cleaned

        return None


llm_service = LLMService()
