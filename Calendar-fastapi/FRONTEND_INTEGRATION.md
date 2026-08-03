# 前后端联调说明

## 快速上手

1. 在 [`Calendar-fastapi`](c:\Users\31957\Desktop\calender\Calendar-fastapi) 目录运行 `.\start.ps1`
2. 访问 `http://127.0.0.1:8000/health`
3. 如果返回 `{"status":"ok"}`，说明后端已启动
4. 纯文本聊天调用 `POST /api/chat`
5. 文本加附件上传调用 `POST /api/chat/upload`

## 服务地址

- 本地地址：`http://127.0.0.1:8000`
- Android 模拟器访问宿主机：`http://10.0.2.2:8000`
- 启动命令：

```powershell
.\start.ps1
```

## 当前可用接口

### `GET /`

快速确认服务已启动。

返回示例：

```json
{
  "message": "Qwen GGUF backend is running"
}
```

### `GET /health`

健康检查接口。

返回示例：

```json
{
  "status": "ok"
}
```

### `POST /api/chat`

纯 JSON 聊天接口，适合只发文本。

请求体示例：

```json
{
  "message": "你好，请介绍一下你自己",
  "history": [
    {
      "role": "user",
      "content": "上一句用户消息"
    },
    {
      "role": "assistant",
      "content": "上一句模型回复"
    }
  ],
  "system_prompt": "你是一个友好的中文助手，请简洁、清楚地回答。"
}
```

成功返回示例：

```json
{
  "success": true,
  "reply": "你好，我是一个本地运行的中文助手，可以帮助你回答问题。",
  "model": "Qwen3.5-4B.Q4_K_M.gguf",
  "attachments": []
}
```

### `POST /api/chat/upload`

`multipart/form-data` 聊天接口，适合发文本、图片、音频和其他文件。

支持字段：

- `message`: 文本消息，可为空
- `system_prompt`: 可选
- `history`: JSON 字符串，可选
- `images`: 可多文件上传
- `audios`: 可多文件上传
- `files`: 可多文件上传

如果 `message` 为空但上传了附件，后端会自动补一条默认说明，不会报错。

返回仍然是统一 JSON，并会附带后端识别到的附件元数据。

成功返回示例：

```json
{
  "success": true,
  "reply": "我已经收到你上传的图片和音频文件。你可以继续说明你想让我做什么。",
  "model": "Qwen3.5-4B.Q4_K_M.gguf",
  "attachments": [
    {
      "field_name": "images",
      "filename": "photo.jpg",
      "content_type": "image/jpeg",
      "size_bytes": 182031,
      "category": "image",
      "text_excerpt": null
    },
    {
      "field_name": "audios",
      "filename": "voice.m4a",
      "content_type": "audio/mp4",
      "size_bytes": 912004,
      "category": "audio",
      "text_excerpt": null
    }
  ]
}
```

失败返回示例：

```json
{
  "detail": "模型调用失败: ..."
}
```

## TypeScript 类型

```ts
export type ChatRole = "user" | "assistant" | "system";

export interface ChatMessage {
  role: ChatRole;
  content: string;
}

export interface AttachmentInfo {
  field_name: string;
  filename: string;
  content_type: string;
  size_bytes: number;
  category: "image" | "audio" | "text" | "file";
  text_excerpt?: string | null;
}

export interface ChatRequest {
  message: string;
  history?: ChatMessage[];
  system_prompt?: string;
}

export interface ChatResponse {
  success: boolean;
  reply: string;
  model: string;
  attachments: AttachmentInfo[];
}

export interface ApiError {
  detail?: string;
}
```

## 前端 JSON 请求示例

```ts
const BASE_URL = "http://127.0.0.1:8000";

export async function chatWithModel(payload: {
  message: string;
  history?: { role: "user" | "assistant" | "system"; content: string }[];
  system_prompt?: string;
}) {
  const response = await fetch(`${BASE_URL}/api/chat`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      message: payload.message,
      history: payload.history ?? [],
      system_prompt:
        payload.system_prompt ??
        "你是一个友好的中文助手，请简洁、清楚地回答。"
    })
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.detail || `HTTP ${response.status}`);
  }
  return data as ChatResponse;
}
```

## 前端 multipart 上传示例

```ts
const BASE_URL = "http://127.0.0.1:8000";

export async function chatWithUpload(payload: {
  message?: string;
  system_prompt?: string;
  history?: { role: "user" | "assistant" | "system"; content: string }[];
  images?: File[];
  audios?: File[];
  files?: File[];
}) {
  const formData = new FormData();

  if (payload.message) formData.append("message", payload.message);
  if (payload.system_prompt) formData.append("system_prompt", payload.system_prompt);
  if (payload.history) formData.append("history", JSON.stringify(payload.history));

  for (const image of payload.images ?? []) formData.append("images", image);
  for (const audio of payload.audios ?? []) formData.append("audios", audio);
  for (const file of payload.files ?? []) formData.append("files", file);

  const response = await fetch(`${BASE_URL}/api/chat/upload`, {
    method: "POST",
    body: formData
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.detail || `HTTP ${response.status}`);
  }
  return data as ChatResponse;
}
```

## Curl 调试示例

### 纯文本 JSON

```bash
curl -X POST "http://127.0.0.1:8000/api/chat" ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"你好\",\"history\":[],\"system_prompt\":\"你是一个友好的中文助手，请简洁、清楚地回答。\"}"
```

### multipart 上传文件

```bash
curl -X POST "http://127.0.0.1:8000/api/chat/upload" ^
  -F "message=请看看我上传的内容" ^
  -F "history=[]" ^
  -F "images=@C:/temp/photo.jpg" ^
  -F "audios=@C:/temp/voice.m4a"
```

## 联调注意事项

- 纯文本请求继续用 JSON 接口
- 只要涉及图片、音频、文本文件上传，就用 `multipart/form-data`
- `history` 在上传接口里必须是 JSON 字符串
- `role` 只允许 `user`、`assistant`、`system`
- 当前后端能可靠接收和返回附件元数据
- 文本类文件会被提取一小段文本摘要
- 图片和音频目前只做接收与元数据透传，不做真正视觉识别或语音转写
- 当前接口不是流式输出，前端应按普通请求处理
- 模型推理可能需要几秒到几十秒，前端必须有 loading 状态
- 当前 CORS 为全开放，方便开发联调

## 建议前端约束

- 不要发送空 `message` 和空附件
- `history` 只保留最近几轮，例如 6 到 20 轮
- 单个文件建议小于 15MB
- 增加请求超时处理
- 请求失败时显示兜底文案
- 不要假设 `reply` 一定是固定格式，应按普通文本展示

## 联调排查清单

1. 先运行 `.\start.ps1`
2. 打开 `http://127.0.0.1:8000/health`
3. 纯文本联调用 `POST /api/chat`
4. 文件上传联调用 `POST /api/chat/upload`
5. 重点检查：
   - 状态码是否为 200
   - 返回 JSON 中的 `reply`
   - 返回 JSON 中的 `attachments`
   - 报错时的 `detail`



### 日志简述

- 后端新增 `POST /api/chat/upload`，支持 `multipart/form-data` 传输文本、图片、语音和普通文件。
- 后端补充附件解析、文件大小校验、文本摘要提取、附件元数据返回。
- 后端优化模型回复清洗，去除 `<think>`、自检项、模板垃圾文本和异常 JSON 包装。
- Android 前端新增文件、图片、语音选择能力。
- Android 前端实现“纯文本走 JSON 接口，带附件走 upload 接口”的请求分流。
- 完成后端接口测试、Android 编译、APK 安装和模拟器端真实联调验证。
