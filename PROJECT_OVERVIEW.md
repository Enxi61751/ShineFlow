# ShineFlow 项目整理

整理时间：2026-07-28

## 1. 项目定位

`ShineFlow` 是一个基于开源 Etar Calendar 改造的 Android 日历应用。原始 Etar 提供月/周/日/议程视图、提醒、Widget、ICS 导入导出、多语言等日历基础能力；本项目在此基础上加入了智能导入、AI 日程解析、图片/音频输入、时间冲突检测、时间推荐以及部分 ML 实验模块。

当前主项目目录：`C:\Users\31957\Desktop\Calendar\ShineFlow`

根目录下还存在一个备份/旧版本目录：`C:\Users\31957\Desktop\Calendar\Calendar_back\Etar-Calendar-master`。

## 2. 总体结构

```text
ShineFlow/
├─ app/                         Android 客户端主工程
│  ├─ src/main/java/com/android/calendar/
│  │  ├─ agenda/                议程视图
│  │  ├─ alerts/                提醒、闹钟、通知
│  │  ├─ event/                 日程创建/编辑/详情，智能导入主入口
│  │  ├─ month/                 月视图
│  │  ├─ settings/              设置页
│  │  ├─ widget/                桌面日历组件
│  │  ├─ smart/                 中文规则解析器
│  │  ├─ ocr/                   OCR 上传/识别封装
│  │  ├─ data/remote/           语音转写接口封装
│  │  ├─ ml/                    STN/GNN/Attention 实验模块
│  │  └─ ui/                    ML 调试页面
│  ├─ src/main/res/             布局、字符串、图标、多语言资源
│  └─ src/main/assets/          时区数据与 TFLite 模型
├─ Calendar-fastapi/            FastAPI 后端，负责聊天与日程补全接口
├─ server_gnn/                  FastAPI GNN 推荐服务 Demo
├─ tools_ml/                    STN TFLite 模型导出脚本
├─ metadata/                    Etar/F-Droid/Google Play 元数据与宣传图
├─ gradle/                      Gradle Wrapper 与版本依赖配置
└─ README.md                    原 Etar 项目说明
```

## 3. Android 客户端

### 3.1 技术栈

- Android Gradle Plugin：8.5.2
- Kotlin：2.3.0
- Java/Kotlin 目标版本：21
- compileSdk：36
- minSdk：23
- targetSdk：35
- applicationId：`com.shineflow.app`
- namespace：`ws.xsoh.etar`
- 主要依赖：AndroidX、Material、Retrofit、OkHttp、Gson Converter、Kotlin Coroutines、TensorFlow Lite

### 3.2 核心页面与功能

入口 Activity：`com.android.calendar.AllInOneActivity`

主要功能：

- 日历主视图：月视图、周视图、日视图、议程视图
- 日程编辑与详情
- 搜索
- ICS 导入
- 日历提醒/通知
- 桌面 Widget
- 设置页
- 智能日程导入：文本、图片、音频、录音
- 智能补全/冲突检测/推荐时间段

### 3.3 智能导入流程

用户入口在日程编辑页：`app/src/main/java/com/android/calendar/event/EditEventFragment.java`

用户点击 `Smart import` 后可选择：

1. 粘贴文本
2. 上传图片
3. 上传音频
4. 录音输入
5. 提交到 AI

当前客户端流程：

```text
用户输入文字/图片/音频
  ↓
保存到 App 内部目录 smart_input_data
  ↓
通过 OkHttp multipart 请求后端
  ↓
解析后端返回 JSON
  ↓
提取：事件、地点、时间、人物
  ↓
自动填入日程编辑界面
  ↓
检查是否缺字段/是否冲突
  ↓
给出补全弹窗或时间推荐
```

客户端期望后端返回的数据里包含：

```json
{
  "success": true,
  "data": {
    "日程列表": [
      {
        "事件": "...",
        "地点": "...",
        "时间": "2026-07-28 15:00:00",
        "人物": ["..."]
      }
    ]
  }
}
```

### 3.4 智能冲突检测与推荐

相关文件：

- `app/src/main/java/com/android/calendar/event/SmartScheduleAdvisor.java`
- `app/src/test/java/com/android/calendar/event/SmartScheduleAdvisorTest.java`

