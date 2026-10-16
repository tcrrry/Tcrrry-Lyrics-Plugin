package com.tcrrry.lyrics.engine.lyrics

import java.text.Normalizer
import java.util.Locale

/** Conservative display filter. Neither timestamps nor the source cache are rewritten. */
internal object OpeningCreditsPolicy {
    private val roles = listOf(
        "作词", "作詞", "作曲", "词曲", "詞曲", "编曲", "編曲", "词", "詞", "曲",
        "制作人", "製作人", "音乐制作", "音樂製作", "制作公司", "製作公司",
        "发行", "發行", "发行公司", "發行公司", "出品", "出品人", "出品公司",
        "混音", "混音师", "混音師", "录音", "錄音", "录音师", "錄音師",
        "母带", "母帶", "母带工程师", "母帶工程師", "监制", "監製", "制作统筹", "製作統籌",
        "演唱", "歌手", "和声", "和聲", "合声", "合聲", "和音", "Rap", "录制", "錄製", "经纪公司", "經紀公司", "和声编写", "和聲編寫", "吉他", "吉它", "贝斯", "貝斯",
        "钢琴", "鋼琴", "弦乐", "弦樂", "弦乐团", "弦樂團", "鼓", "版权", "版權", "OP", "SP",
        "lyrics", "lyrics by", "lyricist", "composer", "composed by", "arranger", "arranged by",
        "producer", "produced by", "mixed by", "mastered by", "recorded by", "vocal", "vocals",
        "键盘", "鍵盤", "配唱", "配唱制作人", "配唱製作人", "人声编辑", "人聲編輯",
        "人声监制", "人聲監製", "录音工程师", "錄音工程師", "混音工程师", "混音工程師",
        "录音室", "錄音室", "混音室", "母带室", "母帶室", "音乐总监", "音樂總監",
        "执行制作", "執行製作", "执行制作人", "執行製作人", "统筹", "統籌", "企划", "企劃",
        "制作", "製作", "音乐制作人", "音樂製作人", "母带处理", "母帶處理",
        "歌词提供", "歌詞提供", "作詞家", "作曲家", "編曲家",
        "music by", "written by", "arrangement", "music producer", "vocal producer", "vocal production",
        "vocal editing", "vocal recording", "vocal arranger", "backing vocal", "backing vocals",
        "background vocals", "harmony", "keyboard", "keyboards", "guitar", "guitars", "drum", "drums",
        "bass", "piano", "strings", "string arrangement", "recording engineer", "recording studio",
        "mixing", "mixing engineer", "mastering", "mastering engineer", "executive producer",
        "programming", "publisher", "copyright", "production", "label", "music", "words",
        "作词人", "作詞人", "作曲人", "编曲人", "編曲人", "词作者", "詞作者", "曲作者",
        "混音母带", "混音母帶", "母带制作", "母帶製作", "配唱制作", "配唱製作", "配唱监制", "配唱監製",
        "人声处理", "人聲處理", "音频编辑", "音頻編輯", "弦乐编写", "弦樂編寫", "弦乐编配", "弦樂編配",
        "录音棚", "錄音棚", "音乐监制", "音樂監製", "总监", "總監", "版权公司", "版權公司",
        "supervisor", "music supervisor", "mastering producer", "mixing mastering", "audio editing", "audio engineer"
    ).map(::labelKey).toSet()
    private val separators = Regex("\\s*[/／&＆、+・·]\\s*")
    private val bilingual = Regex("^(\\p{IsHan}+)([a-z]+)$|^([a-z]+)(\\p{IsHan}+)$")
    private val byline = Regex("^(?:lyrics|music|words|composed|arranged|produced|written|mixed|mastered|recorded|performed)\\s+by\\s+\\S.*$", RegexOption.IGNORE_CASE)
    private val qualifiers = listOf("后期", "後期", "前期", "联合", "聯合", "执行", "執行", "助理",
        "音乐", "音樂", "歌曲", "项目", "項目", "人声", "人聲", "乐器", "樂器", "制作", "製作", "音频", "音頻")
    private val roleBoundary = Regex("\\s+|[-·/()\\[\\]_]")
    private val vocalLabel = Regex("^(?:男|女|合|唱|男声|女声|男聲|女聲|合唱|齐唱|齊唱|童声|童聲|旁白|独白|獨白|对白|對白|male|female|all|dialogue|chorus|verse)(?:\\s*\\([^)]*\\))?$", RegexOption.IGNORE_CASE)
    private val dialogueLabel = Regex("(?:说|說|问|問|回答|答道|喊道|唱道|said|says|asked|replied)$", RegexOption.IGNORE_CASE)
    private val pronounLabel = Regex("^(?:我|你|他|她|它|I\\b|you\\b|he\\b|she\\b|we\\b|they\\b)", RegexOption.IGNORE_CASE)
    private val chineseName = Regex("[\\p{IsHan}]{2,5}(?:[/、][\\p{IsHan}]{2,5})*")
    private val latinName = Regex("[A-Z][\\p{L}.'-]*(?: [A-Z][\\p{L}.'-]*){0,3}")

    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace("\uFEFF", "").replace("\u200B", "").trim()
    private fun labelKey(text: String) = normalize(text).lowercase(Locale.ROOT)
        .filterNot { it.isWhitespace() || it in "()[]【】·・" }
    private val copyrightRole = Regex("^(?:词|詞|曲|词曲|詞曲)?(?:op|sp)(?:\\([^()]{1,60}\\))?$", RegexOption.IGNORE_CASE)
    private fun isRole(label: String) = copyrightRole.matches(normalize(label).filterNot(Char::isWhitespace)) || isRoleKey(labelKey(label), 0)

