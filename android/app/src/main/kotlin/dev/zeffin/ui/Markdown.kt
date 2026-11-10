// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.theme.RobotoMono
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import dev.zeffin.ui.present.address
import dev.zeffin.link.chatText
import dev.zeffin.link.inputText

/**
 * The Markdown the agent writes (GitHub style), read with commonmark-java and drawn with the app's own components:
 * headings by size, tables, quotes, separators, nested and task lists, code blocks with Copy, bold, italics,
 * strikethrough, inline code and links. Raw HTML stays as text and images as their alt text: nothing is fetched.
 * Nothing here can throw on odd input: the text is the agent's (docs/design/system/components/ChatMessage.md).
 */
object Markdown {
    sealed class Block {
        data class Paragraph(val text: AnnotatedString) : Block()
        data class Heading(val level: Int, val text: AnnotatedString) : Block()
        data class ListBlock(val ordered: Boolean, val start: Int, val items: List<Item>) : Block()
        data class Quote(val blocks: List<Block>) : Block()
        data class Code(val language: String?, val text: String) : Block()
        data object Rule : Block()
        data class Table(val alignments: List<Align>, val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : Block()
    }

    /** A list item; [checked] is null unless it is a task ("- [ ]" or "- [x]"). */
    data class Item(val checked: Boolean?, val blocks: List<Block>)

    enum class Align { START, CENTER, END }

    /** The annotation that marks a link's address in the text. */
    const val URL = "url"

    private val parser: Parser by lazy {
        Parser.builder().extensions(listOf(
            TablesExtension.create(),
            StrikethroughExtension.builder().requireTwoTildes(true).build(),  // "~/projects" stays a path
            TaskListItemsExtension.create(),
        )).build()
    }

    /** The cleaning of input the owner approves (a plan, PresentChat), for [parse] and [LocalMarkdownClean]. */
    val input: (String) -> String = { inputText(it, multiline = true) }

    /**
     * How deep quotes and lists nest in what is drawn. Deeper levels are drawn flat, their text kept: the agent's text
     * could nest thousands of levels, which parse fine but would overflow the stack while drawing, closing the app each
     * time that conversation opens (security scan before 1.0.0).
     */
    const val MAX_DEPTH = 8

    /** How deep inline styles (emphasis, links) nest before the rest is plain text, for the same reason. */
    const val MAX_INLINE_DEPTH = 32

    /** [text] comes cleaned with [clean], which runs again on what commonmark decodes (see [reclean]). */
    fun parse(text: String, clean: (String) -> String = ::chatText): List<Block> =
        runCatching { blocks(parser.parse(text).also { reclean(it, clean) }, 0) }.getOrElse { listOf(Block.Paragraph(AnnotatedString(text))) }

    private fun children(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()

    /** The blocks under [parent], [depth] quotes and lists deep: from [MAX_DEPTH] on, a quote or a list is drawn flat. */
    private fun blocks(parent: Node, depth: Int): List<Block> = children(parent).mapNotNull { n ->
        when {
            n !is BlockQuote && n !is BulletList && n !is OrderedList -> block(n)
            depth >= MAX_DEPTH -> plain(n).takeIf { it.isNotEmpty() }?.let { Block.Paragraph(AnnotatedString(it)) }
            n is BlockQuote -> Block.Quote(blocks(n, depth + 1))
            n is OrderedList -> Block.ListBlock(true, n.markerStartNumber ?: 1, items(n, depth + 1))
            else -> Block.ListBlock(false, 1, items(n, depth + 1))
        }
    }

    /** A block that holds no other blocks (quotes and lists are [blocks]'). */
    private fun block(node: Node): Block? = when (node) {
        is Paragraph -> Block.Paragraph(inline(node))
        is Heading -> Block.Heading(node.level, inline(node))
        is FencedCodeBlock -> Block.Code(node.info?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }, node.literal.orEmpty().trimEnd('\n'))
        is IndentedCodeBlock -> Block.Code(null, node.literal.orEmpty().trimEnd('\n'))
        is ThematicBreak -> Block.Rule
        is HtmlBlock -> Block.Paragraph(AnnotatedString(node.literal.orEmpty().trimEnd('\n')))
        is TableBlock -> table(node)
        is LinkReferenceDefinition -> null
        else -> inline(node).takeIf { it.isNotEmpty() }?.let { Block.Paragraph(it) }
    }