能力：

- 查询未来 14 天已有日程
- 检测新日程与已有日程是否重叠
- 读取最近 45 天历史日程，统计用户偏好的时间段
- 在 8:00-21:00 间按 30 分钟粒度推荐可用时间
- 对冲突/缺字段场景弹窗提示用户修正

### 3.5 本地中文规则解析器

相关文件：

- `app/src/main/java/com/android/calendar/smart/PreciseRuleExtractor.kt`
- `app/src/main/java/com/android/calendar/smart/ExtractResult.kt`

用途：从中文非结构化文本里抽取时间、地点、事件。

策略：

- 显式锚点：如“时间：”“地点：”“主题：”
- 时间正则：完整日期、短日期、上午/下午、相对时间、截止时间等
- 地点词库与地点模式
- 事件关键词匹配

当前它更像一个本地备用/辅助解析模块，主智能导入流程目前主要走服务端 AI 解析。

## 4. 后端：Calendar-fastapi

目录：`Calendar-fastapi/`

这是一个 FastAPI 后端，用于调用本地 GGUF 模型或远程 OpenAI-compatible 模型。

### 4.1 接口

- `GET /`：健康提示
- `GET /health`：返回状态与模型名
- `POST /api/chat`：纯文本聊天
- `POST /api/chat/upload`：文本 + 附件聊天
- `POST /api/schedule/complete`：日程字段补全接口

### 4.2 配置

示例配置在：`Calendar-fastapi/.env.example`

支持两种模型方式：

1. 本地 GGUF 模型：`MODEL_PATH=./models/qwen_gguf/Qwen3.5-4B-Q4_K_M.gguf`
2. 远程模型服务：
   - `REMOTE_LLM_BASE_URL`
   - `REMOTE_LLM_CHAT_PATH`
   - `REMOTE_LLM_MODEL`
   - `REMOTE_LLM_API_KEY`
   - `REMOTE_LLM_TIMEOUT_SECONDS`

### 4.3 运行方式

```powershell
cd C:\Users\31957\Desktop\Calendar\ShineFlow\Calendar-fastapi
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
python run.py
```

默认服务一般运行在 `http://127.0.0.1:8000`。

如果手机/模拟器要访问电脑服务：

- Android 模拟器访问宿主机：`http://10.0.2.2:8000/`
- 真机访问电脑：使用电脑局域网 IP，如 `http://192.168.x.x:8000/`
- 当前项目配置里写的是远程 HTTPS 服务地址。

## 5. 后端：server_gnn

目录：`server_gnn/`

这是一个 GNN 推荐服务 Demo，用于根据最近日程出现的小时分布，推荐可能合适的新日程时间。

接口：

- `POST /gnn/suggest_time`

输入：

- userId
- recentEventTitles
- recentEventLocations
- recentEventHours
- topK

输出：

- suggestedHours
- confidences

运行方式：

```powershell
cd C:\Users\31957\Desktop\Calendar\ShineFlow\server_gnn
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8001
```

## 6. ML 工具与实验模块

### 6.1 STN/TFLite 图片纠偏

相关文件：

- `tools_ml/export_stn_tflite.py`
- `app/src/main/java/com/android/calendar/ml/stn/StnRectifier.kt`
- `app/src/main/java/com/android/calendar/ml/stn/TFLiteModel.kt`
- `app/src/main/java/com/android/calendar/ui/MlDebugActivity.kt`

用途：将图片缩放到 224x224，通过 TFLite 模型做简单纠偏，供 OCR 前处理使用。

### 6.2 GNN 时间推荐

相关文件：

- `app/src/main/java/com/android/calendar/ml/gnn/GnnApi.kt`
- `app/src/main/java/com/android/calendar/ml/gnn/GnnRepository.kt`
- `server_gnn/app.py`
- `server_gnn/gnn_model.py`

当前主要在 `MlDebugActivity` 中调试使用。

### 6.3 Cross Attention Demo

相关文件：

- `app/src/main/java/com/android/calendar/ml/attention/CrossModalAttention.kt`
- `app/src/main/java/com/android/calendar/ml/attention/CrossAttentionDemo.kt`
- `app/src/main/java/com/android/calendar/ml/attention/MatrixOps.kt`

