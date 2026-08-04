from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone as dt_timezone, tzinfo
from typing import Iterable, List, Optional, Sequence
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.schemas.chat import (
    EmotionInsight,
    EmotionSchedulePlanResponse,
    PlanSlot,
    PlanningTask,
)


EMOTION_KEYWORDS = {
    "anxious": [
        "焦虑", "焦躁", "心慌", "慌", "紧张", "压力", "压得", "内耗", "担心", "怕", "不安",
        "来不及", "崩溃", "烦躁", "烦", "overwhelmed", "anxious", "stress", "stressed",
    ],
    "depressed": [
        "压抑", "低落", "难过", "沮丧", "心累", "想哭", "委屈", "空虚", "麻木", "撑不住",
        "没意义", "depressed", "sad", "down",
    ],
    "tired": [
        "疲惫", "累", "困", "乏", "没睡", "失眠", "睡不着", "头疼", "身心俱疲", "精疲力尽",
        "tired", "exhausted", "insomnia",
    ],
    "low_motivation": [
        "无动力", "没动力", "倦怠", "拖延", "不想动", "提不起劲", "摆烂", "懒得", "burnout",
        "procrastinate", "no motivation",
    ],
    "positive_calm": [
        "开心", "愉悦", "轻松", "放松", "平稳", "状态不错", "有精神", "充实", "期待", "顺利",
        "calm", "happy", "good mood", "relaxed",
    ],
    "excited_irritable": [
        "亢奋", "急躁", "坐不住", "停不下来", "脑子很快", "很急", "急着", "冲动", "兴奋过头",
        "restless", "hyper", "impulsive",
    ],
}

SEVERE_CUES = [
    "绝望", "撑不下去", "活不下去", "不想活", "自杀", "伤害自己", "轻生", "消失算了",
    "suicide", "kill myself", "self-harm", "hurt myself",
]

