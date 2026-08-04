from __future__ import annotations

import inspect
import json
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Dict, List, get_args, get_origin


ToolHandler = Callable[..., Any]


@dataclass(frozen=True)
class RegisteredTool:
    name: str
    description: str
    parameters: dict[str, Any]
    handler: ToolHandler

    def as_openai_tool(self) -> dict[str, Any]:
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": self.parameters,
            },
        }


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: Dict[str, RegisteredTool] = {}

    def register(
        self,
        *,
        name: str,
        description: str,
        parameters: dict[str, Any],
        handler: ToolHandler,
    ) -> None:
        if name in self._tools:
            raise ValueError(f"Tool already registered: {name}")
        self._tools[name] = RegisteredTool(name, description, parameters, handler)

    def openai_tools(self) -> List[dict[str, Any]]:
        return [tool.as_openai_tool() for tool in self._tools.values()]

    def names(self) -> List[str]:
        return list(self._tools.keys())

    async def execute(self, name: str, arguments: dict[str, Any]) -> Any:
        tool = self._tools.get(name)
        if tool is None:
            raise ValueError(f"Unknown tool: {name}")

        safe_arguments = _filter_arguments(tool.handler, arguments)
        result = tool.handler(**safe_arguments)
        if inspect.isawaitable(result):
            result = await result
        return _jsonable(result)


def parse_tool_arguments(raw_arguments: Any) -> dict[str, Any]:
    if raw_arguments is None or raw_arguments == "":
        return {}
    if isinstance(raw_arguments, dict):
        return raw_arguments
    if isinstance(raw_arguments, str):
        try:
            payload = json.loads(raw_arguments)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Tool arguments are not valid JSON: {exc.msg}") from exc
        if not isinstance(payload, dict):
            raise ValueError("Tool arguments must be a JSON object.")
        return payload
    raise ValueError("Tool arguments must be a JSON object or JSON string.")


def _filter_arguments(handler: ToolHandler, arguments: dict[str, Any]) -> dict[str, Any]:
    signature = inspect.signature(handler)
    if any(param.kind == inspect.Parameter.VAR_KEYWORD for param in signature.parameters.values()):
        return arguments
    return {key: value for key, value in arguments.items() if key in signature.parameters}


def _jsonable(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump()
    if isinstance(value, list):
        return [_jsonable(item) for item in value]
    if isinstance(value, tuple):
        return [_jsonable(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _jsonable(item) for key, item in value.items()}
    return value


def object_schema(properties: dict[str, Any], required: List[str] | None = None) -> dict[str, Any]:
    return {
        "type": "object",
        "properties": properties,
        "required": required or [],
        "additionalProperties": False,
    }


def array_of(item_schema: dict[str, Any]) -> dict[str, Any]:
    return {"type": "array", "items": item_schema}
