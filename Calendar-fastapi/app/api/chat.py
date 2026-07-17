from datetime import datetime
from typing import List, Optional
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, File, Form, HTTPException, UploadFile

from app.schemas.chat import (
    AttachmentInfo,
    ChatRequest,
    ChatResponse,
    ScheduleCompletionResponse,
)
from app.services.llm_service import llm_service
from app.utils.attachment_handler import collect_attachments, parse_history_payload
from app.utils.prompt_builder import build_prompt
from app.utils.schedule_prompt_builder import (
    build_schedule_completion_prompt,
    parse_schedule_completion_reply,
)

router = APIRouter(prefix="/api", tags=["chat"])


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest) -> ChatResponse:
    try:
        prompt = build_prompt(
            system_prompt=request.system_prompt,
            history=request.history or [],
            user_message=request.message,
        )

        reply = await llm_service.chat(prompt)

        return ChatResponse(
            success=True,
            reply=reply,
            model=llm_service.model_name,
            attachments=[],
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Model call failed: {exc}") from exc


@router.post("/chat/upload", response_model=ChatResponse)
async def chat_upload(
    message: str = Form(""),
    system_prompt: Optional[str] = Form(None),
    history: Optional[str] = Form(None),
    files: Optional[List[UploadFile]] = File(None),
    images: Optional[List[UploadFile]] = File(None),
    audios: Optional[List[UploadFile]] = File(None),
) -> ChatResponse:
    if not message.strip() and not files and not images and not audios:
        raise HTTPException(status_code=400, detail="message and attachments cannot both be empty.")

    try:
        history_items = parse_history_payload(history)
        attachments = await collect_attachments(files=files, images=images, audios=audios)

        user_message = message.strip() or "The user uploaded attachments. Respond based on the metadata."

        prompt = build_prompt(
            system_prompt=system_prompt,
            history=history_items,
            user_message=user_message,
            attachments=attachments,
        )

        reply = await llm_service.chat(prompt)
        if reply == "The model returned an empty reply. Please try again.":
            reply = build_attachment_fallback_reply(attachments)

        return ChatResponse(
            success=True,
            reply=reply,
            model=llm_service.model_name,
            attachments=attachments,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Model call failed: {exc}") from exc


@router.post("/schedule/complete", response_model=ScheduleCompletionResponse)
async def schedule_complete(
    text: str = Form(""),
    timezone: str = Form("Asia/Shanghai"),
    now_iso: Optional[str] = Form(None),
    duration_minutes: int = Form(60),
    image: Optional[UploadFile] = File(None),
    audio: Optional[UploadFile] = File(None),
    file: Optional[UploadFile] = File(None),
    images: Optional[List[UploadFile]] = File(None),
    audios: Optional[List[UploadFile]] = File(None),
    files: Optional[List[UploadFile]] = File(None),
) -> ScheduleCompletionResponse:
    if (
        not text.strip()
        and image is None
        and audio is None
        and file is None
        and not images
        and not audios
        and not files
    ):
        raise HTTPException(status_code=400, detail="At least one text or attachment input is required.")

    try:
        if not now_iso:
            try:
                now_iso = datetime.now(ZoneInfo(timezone)).isoformat()
            except ZoneInfoNotFoundError:
                now_iso = datetime.now().isoformat()

        attachment_files = list(files or [])
        attachment_images = list(images or [])
        attachment_audios = list(audios or [])

        if file is not None:
            attachment_files.append(file)
        if image is not None:
            attachment_images.append(image)
        if audio is not None:
            attachment_audios.append(audio)

        attachments = await collect_attachments(
            files=attachment_files,
            images=attachment_images,
            audios=attachment_audios,
        )

        prompt = build_schedule_completion_prompt(
            text=text,
            attachments=attachments,
            timezone=timezone,
            duration_minutes=max(duration_minutes, 1),
            now_iso=now_iso,
        )

        raw_reply = await llm_service.chat(
            prompt,
            clean=False,
            max_tokens=160,
            temperature=0.0,
            top_p=1.0,
        )
        parsed = parse_schedule_completion_reply(raw_reply)

        return ScheduleCompletionResponse(
            success=True,
            model=llm_service.model_name,
            data=parsed,
            raw_reply=raw_reply,
            attachments=attachments,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Schedule completion failed: {exc}") from exc


def build_attachment_fallback_reply(attachments: List[AttachmentInfo]) -> str:
    if not attachments:
        return "The message was received. Please try again."

    labels = []
    if any(item.category == "text" for item in attachments):
        labels.append("text")
    if any(item.category == "image" for item in attachments):
        labels.append("image")
    if any(item.category == "audio" for item in attachments):
        labels.append("audio")
    if any(item.category == "file" for item in attachments):
        labels.append("file")

    attachment_text = ", ".join(labels) if labels else "attachments"
    return f"Received {attachment_text}. Please continue with the next instruction."