    private fun items(list: Node, depth: Int): List<Item> = children(list).filterIsInstance<ListItem>().map { item ->
        val marker = generateSequence(item.firstChild) { it.firstChild }.take(3).filterIsInstance<TaskListItemMarker>().firstOrNull()
        Item(marker?.isChecked, blocks(item, depth))
    }

    private fun table(node: TableBlock): Block.Table {
        val rows = mutableListOf<List<TableCell>>()
        fun collect(n: Node) { if (n is TableRow) rows += children(n).filterIsInstance<TableCell>() else children(n).forEach(::collect) }
        collect(node)
        val header = rows.firstOrNull { r -> r.any { it.isHeader } } ?: rows.firstOrNull().orEmpty()
        val body = rows.filter { it !== header }
        val alignments = header.map {
            when (it.alignment) {
                TableCell.Alignment.CENTER -> Align.CENTER
                TableCell.Alignment.RIGHT -> Align.END
                else -> Align.START
            }
        }
        return Block.Table(alignments, header.map(::inline), body.map { r -> List(header.size) { i -> r.getOrNull(i)?.let(::inline) ?: AnnotatedString("") } })
    }

    /** Inline text with its styles; plain when its styles nest past [MAX_INLINE_DEPTH]. */
    private fun inline(node: Node): AnnotatedString =
        if (nestsDeeper(node, MAX_INLINE_DEPTH)) AnnotatedString(plain(node)) else buildAnnotatedString { appendInline(node) }

