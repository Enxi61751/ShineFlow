from __future__ import annotations

from typing import Any, Dict, List, Optional

import httpx

from app.core.config import settings


class OpenAICompatibleToolClient:
    """Minimal OpenAI-compatible chat client with native tool-calling support."""

    async def chat(
        self,
        *,
        messages: List[dict[str, Any]],
        tools: List[dict[str, Any]],
        tool_choice: str | dict[str, Any] = "auto",
        temperature: Optional[float] = None,
        top_p: Optional[float] = None,
        max_tokens: Optional[int] = None,
    ) -> dict[str, Any]:
        if not settings.REMOTE_LLM_BASE_URL.strip():
            raise RuntimeError(
                "Native tool-calling requires REMOTE_LLM_BASE_URL to point to an "
                "OpenAI-compatible chat-completions endpoint that supports tools."
            )

        base_url = settings.REMOTE_LLM_BASE_URL.rstrip("/")
        chat_path = settings.REMOTE_LLM_CHAT_PATH.strip() or "/v1/chat/completions"
        if not chat_path.startswith("/"):
            chat_path = "/" + chat_path

        payload: Dict[str, Any] = {
            "messages": messages,
            "tools": tools,
            "tool_choice": tool_choice,
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
            response = await client.post(f"{base_url}{chat_path}", headers=headers, json=payload)
            response.raise_for_status()
            data = response.json()

        try:
            message = data["choices"][0]["message"]
        except (KeyError, IndexError, TypeError) as exc:
            raise RuntimeError(f"Unexpected tool-calling response format: {data}") from exc

        if not isinstance(message, dict):
            raise RuntimeError(f"Unexpected assistant message format: {message}")
        return message
