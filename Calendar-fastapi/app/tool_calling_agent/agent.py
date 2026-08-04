from __future__ import annotations

import json
from datetime import datetime
from typing import Any, List, Optional
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.tool_calling_agent.calendar_tools import build_default_registry
from app.tool_calling_agent.llm_client import OpenAICompatibleToolClient
from app.tool_calling_agent.models import (
    AgentMessage,
    ToolCallingAgentRequest,
    ToolCallingAgentResponse,
    ToolTrace,
)
from app.tool_calling_agent.tool_registry import ToolRegistry, parse_tool_arguments


SYSTEM_PROMPT = """You are ShineFlow's native tool-calling emotion-aware schedule Agent.

Working rules:
1. When emotion detection, task extraction, calendar-event drafting, task splitting, or adaptive planning is needed, prefer calling tools instead of guessing only from free text.
2. Recommended tool usage:
   - analyze_user_emotion: detect current emotion type, intensity, cues, confidence, and follow-up needs.
   - extract_planning_tasks: extract task title, difficulty, and duration when the user gives natural-language todos.
   - complete_schedule_fields: use for concrete calendar-event add/save/complete requests. You must extract title/location/start_time/end_time/participants/description into the items argument yourself; the tool only validates, normalizes, fills default end_time, and reports missing fields. The tool does not call another model internally.
   - prepare_calendar_event_confirmation: after complete_schedule_fields, use this when the user wants the event added/saved. It prepares a confirmation payload only. Do not claim the event has been written before user confirmation or before a real write tool exists.
   - split_high_pressure_task: use when emotion is anxious/depressed/tired/low_motivation/excited_irritable and a task is high difficulty, long, or overwhelming.
   - create_adaptive_schedule_plan: use after emotion analysis and task extraction/splitting for whole-day or time-block adaptive schedule planning.
3. If emotional cues are vague, gently state uncertainty and ask a light follow-up. Do not ignore hidden negative emotion.
4. Final user-facing replies must be in warm Chinese: empathy first, then emotion/status understanding, then clear schedule/event draft, duration labels where relevant, emotion-regulation tips, and an adjustment question.
5. Do not reveal chain-of-thought, raw JSON, or internal tool traces to the user unless explicitly asked for debugging.
6. If tool results are sufficient, summarize and polish them; do not invent conflicting times or task details.
7. For create/save calendar-event requests, clearly distinguish "drafted and waiting for confirmation" from "created". In the current backend there is no real calendar write tool, so only say it is organized for confirmation.
"""