    private fun AnnotatedString.Builder.appendInline(parent: Node) {
        for (n in children(parent)) when (n) {
            is TaskListItemMarker -> {}
            is Text -> append(n.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Code -> withStyle(SpanStyle(fontFamily = RobotoMono, fontSize = 13.sp, background = FbColors.surfaceSunken)) { append(n.literal) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(n) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(n) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(n) }
            is Link -> link(n.destination) { appendInline(n) }
            is Image -> link(n.destination) { appendInline(n).also { if (n.firstChild == null) append(n.destination.orEmpty()) } }
            is HtmlInline -> append(n.literal)
            is Paragraph -> { if (length > 0) append('\n'); appendInline(n) }
            else -> appendInline(n)
        }
    }

    private fun AnnotatedString.Builder.link(url: String?, content: AnnotatedString.Builder.() -> Unit) {
        if (url.isNullOrBlank()) return content()
        pushStringAnnotation(URL, url)
        withStyle(SpanStyle(color = FbColors.action, textDecoration = TextDecoration.Underline)) { content() }
        pop()
    }
}

/**
 * commonmark decodes character references ("&#x202E;"), which can bring back what the cleaning took out: [clean] runs
 * again on text, code and raw HTML, and addresses and a code block's language lose every control and format
 * character. A walk without recursion, however deep the text nests.
 */
private fun reclean(root: Node, clean: (String) -> String) = walk(root) { n ->
    when (n) {
        is Text -> n.literal = clean(n.literal)
        is Code -> n.literal = clean(n.literal)
        is FencedCodeBlock -> {
            n.literal = clean(n.literal.orEmpty())
            n.info = n.info?.let { inputText(it) }
        }
        is IndentedCodeBlock -> n.literal = clean(n.literal.orEmpty())
        is HtmlInline -> n.literal = clean(n.literal)
        is HtmlBlock -> n.literal = clean(n.literal.orEmpty())
        is Link -> n.destination = n.destination?.let { inputText(it) }
        is Image -> n.destination = n.destination?.let { inputText(it) }
    }
}

/** Every node under [root] (itself first), in document order, without recursion. */
private inline fun walk(root: Node, visit: (Node) -> Unit) {
    var n: Node? = root
    while (n != null) {
        visit(n)
        n = n.firstChild ?: run {
            var up: Node? = n
            while (up != null && up !== root && up.next == null) up = up.parent
            if (up == null || up === root) null else up.next
        }
    }
}

/** What a node writes in [plain]. */
private fun literal(n: Node): String? = when (n) {
    is Text -> n.literal
    is Code -> n.literal
    is HtmlInline -> n.literal
    is SoftLineBreak, is HardLineBreak -> "\n"
    is FencedCodeBlock -> n.literal.orEmpty()
    is IndentedCodeBlock -> n.literal.orEmpty()
    is HtmlBlock -> n.literal.orEmpty()
    else -> null
}

/** A subtree's text, flat, each block on a line of its own: what is drawn past Markdown's depth caps. */
private fun plain(node: Node): String {
    val out = StringBuilder()
    walk(node) { n ->
        if (n is org.commonmark.node.Block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
        literal(n)?.let { out.append(it) }
    }
    return out.toString().trim('\n')
}

/** Whether nodes under [node] nest more than [limit] levels below it, counted without recursion. */
private fun nestsDeeper(node: Node, limit: Int): Boolean {
    val todo = ArrayDeque(listOf(node to 0))
    while (todo.isNotEmpty()) {
        val (n, d) = todo.removeLast()
        if (d > limit) return true
        var child = n.firstChild
        while (child != null) {
            todo += child to d + 1
            child = child.next
        }
    }
    return false
}

private const val CURSOR = "cursor"

/** How [MarkdownText] cleans what commonmark decodes: the agent's words by default; [Markdown.input] for a plan. */
val LocalMarkdownClean = androidx.compose.runtime.staticCompositionLocalOf<(String) -> String> { ::chatText }

/**
 * The agent's text, with the cursor at the end while it is being written. A tapped link goes to [onLink] (a sheet
 * that shows the address first); Copy on a code block goes to [onCopy].
 */
@Composable
fun MarkdownText(text: String, color: Color = FbColors.ink, streaming: Boolean = false, modifier: Modifier = Modifier,
                 onLink: (String) -> Unit = {}, onCopy: (String) -> Unit = {}) {
    val clean = LocalMarkdownClean.current
    val blocks = remember(text, clean) { Markdown.parse(text, clean) }
    val cursor = remember {
        mapOf(CURSOR to InlineTextContent(Placeholder(10.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.padding(start = 2.dp).width(8.dp).fillMaxSize().background(FbColors.info))
        })
    }
    val draw = MarkdownDraw(FbType.body.copy(color = color), cursor, onLink, onCopy)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (blocks.isEmpty() && streaming) draw.text(AnnotatedString(""), draw.style, true)
        draw.blocks(blocks, streaming)
    }
}

private class MarkdownDraw(
    val style: TextStyle,
    val cursor: Map<String, InlineTextContent>,
    val onLink: (String) -> Unit,
    val onCopy: (String) -> Unit,
    /** How deep in nested lists: bullets are filled at the top level and hollow below it. */
    val depth: Int = 0,
) {
    /** Links become tappable here, where the callback is known. */
    fun links(t: AnnotatedString): AnnotatedString {
        val urls = t.getStringAnnotations(Markdown.URL, 0, t.length)
        if (urls.isEmpty()) return t
        return buildAnnotatedString {
            append(t)
            for (u in urls) addLink(LinkAnnotation.Clickable(u.item) { onLink(u.item) }, u.start, u.end)
        }
    }

    @Composable
    fun text(t: AnnotatedString, style: TextStyle, withCursor: Boolean, modifier: Modifier = Modifier, align: TextAlign? = null) {
        val shown = if (withCursor) buildAnnotatedString { append(links(t)); appendInlineContent(CURSOR) } else links(t)
        BasicText(shown, modifier, style = if (align != null) style.copy(textAlign = align) else style, inlineContent = cursor)
    }

    @Composable
    fun blocks(list: List<Markdown.Block>, streaming: Boolean) {
        list.forEachIndexed { index, b -> block(b, streaming && index == list.lastIndex) }
    }

    @Composable
    fun block(b: Markdown.Block, last: Boolean) {
        when (b) {
            is Markdown.Block.Paragraph -> text(b.text, style, last)
            is Markdown.Block.Heading -> text(b.text, when (b.level) {
                1 -> FbType.title.copy(color = style.color)
                2, 3 -> FbType.heading.copy(color = style.color)
                else -> style.copy(fontWeight = FontWeight.Bold)
            }, last)
            is Markdown.Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                b.items.forEachIndexed { i, item ->
                    listItem(item, if (b.ordered) "${b.start + i}." else if (depth == 0) "•" else "◦", last && i == b.items.lastIndex)
                }
            }
            is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(FbColors.line))
                Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarkdownDraw(style.copy(color = FbColors.inkMuted), cursor, onLink, onCopy, depth).blocks(b.blocks, last)
                }
            }
            is Markdown.Block.Code -> code(b, last)
            Markdown.Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(FbColors.line))
            is Markdown.Block.Table -> {
                table(b)
                if (last) text(AnnotatedString(""), style, true)
            }
        }
    }

    @Composable
    private fun listItem(item: Markdown.Item, marker: String, last: Boolean) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(22.dp).padding(top = 2.dp)) {
                when (item.checked) {
                    null -> BasicText(marker, style = style)
                    true -> Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(FbColors.success), contentAlignment = Alignment.Center) {
                        Icon(FbIcons.check, FbColors.onAction, size = 12.dp)
                    }
                    false -> Box(Modifier.size(16.dp).border(1.5.dp, FbColors.inkMuted, RoundedCornerShape(3.dp)))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MarkdownDraw(style, cursor, onLink, onCopy, depth + 1).blocks(item.blocks, last)
            }
        }
    }

    @Composable
    private fun code(b: Markdown.Block.Code, last: Boolean) {
        val l = LocalLang.current
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(FbColors.surfaceSunken)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(b.language.orEmpty(), Modifier.weight(1f), style = FbType.caption.copy(color = FbColors.inkFaint))
                Row(Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button, onClickLabel = l.t("Copy the code", "Copiar el código")) { onCopy(b.text) }
                    .padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(FbIcons.copy, FbColors.inkMuted, size = 16.dp)
                    BasicText(l.t("Copy", "Copiar"), style = FbType.label.copy(color = FbColors.inkMuted))
                }
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                val t = AnnotatedString(b.text)
                BasicText(if (last) buildAnnotatedString { append(t); appendInlineContent(CURSOR) } else t,
                    style = FbType.monoSmall.copy(color = style.color), inlineContent = cursor, softWrap = false)
            }
        }
    }

    /** Columns as wide as their widest cell; the whole table scrolls sideways when wider than the bubble. */
    @Composable
    private fun table(b: Markdown.Block.Table) {
        val cell = style.copy(lineHeight = 20.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).border(0.5.dp, FbColors.line)) {
            b.header.forEachIndexed { col, head ->
                val align = when (b.alignments.getOrNull(col)) {
                    Markdown.Align.CENTER -> TextAlign.Center
                    Markdown.Align.END -> TextAlign.End
                    else -> TextAlign.Start
                }
                Column(Modifier.width(IntrinsicSize.Max)) {
                    Box(Modifier.fillMaxWidth().background(FbColors.surfaceSunken).border(0.5.dp, FbColors.line).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        BasicText(links(head), Modifier.fillMaxWidth(), style = cell.copy(fontWeight = FontWeight.Bold, textAlign = align), softWrap = false)
                    }
                    for (row in b.rows) {
                        Box(Modifier.fillMaxWidth().border(0.5.dp, FbColors.line).padding(horizontal = 8.dp, vertical = 6.dp)) {
                            BasicText(links(row[col]), Modifier.fillMaxWidth(), style = cell.copy(textAlign = align), softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