TASK_SPLIT_RE = re.compile(r"[\n；;。]+")
DURATION_RE = re.compile(
    r"(?P<num>\d+(?:\.\d+)?)\s*(?P<unit>小时|个小时|h|hr|hrs|分钟|分|min|mins|m)",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class MoodProfile:
    work_block_minutes: int
    break_minutes: int
    task_limit: int
    start_with_easy: bool
    reduce_load: bool
    advice: str


MOOD_PROFILES = {
    "anxious": MoodProfile(
        work_block_minutes=30,
        break_minutes=10,
        task_limit=4,
        start_with_easy=True,
        reduce_load=True,
        advice="把高难任务切成 25-30 分钟的小块；每完成一小块就喝水、伸展或短走 3-5 分钟。",
    ),
    "depressed": MoodProfile(
        work_block_minutes=25,
        break_minutes=12,
        task_limit=3,
        start_with_easy=True,
        reduce_load=True,
        advice="今天优先保留最低可交付版本，不追求完美；安排一点能让身体回温的轻活动。",
    ),
    "tired": MoodProfile(
        work_block_minutes=25,
        break_minutes=15,
        task_limit=3,
        start_with_easy=True,
        reduce_load=True,
        advice="先补水和放松眼睛，困难任务只做启动步骤；午后安排低强度事项，避免连续硬扛。",
    ),
    "low_motivation": MoodProfile(
        work_block_minutes=25,
        break_minutes=15,
        task_limit=4,
        start_with_easy=True,
        reduce_load=True,
        advice="把标准降到“先开始 10 分钟也算完成一半”，并给自己留出明确的休闲留白。",
    ),
    "positive_calm": MoodProfile(
        work_block_minutes=50,
        break_minutes=10,
        task_limit=6,
        start_with_easy=False,
        reduce_load=False,
        advice="状态平稳时适合做均衡排布：上午深度任务，下午沟通整理，傍晚运动和放松。",
    ),
    "excited_irritable": MoodProfile(
        work_block_minutes=35,
        break_minutes=15,
        task_limit=5,
        start_with_easy=False,
        reduce_load=False,
        advice="不要把日程排得太密；每个任务前后留 10-15 分钟静心缓冲，防止越排越浮躁。",
    ),
    "unclear": MoodProfile(
        work_block_minutes=40,
        break_minutes=10,
        task_limit=5,
        start_with_easy=True,
        reduce_load=False,
        advice="情绪线索还不多，先用温和均衡版本；如果你补充状态，我可以再调轻或调紧。",
    ),
}

EMOTION_LABELS = {
    "anxious": "焦虑/压力偏高",
    "depressed": "压抑/低落",
    "tired": "疲惫/精力不足",
    "low_motivation": "低动力/倦怠",
    "positive_calm": "积极平稳",
    "excited_irritable": "亢奋急躁",
    "unclear": "情绪线索不明确",
}


SCHEDULE_INTENT_KEYWORDS = [
    "安排", "规划", "计划", "日程", "待办", "任务", "今天", "明天", "上午", "下午", "晚上",
    "todo", "schedule", "plan", "agenda",
]


def looks_like_schedule_planning_request(text: str) -> bool:
    lowered = (text or "").lower()
    return any(keyword in lowered for keyword in SCHEDULE_INTENT_KEYWORDS)


def analyze_emotion(text: str, history: Optional[Sequence[str]] = None) -> EmotionInsight:
    combined_parts = list(history or [])[-4:] + [text or ""]
    combined = "\n".join(part for part in combined_parts if part).lower()

    scores: dict[str, int] = {}
    cues: List[str] = []
    for emotion, keywords in EMOTION_KEYWORDS.items():
        score = 0
        for keyword in keywords:
            if keyword.lower() in combined:
                score += 1
                if keyword not in cues:
                    cues.append(keyword)
        if score:
            scores[emotion] = score

    if any(cue.lower() in combined for cue in SEVERE_CUES):
        severe_hits = [cue for cue in SEVERE_CUES if cue.lower() in combined]
        return EmotionInsight(
            emotion_type="depressed",
            emotion_label=EMOTION_LABELS["depressed"],
            level=5,
            confidence=0.95,
            cues=severe_hits[:6],
            needs_follow_up=False,
            follow_up_question="",
            support_note="如果你此刻有伤害自己的冲动，请优先联系身边可信的人或当地紧急援助；我也可以先陪你把接下来 10 分钟安排得更安全。",
        )

    if not scores:
        return EmotionInsight(
            emotion_type="unclear",
            emotion_label=EMOTION_LABELS["unclear"],
            level=2,
            confidence=0.35,
            cues=[],
            needs_follow_up=True,
            follow_up_question="我还不太确定你现在是偏累、偏焦虑，还是只是想高效安排一下？你可以用 1-10 分给今天的压力和精力各打个分。",
            support_note="",
        )

    # Negative/strained states are prioritised over positive words so hidden distress is not ignored.
    priority = ["anxious", "depressed", "tired", "low_motivation", "excited_irritable", "positive_calm"]
    emotion_type = max(priority, key=lambda key: (scores.get(key, 0), -priority.index(key)))
    top_score = scores.get(emotion_type, 0)

    negative_score = sum(scores.get(key, 0) for key in ["anxious", "depressed", "tired", "low_motivation"])
    level = min(5, max(1, 1 + top_score + (1 if negative_score >= 2 else 0)))
    confidence = min(0.92, 0.45 + top_score * 0.16 + (0.12 if negative_score >= 2 else 0.0))

    needs_follow_up = confidence < 0.55 or (emotion_type == "unclear")
    follow_up = ""
    if needs_follow_up:
        follow_up = "我想更贴近你的状态：你现在更像是身体累、心里焦虑，还是单纯没动力？"

    return EmotionInsight(
        emotion_type=emotion_type,
        emotion_label=EMOTION_LABELS.get(emotion_type, emotion_type),
        level=level,
        confidence=round(confidence, 2),
        cues=cues[:8],
        needs_follow_up=needs_follow_up,
        follow_up_question=follow_up,
        support_note="",
    )


def extract_tasks_from_text(text: str) -> List[PlanningTask]:
    tasks: List[PlanningTask] = []
    for raw_part in TASK_SPLIT_RE.split(text or ""):
        part = raw_part.strip(" ，,：:、-·\t")
        if not part:
            continue
        if not _looks_like_task_fragment(part):
            continue

        duration = _extract_duration_minutes(part) or 45
        difficulty = _extract_difficulty(part)
        title = _clean_task_title(part)
        if not title or len(title) > 80:
            continue
        tasks.append(PlanningTask(title=title, difficulty=difficulty, duration_minutes=duration))
        if len(tasks) >= 8:
            break
    return tasks


def build_emotion_context(text: str, history: Optional[Sequence[str]] = None) -> str:
    insight = analyze_emotion(text, history)
    cues = "、".join(insight.cues) if insight.cues else "暂无明确线索"
    follow = insight.follow_up_question or "无需追问即可先给出可调整方案"
    return (
        "Emotion snapshot for the latest request:\n"
        f"- type: {insight.emotion_type} ({insight.emotion_label})\n"
        f"- level: {insight.level}/5, confidence: {insight.confidence}\n"
        f"- cues: {cues}\n"
        f"- follow-up if needed: {follow}\n"
        f"- support note: {insight.support_note or 'none'}"
    )


def create_emotion_schedule_plan(
    message: str,
    tasks: Optional[Iterable[PlanningTask]] = None,
    timezone: str = "Asia/Shanghai",
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
    emotion: Optional[EmotionInsight] = None,
) -> EmotionSchedulePlanResponse:
    insight = emotion or analyze_emotion(message)
    task_list = [task for task in (tasks or []) if task.title.strip()]
    if not task_list:
        task_list = extract_tasks_from_text(message)

    profile = MOOD_PROFILES.get(insight.emotion_type, MOOD_PROFILES["unclear"])
    task_list = _adapt_task_order(task_list, profile)[: profile.task_limit]

    day_start = _parse_or_default_time(start_time, timezone, default_hour=8, default_minute=30)
    day_end = _parse_or_default_time(end_time, timezone, default_hour=21, default_minute=30)
    if day_end <= day_start:
        day_end = day_start + timedelta(hours=8)

    slots = _build_slots(task_list, profile, day_start, day_end, insight)
    reply = _format_plan_reply(insight, slots, profile, bool(task_list))

    return EmotionSchedulePlanResponse(
        success=True,
        emotion=insight,
        plan=slots,
        reply=reply,
    )


def _looks_like_task_fragment(part: str) -> bool:
    lowered = part.lower()
    if DURATION_RE.search(part):
        return True
    return any(keyword in lowered for keyword in ["任务", "待办", "完成", "写", "做", "复习", "学习", "会议", "整理", "提交", "todo"])


def _extract_duration_minutes(text: str) -> Optional[int]:
    match = DURATION_RE.search(text)
    if not match:
        return None
    value = float(match.group("num"))
    unit = match.group("unit").lower()
    if unit in {"小时", "个小时", "h", "hr", "hrs"}:
        return max(5, int(value * 60))
    return max(5, int(value))


def _extract_difficulty(text: str) -> str:
    lowered = text.lower()
    if any(word in lowered for word in ["高难", "困难", "难", "复杂", "hard", "difficult"]):
        return "high"
    if any(word in lowered for word in ["简单", "轻松", "容易", "easy", "simple"]):
        return "low"
    return "medium"


def _clean_task_title(text: str) -> str:
    title = DURATION_RE.sub("", text)
    title = re.sub(r"(高难度?|困难|简单|轻松|中等难度?|预计|大概|约|需要)", "", title)
    title = re.sub(r"^(今天|明天|待办|任务|todo)[:：、\s-]*", "", title, flags=re.IGNORECASE)
    title = re.sub(r"\s+", " ", title).strip(" ，,：:、-·")
    return title


def _adapt_task_order(tasks: List[PlanningTask], profile: MoodProfile) -> List[PlanningTask]:
    if not profile.start_with_easy:
        return tasks

    difficulty_rank = {"low": 0, "medium": 1, "high": 2}
    return sorted(tasks, key=lambda task: difficulty_rank.get((task.difficulty or "medium").lower(), 1))


def _parse_or_default_time(value: Optional[str], timezone: str, default_hour: int, default_minute: int) -> datetime:
    tz = _safe_zoneinfo(timezone)
    now = datetime.now(tz)
    if value:
        for fmt in ["%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%H:%M"]:
            try:
                parsed = datetime.strptime(value, fmt)
                if fmt == "%H:%M":
                    parsed = parsed.replace(year=now.year, month=now.month, day=now.day)
                return parsed.replace(tzinfo=tz)
            except ValueError:
                continue
        try:
            parsed = datetime.fromisoformat(value)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=tz)
            return parsed.astimezone(tz)
        except ValueError:
            pass

    default = now.replace(hour=default_hour, minute=default_minute, second=0, microsecond=0)
    if default < now and default_hour <= 12:
        rounded = now + timedelta(minutes=(30 - now.minute % 30) % 30)
        return rounded.replace(second=0, microsecond=0)
    return default


