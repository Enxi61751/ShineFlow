package com.android.calendar.ocr

/**
 * 根据你的后端返回结构调整字段名：
 * 示例返回：
 * {
 *   "code": 0,
 *   "msg": "ok",
 *   "data": {
 *     "lines": [
 *       {"text":"...", "score":0.98, "box":[x1,y1,x2,y2,x3,y3,x4,y4]}
 *     ]
 *   }
 * }
 */
data class OcrResp(
    val code: Int = -1,
    val msg: String? = null,
    val data: OcrData? = null
)

data class OcrData(
    val lines: List<OcrLine> = emptyList(),
    val fullText: String? = null // 可选：如果后端也返回拼好的全文
)

data class OcrLine(
    val text: String = "",
    val score: Float? = null,
    val box: List<Float>? = null // 8 个数：四个点(x,y)；也可用 Int
)
