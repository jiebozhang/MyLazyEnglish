package com.lazyeng.family.core.common.subtitle

import com.lazyeng.family.core.common.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class SubtitleFormat { SRT, VTT }
enum class SubtitleEncoding(val charsetName: String) {
    UTF8("UTF-8"), UTF16_LE("UTF-16LE"), UTF16_BE("UTF-16BE"), GBK("GBK"),
}

data class ParsedCue(val sequence: Int, val startMs: Long, val endMs: Long, val text: String)
data class ParsedSubtitle(
    val cues: List<ParsedCue>, val encoding: SubtitleEncoding, val hadBom: Boolean,
    val sourceHash: String, val parserVersion: String,
)

enum class SubtitleIssue(val advice: String) {
    ENCODING_UNCERTAIN("无法可靠识别编码。请选择文件的实际编码，或将原文件导出为 UTF-8 后重试。"),
    ENCODING_INVALID("文件包含不符合所选编码的字节或控制字符。请检查编码并重新导出文件。"),
    FORMAT_INVALID("字幕结构不完整。请检查文件格式、序号、时间行和字幕之间的空行。"),
    TIMESTAMP_INVALID("时间码无效。请检查箭头、时分秒范围以及三位毫秒。"),
    SEQUENCE_INVALID("字幕序号必须是唯一的正整数。请修正重复或无效序号。"),
    MARKUP_UNSUPPORTED("字幕包含不支持或不完整的样式标签。请导出为纯文本字幕后重试。"),
    VTT_LAYOUT_UNSUPPORTED("字幕含有暂不支持的布局或样式信息。请导出为无定位样式的 WebVTT 后重试。"),
}

/** No source text is copied to diagnostics. Location and advice are safe for a parent-facing UI. */
data class SubtitleParseError(val issue: SubtitleIssue, val fileLine: Int?, val encodingCandidates: List<SubtitleEncoding> = emptyList()) {
    val error: AppError get() = AppError(AppErrorCode("SUBTITLE_${issue.name}"),
        ErrorHandling.USER_ACTION, UserMessageKey("error.subtitle.${issue.name.lowercase()}"))
    val userMessage: String get() = (fileLine?.let { "第 $it 行：" } ?: "") + issue.advice
}
sealed interface SubtitleParseResult {
    data class Success(val subtitle: ParsedSubtitle) : SubtitleParseResult
    data class Failure(val report: SubtitleParseError) : SubtitleParseResult
}

/** Pure byte-to-cue parser. Semantic timeline validation and tokenization belong to E3-T2. */
object SubtitleParser {
    const val VERSION = "srt-vtt-1"
    fun parse(bytes: ByteArray, format: SubtitleFormat, encoding: SubtitleEncoding? = null): SubtitleParseResult = try {
        val bom = when {
            bytes.starts(0xEF, 0xBB, 0xBF) -> SubtitleEncoding.UTF8 to 3
            bytes.starts(0xFF, 0xFE, 0, 0) || bytes.starts(0, 0, 0xFE, 0xFF) -> fail(SubtitleIssue.ENCODING_UNCERTAIN)
            bytes.starts(0xFF, 0xFE) -> SubtitleEncoding.UTF16_LE to 2
            bytes.starts(0xFE, 0xFF) -> SubtitleEncoding.UTF16_BE to 2
            else -> null
        }
        if (encoding != null && bom != null && encoding != bom.first) fail(SubtitleIssue.ENCODING_INVALID)
        val selected = encoding ?: bom?.first ?: SubtitleEncoding.UTF8
        val text = try { decode(bytes, selected, bom?.second ?: 0) } catch (_: CharacterCodingException) {
            if (encoding != null || bom != null) fail(SubtitleIssue.ENCODING_INVALID)
            // Decodability alone cannot distinguish GBK from other legacy encodings.
            val candidates = try { decode(bytes, SubtitleEncoding.GBK, 0); listOf(SubtitleEncoding.GBK) }
                catch (_: CharacterCodingException) { emptyList() }
            throw ParseFailure(SubtitleParseError(SubtitleIssue.ENCODING_UNCERTAIN, null, candidates))
        }
        if (text.any { (it.code < 32 && it !in "\r\n\t") || it == '\uFFFD' || it == '\uFEFF' }) {
            fail(SubtitleIssue.ENCODING_INVALID)
        }
        val cues = parseText(text.replace("\r\n", "\n").replace('\r', '\n'), format)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        SubtitleParseResult.Success(ParsedSubtitle(cues, selected, bom != null, hash, VERSION))
    } catch (failure: ParseFailure) { SubtitleParseResult.Failure(failure.report) }

    private fun decode(bytes: ByteArray, encoding: SubtitleEncoding, offset: Int): String =
        Charset.forName(encoding.charsetName).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()

