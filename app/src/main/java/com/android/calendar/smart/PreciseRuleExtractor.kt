package com.android.calendar.smart

/**
 * 中文非结构化文本信息提取器 (Time, Location, Event)
 * 策略：锚点优先 + 指令句优先 + 长地点加权
 *
 * ✅ 等价翻译自 extractor.py (PreciseRuleExtractor)
 */
class PreciseRuleExtractor {

    // 1. 显式锚点 (Key-Value 结构)
    private val anchors: Map<String, List<Regex>> = mapOf(
        "time" to listOf(
            Regex("""(?:时间|日期|Time|开庭时间|集合时间|截止时间)[：:]\s*(.*?)""")
        ),
        "location" to listOf(
            Regex("""(?:地点|地址|Location|会场|开庭地点|集合地点|切换地点|就诊地点)[：:]\s*(.*?)""")
        ),
        "event" to listOf(
            Regex("""(?:主题|事件|案由|Subject|Event|模拟场景)[：:]\s*(.*?)""")
        )
    )

    // 2. 时间正则模式
    private data class TimePattern(val regex: Regex, val typeName: String)

    private val timePatterns: List<TimePattern> = listOf(
        // 截止时间/Deadline (优先)
        TimePattern(
            Regex("""(?:截止|于|在|至)\s*(\d{1,2}月\d{1,2}日[^\s，。]*?(?:前|后|截止|下班前))"""),
            "action_deadline"
        ),
        // 复合长日期 (下周一（4月10日）上午9点)
        TimePattern(
            Regex(
                """(?:本|下|上)?(?:周[一二三四五六日]|星期[一二三四五六日])\s*[（(].*?[）)]\s*(?:上午|下午|晚|早)?\s*\d{1,2}[:：]\d{2}(?:[-~至]\d{1,2}[:：]\d{2})?"""
            ),
            "compound_date"
        ),
        // 期间
        TimePattern(Regex("""\d{4}年春节期间"""), "festival_period"),
        TimePattern(Regex("""\d{1,2}月\d{1,2}日\s*[-~至]\s*\d{1,2}(?:日|号)?"""), "date_range_short"),
        // 相对口语
        TimePattern(
            Regex("""(?:昨|今|明|后|前|下|上)(?:天|日|周[一二三四五六日]|早|晚)\s*(?:上午|下午|晚上|凌晨)?(?:\d{1,2}[点:：]\d{0,2}(?:分|半)?)?"""),
            "relative_oral"
        ),
        // 常规日期时间
        TimePattern(
            Regex("""\d{4}年\d{1,2}月\d{1,2}日(?:[上下]午)?(?:\d{1,2}[:点]\d{0,2}(?:分|半)?)?"""),
            "full_date"
        ),
        TimePattern(
            Regex("""\d{1,2}月\d{1,2}日(?:[上下]午)?(?:\d{1,2}[:点]\d{0,2}(?:分|半)?)?"""),
            "short_date"
        ),
        TimePattern(
            Regex("""(?:上午|下午|中午|晚上|凌晨)\d{1,2}[点:：]\d{0,2}(?:分|半)?"""),
            "period_time"
        ),
        TimePattern(
            Regex("""\d{1,2}[:：]\d{2}(?:[-~至]\d{1,2}[:：]\d{2})?"""),
            "digital_time"
        )
    )

    // 3. 地点模式与词库
    private val standaloneLocations: List<String> = listOf(
        "办公室", "会议室", "大厅", "食堂", "操场", "健身房", "茶水间",
        "仁心诊所", "T3航站楼", "社区中心", "影院", "电影院", "报告厅",
        "中央公园", "川菜馆", "体育馆", "展览馆", "博物馆", "宴会厅",
        "线上", "腾讯会议", "Zoom", "钉钉", "审判庭", "演播厅", "窗口",
        "教室", "南门", "大楼", "诊室", "殿堂", "会展中心", "数据中心",
        "人力资源部", "财务部", "维修部"
    )

    private data class LocationPattern(val regex: Regex, val priority: Int)