用途：跨模态注意力实验代码，目前未看出直接接入主业务流程。

## 7. 当前发现的重点问题/风险

### 7.1 Gradle Wrapper 写死了本机路径

文件：`gradle/wrapper/gradle-wrapper.properties`

当前配置：

```properties
distributionUrl=file:///D:/Calendar/gradle/gradle-8.7-bin.zip
```

本机工作区没有这个路径，因此当前直接编译会失败。工作区里能找到一个 zip：

`C:\Users\31957\Desktop\Calendar\Calendar_back\Etar-Calendar-master\gradle-8.7-bin.zip`

建议二选一：

1. 改回官方地址：`https://services.gradle.org/distributions/gradle-8.7-bin.zip`
2. 或改成当前机器真实存在的本地 zip 路径

### 7.2 Android 智能导入接口与 FastAPI 接口不一致

Android 当前请求：

```text
{OCR_BASE_URL}/parse_schedule
```

FastAPI 当前实际接口：

```text
/api/schedule/complete
```

如果当前远程服务器确实提供 `/parse_schedule`，则没问题；如果要使用仓库里的 `Calendar-fastapi`，需要统一为：

```text
{BASE_URL}/api/schedule/complete
```

或在后端额外兼容 `/parse_schedule`。

### 7.3 STN 模型资源目录疑似拼写错误

实际文件：

```text
app/src/main/assets/modles/stn_rectify.tflite
```

代码默认加载：

```text
models/stn_rectify.tflite
```

`modles` 与 `models` 不一致，可能导致运行时找不到模型。

### 7.4 GNN 调试地址有明显拼接错误

文件：`app/src/main/java/com/android/calendar/ui/MlDebugActivity.kt`

当前：

```kotlin
val baseUrl = "http://https://u836809-92e6-37d8b4ba.bjb2.seetacloud.com:8443/"
```

`http://https://` 是错误 URL，应该保留一种协议，例如：

```kotlin
val baseUrl = "https://u836809-92e6-37d8b4ba.bjb2.seetacloud.com:8443/"
```

### 7.5 Retrofit/OkHttp 依赖有重复和版本不统一

`gradle/libs.versions.toml` 中声明了 Retrofit 3.0.0、OkHttp 5.3.2；但 `app/build.gradle.kts` 又手写了 Retrofit 2.11.0、OkHttp 4.12.0，并且 Retrofit/Gson/Logging 出现重复声明。

建议统一到一种写法，避免后续依赖冲突或排查困难。

### 7.6 `local.properties` 不建议提交

`local.properties` 一般保存本机 Android SDK 路径，建议保留在本地，不纳入仓库共享。

### 7.7 部分注释或文档编码需要统一

终端查看时，部分中文注释/文档出现乱码。建议统一保存为 UTF-8，尤其是包含中文 JSON 键名和中文说明的文件。

## 8. 建议优先级

### P0：先保证能构建

1. 修复 Gradle Wrapper 路径
2. 执行 Android 编译检查
3. 如果有编译错误，再逐个修复

### P1：打通智能导入主链路

1. 统一 Android 请求路径与后端接口路径
2. 确认 `OCR_BASE_URL` 指向有效服务
3. 用文本输入测试一次自动填表
4. 再测试图片、音频、录音

### P2：清理 ML 实验模块

1. 修复 STN 模型目录名
2. 修复 GNN 调试 URL
3. 判断 STN/GNN/Attention 是否要正式接入主流程；不接入则标为实验模块

### P3：工程整理

1. 统一 Retrofit/OkHttp 依赖版本
2. 清理重复依赖
3. 补充项目 README：启动 Android、启动后端、接口说明、常见问题
4. 排除本机配置文件和临时产物

## 9. 推荐后续 README 结构

```text
# ShineFlow

## 项目简介
## 功能特性
## 技术栈
## 目录结构
## Android 端运行方式
## 后端运行方式
## 智能导入接口约定
## 常见问题
## 开发计划
```

## 10. 一句话总结

这是一个“Etar 日历 + AI 智能日程导入 + 时间冲突检测/推荐 + ML 实验模块”的 Android 项目。当前业务方向清晰，但需要优先修复构建工具路径、前后端接口路径、模型资源路径和调试 URL，才能让项目更稳定地运行与交接。
