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
    system_prompt: Optional[str] = "You are a warm Chinese assistant with emotion-aware schedule planning ability."


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


class EmotionInsight(BaseModel):
    emotion_type: str = Field(..., description="anxious/depressed/tired/low_motivation/positive_calm/excited_irritable/unclear")
    emotion_label: str = ""
    level: int = Field(1, ge=1, le=5, description="emotion intensity from 1 to 5")
    confidence: float = Field(0.0, ge=0.0, le=1.0)
    cues: List[str] = Field(default_factory=list)
    needs_follow_up: bool = False
    follow_up_question: str = ""
    support_note: str = ""


class PlanningTask(BaseModel):
    title: str
    difficulty: str = Field("medium", description="low/medium/high")
    duration_minutes: int = Field(45, ge=5, le=480)
    deadline: Optional[str] = None


class EmotionSchedulePlanRequest(BaseModel):
    message: str = ""
    tasks: List[PlanningTask] = Field(default_factory=list)
    timezone: str = "Asia/Shanghai"
    start_time: Optional[str] = None
    end_time: Optional[str] = None


class PlanSlot(BaseModel):
    start_time: str
    end_time: str
    title: str
    duration_minutes: int
    slot_type: str = Field("task", description="task/break/rest/planning/buffer")
    suggestion: str = ""


class EmotionSchedulePlanResponse(BaseModel):
    success: bool
    emotion: EmotionInsight
    plan: List[PlanSlot] = Field(default_factory=list)
    reply: str