class ToolCallingScheduleAgent:
    """A small native tool-calling loop for ShineFlow's schedule planner.

    This class keeps the existing deterministic Python emotion/schedule utilities as
    callable tools, while letting an OpenAI-compatible LLM decide when and how to
    call those tools.
    """

    def __init__(
        self,
        *,
        registry: Optional[ToolRegistry] = None,
        client: Optional[OpenAICompatibleToolClient] = None,
    ) -> None:
        self.registry = registry or build_default_registry()
        self.client = client or OpenAICompatibleToolClient()

    async def run(self, request: ToolCallingAgentRequest) -> ToolCallingAgentResponse:
        messages = self._build_initial_messages(request)
        traces: List[ToolTrace] = []
        tools = self.registry.openai_tools()

        for _round_index in range(request.max_tool_rounds):
            assistant_message = await self.client.chat(
                messages=messages,
                tools=tools,
                tool_choice="auto",
            )
            normalized_assistant = _normalize_assistant_message(assistant_message)
            messages.append(normalized_assistant)

            tool_calls = normalized_assistant.get("tool_calls") or []
            if not tool_calls:
                return ToolCallingAgentResponse(
                    success=True,
                    reply=(normalized_assistant.get("content") or "").strip(),
                    tool_traces=traces,
                    raw_messages=messages,
                )

            for tool_call in tool_calls:
                trace, tool_message = await self._execute_tool_call(tool_call)
                traces.append(trace)
                messages.append(tool_message)

        messages.append(
            {
                "role": "system",
                "content": (
                    "The maximum tool-calling rounds have been reached. Based only on the tool results so far, "
                    "give a final warm Chinese reply. If information is insufficient, ask a short follow-up question."
                ),
            }
        )
        final_message = await self.client.chat(
            messages=messages,
            tools=tools,
            tool_choice="none",
        )
        normalized_final = _normalize_assistant_message(final_message)
        messages.append(normalized_final)

        return ToolCallingAgentResponse(
            success=True,
            reply=(normalized_final.get("content") or "").strip(),
            tool_traces=traces,
            raw_messages=messages,
        )

    async def _execute_tool_call(self, tool_call: dict[str, Any]) -> tuple[ToolTrace, dict[str, Any]]:
        call_id = str(tool_call.get("id") or "")
        function_info = tool_call.get("function") or {}
        tool_name = str(function_info.get("name") or "")
        raw_arguments = function_info.get("arguments")

        arguments: dict[str, Any] = {}
        try:
            arguments = parse_tool_arguments(raw_arguments)
            result = await self.registry.execute(tool_name, arguments)
            trace = ToolTrace(tool_name=tool_name, arguments=arguments, result=result)
            content = {"success": True, "result": result}
        except Exception as exc:  # Keep the agent loop recoverable.
            trace = ToolTrace(tool_name=tool_name, arguments=arguments, error=str(exc))
            content = {"success": False, "error": str(exc)}

        return trace, {
            "role": "tool",
            "tool_call_id": call_id,
            "name": tool_name,
            "content": json.dumps(content, ensure_ascii=False),
        }

    def _build_initial_messages(self, request: ToolCallingAgentRequest) -> List[dict[str, Any]]:
        now_text = _now_text(request.timezone)
        messages: List[dict[str, Any]] = [
            {
                "role": "system",
                "content": (
                    f"{SYSTEM_PROMPT}\n\n"
                    f"Current time reference: {now_text}\n"
                    f"User timezone: {request.timezone}\n"
                    f"Requested schedule start_time: {request.start_time or 'not specified'}\n"
                    f"Requested schedule end_time: {request.end_time or 'not specified'}"
                ),
            }
        ]

        for item in request.history:
            normalized = _normalize_history_message(item)
            if normalized is not None:
                messages.append(normalized)

        user_parts = [request.message]
        if request.start_time or request.end_time or request.timezone:
            user_parts.append(
                "\n[Planning parameters] "
                f"timezone={request.timezone}; "
                f"start_time={request.start_time or 'not specified'}; "
                f"end_time={request.end_time or 'not specified'}"
            )
        messages.append({"role": "user", "content": "\n".join(user_parts).strip()})
        return messages


def _normalize_history_message(message: AgentMessage) -> Optional[dict[str, Any]]:
    role = message.role.strip().lower()
    if role not in {"system", "user", "assistant", "tool"}:
        return None

    payload: dict[str, Any] = {"role": role, "content": message.content or ""}
    if message.name:
        payload["name"] = message.name
    if role == "tool" and message.tool_call_id:
        payload["tool_call_id"] = message.tool_call_id
    return payload


def _normalize_assistant_message(message: dict[str, Any]) -> dict[str, Any]:
    normalized: dict[str, Any] = {
        "role": "assistant",
        "content": message.get("content") or "",
    }
    if message.get("tool_calls"):
        normalized["tool_calls"] = message["tool_calls"]
    if message.get("name"):
        normalized["name"] = message["name"]
    return normalized


def _now_text(timezone: str) -> str:
    try:
        tz = ZoneInfo(timezone)
        return datetime.now(tz).strftime("%Y-%m-%d %H:%M %Z")
    except ZoneInfoNotFoundError:
        return datetime.now().strftime("%Y-%m-%d %H:%M")
