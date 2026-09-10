from __future__ import annotations

from typing import Any, List, Optional

from pydantic import BaseModel, Field


class AgentMessage(BaseModel):
    role: str = Field(..., description="system/user/assistant/tool")
    content: str = ""
    name: Optional[str] = None
    tool_call_id: Optional[str] = None


class ToolCallingAgentRequest(BaseModel):
    message: str
    history: List[AgentMessage] = Field(default_factory=list)
    timezone: str = "Asia/Shanghai"
    start_time: Optional[str] = None
    end_time: Optional[str] = None
    max_tool_rounds: int = Field(5, ge=1, le=10)


class ToolTrace(BaseModel):
    tool_name: str
    arguments: dict[str, Any] = Field(default_factory=dict)
    result: Any = None
    error: Optional[str] = None


class ToolCallingAgentResponse(BaseModel):
    success: bool
    reply: str
    tool_traces: List[ToolTrace] = Field(default_factory=list)
    raw_messages: List[dict[str, Any]] = Field(default_factory=list)