    private fun isRoleKey(key: String, depth: Int): Boolean {
        if (key in roles) return true
        if (depth >= 4) return false
        // Check after each qualifier: music + production company must not become just "company".
        if (qualifiers.any { key.startsWith(it) && isRoleKey(key.removePrefix(it), depth + 1) }) return true
        for (suffix in listOf("制作人", "製作人", "工程师", "工程師", "助理", "producer", "engineer",
            "演唱", "监制", "監製", "录音", "錄音", "录音师", "錄音師", "录音室", "錄音室", "录音棚", "錄音棚", "编写", "編寫", "编配", "編配")) {
            if (key.endsWith(suffix) && key.length > suffix.length &&
                key.removeSuffix(suffix) in roles) return true
        }
        val match = bilingual.matchEntire(key) ?: return false
        val parts = match.groupValues.drop(1).filter(String::isNotEmpty)
        if (parts.all { isRoleKey(it, depth + 1) }) return true
        // A known multi-character Chinese job can have an unseen English alias.
        return parts.any { it.length >= 2 && it.all { character -> character.code in 0x4e00..0x9fff } &&
            isRoleKey(it, depth + 1) }
    }

    private fun titleKey(text: String): String {
        var title = normalize(text)
        // Providers append OST/version annotations to a short catalog title. Identity check only.
        while (title.endsWith(')')) {
            var depth = 0
            var opening = -1
            for (index in title.lastIndex downTo 0) {
                when (title[index]) { ')' -> depth++; '(' -> depth-- }
                if (depth == 0) { opening = index; break }
            }
            if (opening <= 0) break
            title = title.substring(0, opening).trimEnd()
        }
        return labelKey(title)
    }

    private fun unwrapped(text: String): String {
        var value = normalize(text)
        val wrappers = listOf('(' to ')', '[' to ']', '【' to '】')
        repeat(3) {
            if (value.length > 2 && wrappers.any { value.first() == it.first && value.last() == it.second })
                value = value.substring(1, value.lastIndex).trim()
        }
        return value
    }

    private fun protectedLabel(label: String) = vocalLabel.matches(label) ||
        dialogueLabel.containsMatchIn(label) || pronounLabel.containsMatchIn(label)

    private fun roleLabel(label: String) = isRole(label) || separators.split(label).all(::isRole)

    private fun field(text: String): Pair<String, String>? {
        val colon = text.indexOf(':')
        if (colon !in 1..128) return null
        val label = text.substring(0, colon).trim()
        val value = text.substring(colon + 1).trim()
        if (value.isEmpty() || label.none(Char::isLetter) ||
            label.any { it in "，。？！!?;；…" } || protectedLabel(label)) return null
        return label to value
    }