    private val locationPatterns: List<LocationPattern> = listOf(
        // 复合地点
        LocationPattern(Regex("""(线上.*?线下[^，。；\n]+)"""), 20),
        // 极长地点描述 (高优先级)
        LocationPattern(
            Regex(
                """([^，,。；!?:：\s\d（(]{6,30}?(?:会议室|教室|大厅|广场|航站楼|影院|号厅|诊所|中心|体育馆|园区|车间|总部|分公司|窗口|演播厅|审判庭|诊室|酒店|殿堂|馆|部|楼|门))"""
            ),
            15
        ),
        // 普通地点
        LocationPattern(
            Regex(
                """([^，,。；!?:：\s\d（(]{2,10}?(?:会议室|教室|大厅|广场|航站楼|影院|号厅|诊所|中心|体育馆|园区|车间|总部|分公司|窗口|演播厅|审判庭|诊室|酒店|殿堂|馆|部))"""
            ),
            10
        ),
        // 介词引导
        LocationPattern(
            Regex("""(?:在|于|地点[：:]|切换地点[：:]|设在)\s*([^，,。；!?:：\s]+)"""),
            8
        )
    )

    // 4. 事件关键词
    private val eventKeywords: List<String> = listOf(
        "会议", "大修", "复盘", "义诊", "登机", "加班", "就诊",
        "上映", "典礼", "聚餐", "培训", "面试", "沟通", "家长会",
        "读书会", "检修", "试吃", "庆生", "亲子课", "办理业务",
        "峰会", "体检", "工作坊", "结账", "启动会", "年会",
        "审核", "讲座", "度假", "晚会", "打翻", "传票", "审理",
        "演练", "预展", "调查", "面谈", "婚礼", "见", "买好",
        "投票", "选举"
    )

    private val splitSentenceRegex = Regex("""[。；\n]""")
    private val splitSegmentsRegex = Regex("""[，,。；;!！\n]""")
    private val stripPrefixRegex = Regex("""^(在|于|到|至|地点[：:]?|切换地点[：:]|设在)\s*""")
    private val stripTrailingPuncRegex = Regex("""[，,。；;]$""")
    private val digitsOnlyRegex = Regex("""^\d+$""")

    private data class TimeMatch(
        var str: String,
        val start: Int,
        var end: Int,
        val type: String
    )

