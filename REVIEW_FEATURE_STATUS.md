# AI 日程复盘功能情况说明

更新时间：2026-08-25

## 当前状态

AI 日程复盘、个性化智能体和 SQLite 长期记忆库已经完成开发，并已通过本机自动化测试和 Android 模拟器端到端测试。

正式 Android 客户端当前仍指向：

```text
https://u836809-92e6-37d8b4ba.bjb2.seetacloud.com:8443/
```

截至 2026-08-25，该地址尚未部署本次新增的复盘接口：

```text
GET  /openapi.json         -> 404
POST /api/reviews/generate -> 404
```

因此，代码和 APK 已可用，但要完成正式线上大模型闭环，还需要将 `Calendar-fastapi` 部署到该服务器或调整客户端服务地址。

## 已实现功能

### Android

- 主菜单新增“AI 日程复盘”入口
- 支持选择复盘日期并读取当天日历事项
- 支持填写心情、精力、满意度和自由复盘内容
- 支持设置智能体名称、性格和长期目标
- 展示结构化复盘、生成来源和长期记忆
- 网络或模型异常时显示明确错误或后端降级结果，不会崩溃

### 后端

提供以下接口：

```text
POST   /api/reviews/generate
GET    /api/reviews/profile/{user_id}
PUT    /api/reviews/profile/{user_id}
GET    /api/reviews/memories/{user_id}
DELETE /api/reviews/memories/{user_id}/{memory_id}
DELETE /api/reviews/memories/{user_id}
GET    /api/reviews/history/{user_id}
GET    /api/reviews/{review_id}
```

主要能力：

- 将智能体名称、性格、语气和目标加入大模型提示词
- 让大模型返回结构化复盘结果和长期记忆候选
- SQLite 持久化智能体资料、复盘历史和长期记忆
- 记忆去重、关键词检索、重要度和时效性排序
- 大模型不可用时自动使用安全的本地规则生成复盘

## 测试结果

### 后端自动化测试

```text
Ran 5 tests
OK
```

覆盖资料持久化、历史保存、记忆去重与清理、大模型正常返回、大模型失败降级及完整 HTTP 流程。

### Android 与端到端测试

已验证：

```text
Android 模拟器
-> 本机 FastAPI
-> 规则降级复盘
-> SQLite 持久化
```

端到端请求结果：

```text
POST /api/reviews/generate -> 200 OK
GET  /api/reviews/memories/{user_id} -> 200 OK
```

数据库中确认保存：

- 1 条智能体资料
- 1 条复盘历史
- 2 条长期记忆（长期目标、精力规律）

其他检查：

- Python 编译检查通过
- 9 个 Android XML 文件解析通过
- Android Debug APK 构建成功
- APK v1/v2 签名验证通过
- `git diff --check` 通过

## APK

仓库内位置：

```text
artifacts/ShineFlow-review-debug.apk
```

SHA-256：

```text
C0EB85B8EFF2C89ECF1FA56EBA7E8DC0C1AAD90195BA8B113CAC4E4D273851BA
```

## 部署时需要确认

1. 在服务器部署当前 `Calendar-fastapi` 代码。
2. 配置大模型地址、模型名称和密钥等环境变量。
3. 确保服务器可以写入 `REVIEW_MEMORY_DB_PATH` 指定的目录。
4. 验证 `/api/reviews/generate` 和 `/api/reviews/memories/{user_id}` 不再返回 404。
5. 如果服务器地址发生变化，更新 `app/build.gradle.kts` 中的 `SHINEFLOW_API_BASE_URL` 并重新构建 APK。