def _safe_zoneinfo(timezone: str) -> tzinfo:
    try:
        return ZoneInfo(timezone)
    except ZoneInfoNotFoundError:
        if timezone in {"Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin"}:
            return dt_timezone(timedelta(hours=8), name="Asia/Shanghai")
        return dt_timezone.utc


def _build_slots(
    tasks: List[PlanningTask],
    profile: MoodProfile,
    start: datetime,
    end: datetime,
    insight: EmotionInsight,
) -> List[PlanSlot]:
    slots: List[PlanSlot] = []
    cursor = start

    def add_slot(minutes: int, title: str, slot_type: str, suggestion: str) -> None:
        nonlocal cursor
        if cursor >= end:
            return
        slot_end = min(cursor + timedelta(minutes=minutes), end)
        if slot_end <= cursor:
            return
        slots.append(
            PlanSlot(
                start_time=cursor.strftime("%H:%M"),
                end_time=slot_end.strftime("%H:%M"),
                title=title,
                duration_minutes=int((slot_end - cursor).total_seconds() // 60),
                slot_type=slot_type,
                suggestion=suggestion,
            )
        )
        cursor = slot_end

    add_slot(10, "状态安顿：喝水 + 写下今天最小目标", "buffer", "先不用逼自己立刻高效，把身体和注意力带回来。")

    if not tasks:
        add_slot(25, "轻量梳理待办：列出 1-3 件必须做的事", "planning", "如果情绪还模糊，先用少量信息规划，不急着把整天填满。")
        add_slot(15, "短休息：离开屏幕、伸展或散步", "break", "观察一下现在的压力/精力分数，再决定下一步强度。")
        return slots

    for index, task in enumerate(tasks):
        remaining = max(task.duration_minutes or profile.work_block_minutes, 5)
        if profile.reduce_load and (task.difficulty or "medium").lower() == "high":
            add_slot(5, f"{task.title}：只确定第一步", "planning", "高难任务先拆入口，不要求一次完成。")

        part = 1
        while remaining > 0 and cursor < end:
            block = min(remaining, profile.work_block_minutes)
            task_title = task.title if remaining <= profile.work_block_minutes else f"{task.title}（第 {part} 小段）"
            add_slot(
                block,
                task_title,
                "task",
                _task_suggestion(insight.emotion_type, task.difficulty or "medium"),
            )
            remaining -= block
            part += 1
            if remaining > 0 and cursor < end:
                add_slot(profile.break_minutes, "短时缓冲：喝水 / 远眺 / 慢走", "break", profile.advice)

        if index < len(tasks) - 1 and cursor < end:
            add_slot(profile.break_minutes, "任务切换缓冲", "break", _transition_suggestion(insight.emotion_type))

        # Respect common energy rhythm around lunch and late afternoon.
        if cursor.hour == 12 or (cursor.hour == 11 and cursor.minute >= 40):
            add_slot(60, "午餐与放空休息", "rest", "中午不安排高压输出，让下午更稳。")
        elif cursor.hour == 17 and cursor.minute >= 20:
            add_slot(30, "轻运动或散步", "rest", "用身体活动释放一天的紧绷感。")

    if cursor < end:
        leisure_minutes = 45 if insight.emotion_type in {"depressed", "tired", "low_motivation"} else 30
        add_slot(leisure_minutes, "收尾与留白", "rest", "复盘完成的部分；剩余任务可顺延，不用把今天塞满。")

    return slots


def _task_suggestion(emotion_type: str, difficulty: str) -> str:
    if emotion_type in {"anxious", "depressed", "tired"}:
        if difficulty.lower() == "high":
            return "只盯住当前小步骤；完成 60% 也可以先停。"
        return "保持低压力节奏，完成后给自己一个短暂停顿。"
    if emotion_type == "low_motivation":
        return "先做 10 分钟启动；做到最低标准就算有效推进。"
    if emotion_type == "excited_irritable":
        return "开始前深呼吸 3 次，避免因为急躁跳步骤。"
    return "利用当前稳定精力专注推进，但到点就休息。"


def _transition_suggestion(emotion_type: str) -> str:
    if emotion_type == "excited_irritable":
        return "刻意放慢切换速度，避免越做越急。"
    if emotion_type in {"anxious", "depressed", "tired", "low_motivation"}:
        return "不要马上冲进下一件事，先让大脑降噪。"
    return "短暂离屏，维持效率和恢复的平衡。"


def _format_plan_reply(
    insight: EmotionInsight,
    slots: Sequence[PlanSlot],
    profile: MoodProfile,
    has_tasks: bool,
) -> str:
    empathy = _empathy_sentence(insight)
    lines = [empathy]
    lines.append(f"我先按你当前的状态判断：{insight.emotion_label}，强度约 {insight.level}/5。")
    if insight.cues:
        lines.append(f"我留意到的线索：{'、'.join(insight.cues[:5])}。")
    if insight.support_note:
        lines.append(insight.support_note)
    if insight.needs_follow_up and insight.follow_up_question:
        lines.append(insight.follow_up_question)
    if not has_tasks:
        lines.append("你还没有给出完整待办，我先给一个低压力的起步版；补充任务后我可以继续细化。")

    lines.append("\n今天的适配日程：")
    for slot in slots:
        lines.append(
            f"- {slot.start_time}-{slot.end_time}（{slot.duration_minutes}分钟）{slot.title}｜{slot.suggestion}"
        )

    lines.append(f"\n情绪调节小建议：{profile.advice}")
    lines.append("这份安排可以随时调轻或调紧。你想增减任务、修改开始时间，还是把某个任务拆得更细一点？")
    return "\n".join(lines)


def _empathy_sentence(insight: EmotionInsight) -> str:
    if insight.emotion_type == "anxious":
        return "听起来你现在背着不少压力，我们先把事情拆小一点，不让日程继续放大焦虑。"
    if insight.emotion_type == "depressed":
        return "你已经在很用力地撑着了，今天的安排会以减压和可完成为主。"
    if insight.emotion_type == "tired":
        return "感觉你的身体和注意力都需要被照顾一下，我们先用更短的工作段来安排。"
    if insight.emotion_type == "low_motivation":
        return "没动力的时候不适合硬逼自己满负荷，先把标准降到能启动。"
    if insight.emotion_type == "excited_irritable":
        return "你现在的能量像是比较急、比较满，我们会刻意留缓冲，让节奏稳下来。"
    if insight.emotion_type == "positive_calm":
        return "你现在状态比较平稳，适合做效率和休息兼顾的安排。"
    return "我会先用温和的方式帮你安排；如果你愿意补充情绪和精力状态，我还能调得更贴近你。"



