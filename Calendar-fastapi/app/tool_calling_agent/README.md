# ShineFlow Tool-calling Agent

This folder is an isolated upgrade layer for a real native tool-calling Agent. It does not replace the existing `/api/chat` flow directly.

## Files

- `agent.py`: Agent loop. It sends messages plus tool schemas to the model, executes returned tool calls, appends tool results, and asks the model for the final user-facing answer.
- `llm_client.py`: OpenAI-compatible chat-completions client with native `tools` / `tool_choice` support.
- `tool_registry.py`: Tool registry and executor. It converts Python handlers into OpenAI-compatible function tools and executes selected handlers.
- `calendar_tools.py`: ShineFlow business tools for emotion detection, task extraction, calendar-event field completion, confirmation preparation, high-pressure task splitting, and adaptive schedule planning.
- `../services/emotion_llm_service.py`: LLM-backed structured emotion recognizer. Code handles prompt construction, JSON parsing, validation, safety fallback, and unavailable fallback.
- `../services/schedule_llm_service.py`: LLM-backed structured schedule planner. The model drafts the plan; code validates time range, overlap, duration, slot types, emotion-adaptation constraints, and falls back to the rule planner when needed.
- `models.py`: Request/response/trace models for the tool-calling Agent endpoint.

## Tool set

Registered tools:

1. `analyze_user_emotion`
   - Detects current emotion type, level, confidence, cues, and follow-up needs.
2. `extract_planning_tasks`
   - Extracts planning tasks from natural-language todos.
3. `complete_schedule_fields`
   - Long-term design version. The tool-calling model extracts event fields into `items`; code validates, normalizes, fills default `end_time`, and reports missing required fields. This tool does not call another model internally.
4. `prepare_calendar_event_confirmation`
   - Prepares safe confirmation data before any future calendar write action. It does not create or modify calendar data.
5. `split_high_pressure_task`
   - Splits overwhelming/high-difficulty/long tasks into smaller low-pressure segments.
6. `create_adaptive_schedule_plan`
   - Creates an emotion-adaptive time-blocked schedule. It now calls the LLM schedule planner first, then validates the result in backend code, and falls back to the deterministic rule planner if the LLM is unavailable or invalid.

## LLM-backed adaptive schedule flow

For daily planning requests, the intended chain is:

1. `analyze_user_emotion`: the LLM returns structured emotion insight (`emotion_type`, `level`, `confidence`, cues, follow-up need, risk level, and planning hint).
2. `extract_planning_tasks`: code extracts rough tasks when the model/user provides todos; the tool-calling model may also pass structured `tasks` directly.
3. `split_high_pressure_task`: optional helper when a task is long, hard, or overwhelming.
4. `create_adaptive_schedule_plan`: calls `schedule_llm_service` to ask the LLM for structured schedule JSON.
5. Backend validation checks: JSON object, non-empty plan, valid HH:MM/time values, inside requested time range, no overlap, matching duration, valid slot type, and required break/buffer for negative or strained emotions.
6. If validation passes, the backend rebuilds a stable warm Chinese reply. If validation fails or the model endpoint is not configured, the backend uses the existing deterministic rule planner as fallback.

This means the LLM is the planner, while Python remains the reviewer, guardrail, and fallback executor.

## Long-term calendar-event flow

For create/save calendar-event requests, the intended safe chain is:

1. `complete_schedule_fields`: the model extracts `title`, `location`, `start_time`, `end_time`, `participants`, and `description` into tool arguments; code validates and normalizes.
2. `prepare_calendar_event_confirmation`: code prepares confirmation text and tells whether required fields are complete.
3. Future `create_calendar_event`: only after explicit user confirmation, write to the Android/calendar storage layer.

The FastAPI backend currently has no real calendar write endpoint, so step 3 is intentionally not implemented in this folder yet. The assistant must not say an event has been created unless a real write tool exists and has succeeded.

## Endpoint

`POST /api/agent/tool-chat`

Example for adaptive planning:

```json
{
  "message": "I feel anxious today. Please plan: write report high difficulty 2 hours; organize notes 30 minutes.",
  "timezone": "Asia/Shanghai",
  "start_time": "09:00",
  "end_time": "18:00"
}
```

Example for calendar-event drafting:

```json
{
  "message": "Add a meeting with Wang tomorrow at 3pm in the library.",
  "timezone": "Asia/Shanghai"
}
```

## Runtime requirement

Native tool-calling requires a remote OpenAI-compatible model endpoint that supports `tools` / `tool_calls`:

```env
REMOTE_LLM_BASE_URL=https://your-openai-compatible-host
REMOTE_LLM_CHAT_PATH=/v1/chat/completions
REMOTE_LLM_API_KEY=your-key-if-needed
REMOTE_LLM_MODEL=your-tool-capable-model
```

The local llama-cpp path is not assumed to support native OpenAI tool-calling.
