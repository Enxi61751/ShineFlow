from typing import List, Optional

from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    role: str = Field(..., description="user/assistant/system")
    content: str = Field(..., description="message content")


class AttachmentInfo(BaseModel):
    field_name: str
    filename: str
    content_type: str
    size_bytes: int
    category: str
    text_excerpt: Optional[str] = None


class ChatRequest(BaseModel):
    message: str
    history: Optional[List[ChatMessage]] = Field(default_factory=list)
    system_prompt: Optional[str] = "You are a concise Chinese assistant."


class ChatResponse(BaseModel):
    success: bool
    reply: str
    model: str
    attachments: List[AttachmentInfo] = Field(default_factory=list)


class ScheduleCompletionItem(BaseModel):
    title: str = ""
    location: str = ""
    start_time: Optional[str] = None
    end_time: Optional[str] = None
    participants: List[str] = Field(default_factory=list)
    description: str = ""
    missing_fields: List[str] = Field(default_factory=list)
    confidence: Optional[float] = None


class ScheduleCompletionData(BaseModel):
    items: List[ScheduleCompletionItem] = Field(default_factory=list)
    follow_up_question: str = ""
    notes: str = ""


class ScheduleCompletionResponse(BaseModel):
    success: bool
    model: str
    data: ScheduleCompletionData
    raw_reply: str = ""
    attachments: List[AttachmentInfo] = Field(default_factory=list)
