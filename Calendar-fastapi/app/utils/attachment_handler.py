import json
from typing import List, Optional

from fastapi import HTTPException, UploadFile

from app.schemas.chat import AttachmentInfo, ChatMessage


TEXT_PREVIEW_LIMIT = 500
MAX_FILE_SIZE_BYTES = 15 * 1024 * 1024


async def collect_attachments(
    files: Optional[List[UploadFile]] = None,
    images: Optional[List[UploadFile]] = None,
    audios: Optional[List[UploadFile]] = None,
) -> List[AttachmentInfo]:
    attachments: List[AttachmentInfo] = []

    for field_name, bucket in (
        ("files", files or []),
        ("images", images or []),
        ("audios", audios or []),
    ):
        for upload in bucket:
            data = await upload.read()
            size_bytes = len(data)
            if size_bytes > MAX_FILE_SIZE_BYTES:
                raise HTTPException(
                    status_code=413,
                    detail=f"File too large: {upload.filename} exceeds {MAX_FILE_SIZE_BYTES} bytes.",
                )

            content_type = upload.content_type or "application/octet-stream"
            category = classify_attachment(content_type, field_name)
            excerpt = None
            if is_text_like(content_type, upload.filename or ""):
                excerpt = extract_text_excerpt(data)

            attachments.append(
                AttachmentInfo(
                    field_name=field_name,
                    filename=upload.filename or "unnamed",
                    content_type=content_type,
                    size_bytes=size_bytes,
                    category=category,
                    text_excerpt=excerpt,
                )
            )

    return attachments


def parse_history_payload(history_raw: Optional[str]) -> List[ChatMessage]:
    if not history_raw or not history_raw.strip():
        return []

    try:
        payload = json.loads(history_raw)
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=400, detail=f"history is not valid JSON: {exc.msg}") from exc

    if not isinstance(payload, list):
        raise HTTPException(status_code=400, detail="history must be a JSON array.")

    try:
        return [ChatMessage.model_validate(item) for item in payload]
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"history format is invalid: {exc}") from exc


def classify_attachment(content_type: str, field_name: str) -> str:
    if content_type.startswith("image/") or field_name == "images":
        return "image"
    if content_type.startswith("audio/") or field_name == "audios":
        return "audio"
    if content_type.startswith("text/"):
        return "text"
    if content_type in {"application/json", "application/xml"}:
        return "text"
    return "file"


def is_text_like(content_type: str, filename: str) -> bool:
    if content_type.startswith("text/"):
        return True
    if content_type in {"application/json", "application/xml"}:
        return True
    lowered = filename.lower()
    return lowered.endswith((".txt", ".md", ".json", ".csv", ".log", ".xml"))


def extract_text_excerpt(data: bytes) -> str:
    text = data.decode("utf-8-sig", errors="ignore").strip()
    if len(text) > TEXT_PREVIEW_LIMIT:
        return text[:TEXT_PREVIEW_LIMIT].strip() + "..."
    return text