    private fun isCredit(text: String): Boolean {
        val normalized = unwrapped(text)
        if (byline.matches(normalized)) return true
        if (':' in normalized) {
            val label = field(normalized)?.first ?: return false
            return roleLabel(label)
        }
        return roleBoundary.findAll(normalized).takeWhile { it.range.first <= 128 }.any { boundary ->
            val label = normalized.substring(0, boundary.range.first).trim()
            val value = normalized.substring(boundary.range.last + 1).trim(' ', ')', ']', '/')
            label.length >= 2 && value.isNotBlank() && !protectedLabel(label) && roleLabel(label)
        }
    }

    private fun isFiller(text: String) = text.isBlank() || text.length <= 8 && text.none(Char::isLetterOrDigit)
    private fun isName(text: String): Boolean {
        if (text.isBlank() || protectedLabel(text)) return false
        return chineseName.matches(text) || latinName.matches(text) ||
            text.contains('@') || text.endsWith("公司") || text.endsWith("工作室")
    }

    fun hiddenIndices(source: String, lines: List<String>, enabled: Boolean,
        title: String = "", artist: String = "", starts: List<Long> = emptyList()): Set<Int> {
        if (!enabled || source !in setOf("QQ音乐", "网易云音乐")) return emptySet()
        val texts = lines.map(::unwrapped)
        val fillers = texts.map(::isFiller)
        val fields = texts.map(::field)
        val labelOnly = texts.map { it.length >= 2 && roleLabel(it) }
        val explicitCredit = texts.indices.map { !labelOnly[it] && isCredit(texts[it]) }
        val pairedLabel = texts.indices.map { labelOnly[it] && texts.getOrNull(it + 1)?.let(::isName) == true }
        val names = texts.indices.map { it > 0 && pairedLabel[it - 1] }
        val credit = texts.indices.map { explicitCredit[it] || pairedLabel[it] }
        val repeated = texts.groupingBy(::labelKey).eachCount()
        val structural = texts.indices.map { fields[it] != null && (repeated[labelKey(texts[it])] ?: 0) == 1 }
        fun header(index: Int): Boolean {
            // Require a matching song identity and a following credit block; a title may itself be sung.
            if (index > 8 || starts.getOrNull(index)?.let { it > 5_000L } == true) return false
            val text = labelKey(lines[index])
            val song = titleKey(title)
            val singer = labelKey(artist)
            val matches = song.isNotBlank() && titleKey(lines[index]) == song || singer.isNotBlank() && song.isNotBlank() &&
                listOf("-", "–", "—", "·", "/").any { text == labelKey("$title$it$artist") }
            val following = (index + 1 until lines.size).take(6)
                .takeWhile { credit[it] || structural[it] || names[it] || fillers[it] }
            val count = following.count { credit[it] }
            if (count < 2 && following.none { explicitCredit[it] }) return false
            val dense = starts.getOrNull(following.lastOrNull() ?: index)?.let { end ->
                starts.getOrNull(index)?.let { start -> end >= start && end - start <= 10_000L }
            } == true
            val parts = normalize(lines[index]).split(Regex("\\s+[-–—]\\s+"), limit = 2)
            val matchingTitleHeader = parts.size == 2 && parts[1].isNotBlank() &&
                (song.isNotBlank() && titleKey(parts[0]) == song || song.isBlank() && source == "QQ音乐" && dense && count >= 3)
            return (matches && count >= if (dense) 1 else 2) || matchingTitleHeader && count >= 2
        }
        val headers = texts.indices.map(::header)
        val candidate = texts.indices.takeWhile { fillers[it] || headers[it] || credit[it] || names[it] || structural[it] }
        // Dictionary matches establish the block; unknown fields no longer veto all later credits.
        var end = if (candidate.count { credit[it] } >= 2) candidate.size else
            candidate.takeWhile { fillers[it] || headers[it] || explicitCredit[it] }.size
        if (end < texts.size && (':' in texts[end] && protectedLabel(texts[end].substringBefore(':').trim()) ||
                (repeated[labelKey(texts[end])] ?: 0) > 1)) {
            // Unknown fields directly preceding a dialogue/repeated verse may also be sung text.
            while (end > 0 && (fillers[end - 1] || structural[end - 1] && !credit[end - 1])) end--
        }
        // An all-credit response is not evidence of a usable lyric body. Keep it rather than emit empty TTML.
        if (end == lines.size) return emptySet()
        return (0 until end).toSet()
    }
}
