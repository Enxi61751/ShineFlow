# ShineFlow 日程复盘与长期记忆 API

## 1. 功能概览

日程复盘模块把当天日程、实际完成情况、主观感受、个性化智能体设定和历史长期记忆组合后交给大模型，生成结构化复盘。生成完成后，系统会把适合跨天使用的信息写入 SQLite 记忆库，供之后的复盘检索。

复盘输出包括：

- 今日标题和总结
- 已完成事项与未完成事项
- 重复规律与可能原因
- 可执行建议
- 明日重点
- 个性化鼓励语
- 0–100 完成度分数
- 新增长期记忆候选

当大模型接口不可用、超时或返回格式不正确时，服务会自动切换到规则复盘，并继续保存复盘历史和可提取的基础记忆。

## 2. 配置

复制 `.env.example` 为 `.env`，至少选择一种模型运行方式。

### 远程 OpenAI 兼容接口（推荐）

```env
REMOTE_LLM_BASE_URL=https://your-llm-host.example.com
REMOTE_LLM_CHAT_PATH=/v1/chat/completions
REMOTE_LLM_API_KEY=your-api-key
REMOTE_LLM_MODEL=your-model-name
REMOTE_LLM_TIMEOUT_SECONDS=120
```

### 本地 GGUF 模型

当 `REMOTE_LLM_BASE_URL` 为空时，后端会使用 `llama-cpp-python` 加载本地模型：

```env
MODEL_PATH=./models/qwen_gguf/Qwen3.5-4B-Q4_K_M.gguf
N_CTX=4096
```

### 复盘记忆配置

```env
REVIEW_MEMORY_DB_PATH=./data/review_memory.db
REVIEW_MEMORY_RETRIEVAL_LIMIT=8
REVIEW_LLM_MAX_TOKENS=2200
```

数据库目录会自动创建。默认数据库文件为：

```text
Calendar-fastapi/data/review_memory.db
```

## 3. 启动服务

```powershell
cd Calendar-fastapi
python -m pip install -r requirements.txt
python run.py
```

健康检查：

```text
GET /health
```

## 4. 生成日程复盘

```text
POST /api/reviews/generate
Content-Type: application/json
```

请求示例：

```json
{
  "user_id": "f1e72932-45a8-4b99-8483-4d7900e2e056",
  "review_date": "2026-08-24",
  "timezone": "Asia/Shanghai",
  "reflection": "上午很专注，下午被消息打断了几次。",
  "mood": "平静",
  "energy_level": 4,
  "satisfaction_score": 4,
  "events": [
    {
      "event_id": "101",
      "title": "完成日程复盘功能",
      "start_time": "2026-08-24T09:00:00+08:00",
      "end_time": "2026-08-24T11:00:00+08:00",
      "description": "",
      "location": "",
      "all_day": false,
      "completion_status": "completed",
      "completion_note": ""
    },
    {
      "event_id": "102",
      "title": "整理部署文档",
      "start_time": "2026-08-24T15:00:00+08:00",
      "end_time": "2026-08-24T16:00:00+08:00",
      "description": "",
      "location": "",
      "all_day": false,
      "completion_status": "not_completed",
      "completion_note": "被临时消息打断"
    }
  ],
  "agent_profile": {
    "assistant_name": "小光",
    "personality": "关注长期规律的成长伙伴",
    "tone": "温暖、具体、不说教",
    "goals": ["持续交付产品功能", "保持稳定作息"]
  }
}
```

`completion_status` 支持：

- `completed`：已完成
- `partial`：部分完成
- `not_completed`：未完成
- `cancelled`：取消
- `unknown`：未确认；模型不得将其描述为已完成或未完成

响应示例：

```json
{
  "success": true,
  "review_id": "d29620d7-68d8-4ed4-aa99-6b1131c8c910",
  "generated_by": "llm",
  "model": "your-model-name",
  "result": {
    "headline": "有推进，也要保护下午的注意力",
    "summary": "核心开发任务已完成。下午的消息打断值得继续观察。",
    "achievements": ["完成日程复盘功能"],
    "unfinished_items": ["部署文档仍需补齐"],
    "patterns": ["下午可能更容易受到即时消息影响"],
    "suggestions": ["明天下午设置一个关闭通知的 25 分钟专注段"],
    "tomorrow_focus": ["补齐部署文档"],
    "encouragement": "小光会陪你把有效节奏慢慢稳定下来。",
    "completion_score": 78
  },
  "used_memories": [],
  "saved_memories": [],
  "created_at": "2026-08-24T09:30:00+00:00"
}
```

`generated_by`：

- `llm`：大模型成功生成
- `rule_fallback`：大模型失败后使用规则降级

## 5. 智能体个性资料

获取资料：

```text
GET /api/reviews/profile/{user_id}
```

更新资料：

```text
PUT /api/reviews/profile/{user_id}
Content-Type: application/json
```

```json
{
  "assistant_name": "小光",
  "personality": "理性、结构清晰的行动教练",
  "tone": "直接、具体，但不羞辱用户",
  "goals": ["完成毕业设计", "稳定早睡"]
}
```

生成复盘时传入的 `agent_profile` 也会自动更新服务端资料。

## 6. 长期记忆库

获取记忆：

```text
GET /api/reviews/memories/{user_id}?limit=50
```

删除单条记忆：

```text
DELETE /api/reviews/memories/{user_id}/{memory_id}
```

清空用户的全部有效记忆：

```text
DELETE /api/reviews/memories/{user_id}
```

清空记忆不会删除历史复盘。

当前支持的记忆类型：

- `preference`：偏好
- `habit`：习惯
- `challenge`：持续挑战或阻碍
- `effective_strategy`：已验证的有效策略
- `goal`：长期目标
- `energy_pattern`：精力规律

系统会对记忆内容进行指纹去重。检索时综合关键词相关性、重要度和更新时间进行排序。

## 7. 复盘历史

获取用户历史复盘：

```text
GET /api/reviews/history/{user_id}?limit=20
```

获取单条复盘：

```text
GET /api/reviews/{review_id}
```

## 8. Android 接入

Android 端服务地址由 `app/build.gradle.kts` 中的 `SHINEFLOW_API_BASE_URL` 控制。部署本次后端代码后，需要确认它指向包含 `/api/reviews/*` 接口的服务器，然后重新构建应用。

目前 Android 使用本地生成的 UUID 作为 `user_id`。卸载应用或清除应用数据后会生成新的 UUID，服务端会把它视为新用户。

## 9. 数据与隐私注意事项

当前实现适合原型和受控测试环境：

- 服务端尚未加入登录认证，不能仅依赖客户端传入的 `user_id` 作为生产环境身份凭证。
- SQLite 中会保存日程请求、复盘结果、智能体资料和长期记忆；部署时应限制数据库文件访问权限并做好备份。
- 公开服务前应增加鉴权、用户隔离、传输加密、数据导出/删除能力和敏感内容过滤。
- 大模型提示词要求不保存密码、联系方式等敏感内容，但生产环境仍应在写入数据库前增加独立的敏感信息检测。
