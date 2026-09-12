from fastapi import APIRouter, HTTPException, Query

from app.schemas.review import (
    AgentProfileInput,
    AgentProfileResponse,
    DeleteResponse,
    MemoryListResponse,
    ReviewGenerateRequest,
    ReviewGenerateResponse,
    ReviewHistoryItem,
    ReviewHistoryResponse,
)
from app.services.schedule_review_service import schedule_review_service


router = APIRouter(prefix="/api/reviews", tags=["schedule-reviews"])


@router.post("/generate", response_model=ReviewGenerateResponse)
async def generate_review(request: ReviewGenerateRequest) -> ReviewGenerateResponse:
    try:
        return await schedule_review_service.generate(request)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Schedule review failed: {exc}") from exc


@router.get("/profile/{user_id}", response_model=AgentProfileResponse)
def get_review_profile(user_id: str) -> AgentProfileResponse:
    return schedule_review_service.get_profile(user_id)


@router.put("/profile/{user_id}", response_model=AgentProfileResponse)
def update_review_profile(user_id: str, profile: AgentProfileInput) -> AgentProfileResponse:
    return schedule_review_service.save_profile(user_id, profile)


@router.get("/memories/{user_id}", response_model=MemoryListResponse)
def list_review_memories(
    user_id: str,
    limit: int = Query(50, ge=1, le=200),
) -> MemoryListResponse:
    items = schedule_review_service.repository.list_memories(user_id, limit=limit)
    return MemoryListResponse(items=items)


@router.delete("/memories/{user_id}/{memory_id}", response_model=DeleteResponse)
def delete_review_memory(user_id: str, memory_id: str) -> DeleteResponse:
    deleted = schedule_review_service.repository.delete_memory(user_id, memory_id)
    return DeleteResponse(deleted=deleted)


@router.delete("/memories/{user_id}", response_model=DeleteResponse)
def clear_review_memories(user_id: str) -> DeleteResponse:
    deleted = schedule_review_service.repository.clear_memories(user_id)
    return DeleteResponse(deleted=deleted)


@router.get("/history/{user_id}", response_model=ReviewHistoryResponse)
def list_review_history(
    user_id: str,
    limit: int = Query(20, ge=1, le=100),
) -> ReviewHistoryResponse:
    items = schedule_review_service.repository.list_reviews(user_id, limit=limit)
    return ReviewHistoryResponse(items=items)


@router.get("/{review_id}", response_model=ReviewHistoryItem)
def get_review(review_id: str) -> ReviewHistoryItem:
    item = schedule_review_service.repository.get_review(review_id)
    if item is None:
        raise HTTPException(status_code=404, detail="Review not found.")
    return item