    /** 提取显式锚点后的内容 */
    private fun getAnchorContent(text: String, typeKey: String): String? {
        val patterns = anchors[typeKey] ?: return null
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val content = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val segments = splitSentenceRegex.split(content)
            var clean = segments.firstOrNull().orEmpty()
            if (clean.contains('（') && !clean.contains('）')) {
                clean += "）"
            }
            val result = clean.trim()
            if (result.isNotEmpty()) return result
        }
        return null
    }

    fun extractTime(text: String): String {
        val anchorVal = getAnchorContent(text, "time")
        if (!anchorVal.isNullOrEmpty()) return anchorVal

        val matches = mutableListOf<TimeMatch>()
        for (tp in timePatterns) {
            val all = tp.regex.findAll(text)
            for (m in all) {
                val strVal = if (tp.typeName == "action_deadline") {
                    m.groupValues.getOrNull(1).orEmpty()
                } else {
                    m.value
                }
                matches.add(
                    TimeMatch(
                        str = strVal,
                        start = m.range.first,
                        end = m.range.last + 1,
                        type = tp.typeName
                    )
                )
            }
        }

        if (matches.isEmpty()) return ""

        // 优先截止时间
        val deadlines = matches.filter { it.type == "action_deadline" }
        if (deadlines.isNotEmpty()) {
            return deadlines.maxBy { it.str.length }.str
        }

        // 合并相邻时间
        matches.sortBy { it.start }
        val merged = mutableListOf<String>()

        var curr = matches[0]
        for (i in 1 until matches.size) {
            val next = matches[i]
            val gap = text.substring(curr.end, next.start)
            val hasPunc = gap.any { it in charArrayOf('，', '。', '；', '!') }

            if (gap.length <= 5 && !hasPunc) {
                // 合并：用原文片段覆盖（与 Python 逻辑一致）
                curr.str = text.substring(curr.start, next.end)
                curr.end = next.end
            } else {
                if (merged.none { it.contains(curr.str) }) {
                    merged.add(curr.str)
                }
                curr = next
            }
        }
        merged.add(curr.str)

        return merged.maxBy { it.length }
    }

    fun extractLocation(text: String): String {
        val anchorVal = getAnchorContent(text, "location")
        if (!anchorVal.isNullOrEmpty()) {
            // 特殊：锚点包含线上，正文提到线下但锚点未包含线下
            if (anchorVal.contains("线上") && text.contains("线下") && !anchorVal.contains("线下")) {
                val idx = text.indexOf("线下")
                if (idx >= 0) {
                    val restAll = text.substring(idx)
                    val rest = restAll.split('。').firstOrNull().orEmpty()
                    return "$anchorVal，$rest"
                }
            }
            return anchorVal
        }

        val candidates = mutableListOf<Pair<String, Int>>()

        // 词库命中
        for (loc in standaloneLocations) {
            if (text.contains(loc)) candidates.add(loc to 5)
        }

        // 正则候选
        for (lp in locationPatterns) {
            for (m in lp.regex.findAll(text)) {
                val raw = m.groupValues.getOrNull(1).orEmpty()
                var clean = stripPrefixRegex.replace(raw, "")
                clean = stripTrailingPuncRegex.replace(clean, "")

                if (clean.length >= 2 &&
                    !clean.contains("期间") &&
                    !digitsOnlyRegex.matches(clean)
                ) {
                    candidates.add(clean to lp.priority)
                }
            }
        }

        if (candidates.isEmpty()) return ""

        // 按 (priority, len) 逆序
        val best = candidates.maxWith(
            compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length }
        )
        return best.first
    }

    fun extractEvent(text: String, extractedTime: String, extractedLoc: String): String {
        // 1. 锚点优先
        val anchorVal = getAnchorContent(text, "event")

        // 2. 指令句优先
        val instructionSentences = mutableListOf<String>()
        val sentences = splitSentenceRegex.split(text)
        for (s0 in sentences) {
            val sent = s0.trim()
            if (sent.isEmpty()) continue
            if (listOf("请", "务必", "将", "定于", "诚邀", "成功预约").any { sent.contains(it) }) {
                val cleanSent = Regex("""^.*?[:：]""").replace(sent, "")
                instructionSentences.add(cleanSent)
            }
        }

        // 书名号/引号内容
        val bookMatch = Regex("""[《"“]([^》"”]+)[》"”]""").find(text)
        if (bookMatch != null) {
            val content = bookMatch.groupValues.getOrNull(1).orEmpty()
            if (content.length > 1) {
                return if (text.contains("电影")) "看$content" else content
            }
        }

        // 锚点清洗
        if (!anchorVal.isNullOrEmpty() && anchorVal.length > 4) {
            var cleanAnchor = Regex("""^(关于|举办|召开)""").replace(anchorVal, "")
            cleanAnchor = Regex("""的?(通知|提醒|公告|计划)$""").replace(cleanAnchor, "")
            return cleanAnchor
        }

        // 指令句取最长
        if (instructionSentences.isNotEmpty()) {
            return instructionSentences.maxBy { it.length }
        }

        // 3. 兜底消去法
        var tempText = text
        if (extractedTime.isNotEmpty()) tempText = tempText.replace(extractedTime, " ")
        if (extractedLoc.isNotEmpty()) tempText = tempText.replace(extractedLoc, " ")
        tempText = Regex("""^(主题|关于|时间|地点|内容|案由|事件)[：:]?""").replace(tempText, "")

        val segments = splitSegmentsRegex.split(tempText)

        var bestSeg = ""
        var maxScore = -50

        for (seg0 in segments) {
            val seg = seg0.trim()
            if (seg.length < 2) continue

            var score = 0
            if (eventKeywords.any { seg.contains(it) }) score += 15
            if (listOf("进行", "举办", "召开", "开始").any { seg.contains(it) }) score += 5
            if (seg in listOf("原定", "改为", "时间", "地点")) score -= 10

            if (score > maxScore) {
                maxScore = score
                bestSeg = seg
            }
        }

        return if (bestSeg.isNotEmpty()) bestSeg else ""
    }

    /** 主入口 */
    fun extract(text: String?): ExtractResult {
        if (text.isNullOrEmpty()) return ExtractResult()

        val time = extractTime(text)
        val location = extractLocation(text)
        val event = extractEvent(text, time, location)

        return ExtractResult(time = time, location = location, event = event)
    }
}
