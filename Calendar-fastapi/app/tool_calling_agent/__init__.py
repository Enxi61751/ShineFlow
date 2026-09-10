"""Native tool-calling agent package for ShineFlow.

This package is intentionally isolated from the existing /api/chat flow so the
project can upgrade to a real LLM-tool loop without breaking current behavior.
"""

from app.tool_calling_agent.agent import ToolCallingScheduleAgent

__all__ = ["ToolCallingScheduleAgent"]