    private fun parseText(text: String, format: SubtitleFormat): List<ParsedCue> {
        val lines = text.split('\n')
        var index = 0
        if (format == SubtitleFormat.VTT) {
            if (!Regex("WEBVTT(?:[ \t].*)?").matches(lines[0]) || "-->" in lines[0]) fail(SubtitleIssue.FORMAT_INVALID, 1)
            index++
            if (index < lines.size && lines[index].isNotBlank()) fail(SubtitleIssue.VTT_LAYOUT_UNSUPPORTED, index + 1)
        }
        val cues = mutableListOf<ParsedCue>()
        val sequences = mutableSetOf<Int>()
        while (index < lines.size) {
            if (lines[index].isBlank()) { index++; continue }
            val start = index
            while (index < lines.size && lines[index].isNotBlank()) index++
            val block = lines.subList(start, index)
            if (format == SubtitleFormat.VTT && (block[0] == "NOTE" || block[0].startsWith("NOTE ") || block[0].startsWith("NOTE\t"))) continue
            if (format == SubtitleFormat.VTT && block[0] in setOf("STYLE", "REGION")) fail(SubtitleIssue.VTT_LAYOUT_UNSUPPORTED, start + 1)
            val timingIndex: Int
            val sequence: Int
            if (format == SubtitleFormat.SRT) {
                sequence = block[0].trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()
                    ?: fail(SubtitleIssue.SEQUENCE_INVALID, start + 1)
                if (sequence <= 0 || !sequences.add(sequence)) fail(SubtitleIssue.SEQUENCE_INVALID, start + 1)
                timingIndex = 1
            } else {
                sequence = cues.size + 1 // WebVTT identifiers are labels, not ordering numbers.
                timingIndex = if ("-->" in block[0]) 0 else 1
            }
            val timing = block.getOrNull(timingIndex) ?: fail(SubtitleIssue.TIMESTAMP_INVALID, start + timingIndex + 1)
            val match = Regex("([^ \\t]+)[ \\t]+-->[ \\t]+([^ \\t]+)(.*)").matchEntire(timing.trim())
                ?: fail(SubtitleIssue.TIMESTAMP_INVALID, start + timingIndex + 1)
            if (match.groupValues[3].isNotBlank()) fail(SubtitleIssue.VTT_LAYOUT_UNSUPPORTED, start + timingIndex + 1)
            val from = timestamp(match.groupValues[1], format, start + timingIndex + 1)
            val to = timestamp(match.groupValues[2], format, start + timingIndex + 1)
            val body = block.drop(timingIndex + 1)
            if (body.isEmpty()) fail(SubtitleIssue.FORMAT_INVALID, start + timingIndex + 2)
            // A missing cue separator must not turn a later timestamp into spoken text.
            if (body.any { "-->" in it }) fail(SubtitleIssue.FORMAT_INVALID, start + timingIndex + 2)
            val clean = body.mapIndexed { n, line -> cleanMarkup(line, start + timingIndex + 2 + n) }.joinToString("\n")
            if (clean.isBlank()) fail(SubtitleIssue.FORMAT_INVALID, start + timingIndex + 2)
            cues += ParsedCue(sequence, from, to, clean)
        }
        if (cues.isEmpty()) fail(SubtitleIssue.FORMAT_INVALID, 1)
        return cues.sortedBy { it.sequence }
    }

    private fun timestamp(raw: String, format: SubtitleFormat, line: Int): Long {
        val pattern = if (format == SubtitleFormat.SRT) Regex("([0-9]{2,}):([0-9]{2}):([0-9]{2}),([0-9]{3})")
            else Regex("(?:([0-9]{2,}):)?([0-9]{2}):([0-9]{2})\\.([0-9]{3})")
        val parts = pattern.matchEntire(raw)?.groupValues ?: fail(SubtitleIssue.TIMESTAMP_INVALID, line)
        val hours = if (parts[1].isEmpty()) 0L else parts[1].toLongOrNull() ?: fail(SubtitleIssue.TIMESTAMP_INVALID, line)
        val minutes = parts[2].toLong()
        val seconds = parts[3].toLong()
        if (minutes > 59 || seconds > 59 || hours > (Long.MAX_VALUE - 3_599_999) / 3_600_000) fail(SubtitleIssue.TIMESTAMP_INVALID, line)
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1000 + parts[4].toLong()
    }

    private val styling = Regex("</?(?:i|b|u)>|<font(?:\\s+[a-zA-Z]+\\s*=\\s*(?:\"[^\"<>]*\"|'[^'<>]*'))*\\s*>|</font>", RegexOption.IGNORE_CASE)
    private fun cleanMarkup(text: String, line: Int): String {
        val clean = styling.replace(text, "")
        // No HTML is executed; unknown tags fail visibly instead of silently losing body text.
        if (Regex("<[/!a-zA-Z]").containsMatchIn(clean)) fail(SubtitleIssue.MARKUP_UNSUPPORTED, line)
        return clean
    }
    private fun ByteArray.starts(vararg prefix: Int) = size >= prefix.size && prefix.indices.all { (this[it].toInt() and 255) == prefix[it] }
    private fun fail(issue: SubtitleIssue, line: Int? = null): Nothing = throw ParseFailure(SubtitleParseError(issue, line))
    private class ParseFailure(val report: SubtitleParseError) : RuntimeException(report.issue.name)
}
