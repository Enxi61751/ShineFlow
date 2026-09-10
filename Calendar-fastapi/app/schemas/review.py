from __future__ import annotations

from datetime import date, datetime
from typing import List, Optional

from pydantic import BaseModel, Field


class ReviewEventInput(BaseModel):
    event_id: Optional[str] = None
    title: str = Field(..., min_length=1, max_length=200)
    start_time: Optional[str] = None
    end_time: Optional[str] = None
    description: str = ""
    location: str = ""
    all_day: bool = False
    completion_status: str = Field(
        "unknown",
        description="completed/partial/not_completed/cancelled/unknown",
    )
    completion_note: str = ""


class AgentProfileInput(BaseModel):
    assistant_name: str = Field("Shine", min_length=1, max_length=40)
    personality: str = Field(
        "温柔、有行动力的成长伙伴",
        min_length=1,
        max_length=300,
    )
    tone: str = Field("温暖、具体、不说教", max_length=200)
    goals: List[str] = Field(default_factory=list, max_length=20)


class ReviewGenerateRequest(BaseModel):
    user_id: str = Field(..., min_length=1, max_length=120)
    review_date: date
    timezone: str = "Asia/Shanghai"
    reflection: str = Field("", max_length=6000)
    mood: str = Field("", max_length=100)
    energy_level: Optional[int] = Field(None, ge=1, le=5)
    satisfaction_score: Optional[int] = Field(None, ge=1, le=5)
    events: List[ReviewEventInput] = Field(default_factory=list, max_length=100)
    agent_profile: AgentProfileInput = Field(default_factory=AgentProfileInput)


class ReviewResult(BaseModel):
    headline: str = "今日复盘"
    summary: str = ""
    achievements: List[str] = Field(default_factory=list)
    unfinished_items: List[str] = Field(default_factory=list)
    patterns: List[str] = Field(default_factory=list)
    suggestions: List[str] = Field(default_factory=list)
    tomorrow_focus: List[str] = Field(default_factory=list)
    encouragement: str = ""
    completion_score: int = Field(0, ge=0, le=100)


class MemoryItem(BaseModel):
    id: str
    user_id: str
    kind: str
    content: str
    importance: int = Field(3, ge=1, le=5)
    source_review_id: Optional[str] = None
    created_at: datetime
    updated_at: datetime


class ReviewGenerateResponse(BaseModel):
    success: bool = True
    review_id: str
    generated_by: str
    model: str
    result: ReviewResult
    used_memories: List[MemoryItem] = Field(default_factory=list)
    saved_memories: List[MemoryItem] = Field(default_factory=list)
    created_at: datetime


class ReviewHistoryItem(BaseModel):
    review_id: str
    user_id: str
    review_date: date
    generated_by: str
    model: str
    result: ReviewResult
    created_at: datetime


class ReviewHistoryResponse(BaseModel):
    success: bool = True
    items: List[ReviewHistoryItem] = Field(default_factory=list)


class MemoryListResponse(BaseModel):
    success: bool = True
    items: List[MemoryItem] = Field(default_factory=list)


class AgentProfileResponse(BaseModel):
    success: bool = True
    user_id: str
    profile: AgentProfileInput
    updated_at: datetime


class DeleteResponse(BaseModel):
    success: bool = True
    deleted: int = 0
