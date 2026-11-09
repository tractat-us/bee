package us.tractat.bee.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import us.tractat.bee.core.BoardClient
import us.tractat.bee.core.HostAddress

/** What the app shows: the one field that asks for a board, or the board it joined. */
sealed interface AppScreen {
    /** No board yet. [problem] says why the last link given wasn't a board's, if one wasn't. */
    data class AskForBoard(val problem: String? = null) : AppScreen

    /** The board the app joined, the whole screen. */
    class OnBoard(val board: BoardClient) : AppScreen
}

/**
 * The bee app: the board is the whole screen (product invariant 1). It starts on [start], and a
 * link typed into the field is [join]ed. A link that names no host is refused with
 * [HostAddress.parse]'s reason, and the field stays.
 */
@Composable
fun BeeApp(start: AppScreen, join: (HostAddress) -> BoardClient) {
    var screen by remember { mutableStateOf(start) }
    Box(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().padding(24.dp)) {
        when (val now = screen) {
            is AppScreen.AskForBoard -> AskForBoard(now.problem) { link -> screen = screenFor(link, join) }
            is AppScreen.OnBoard -> Board(now.board)
        }
    }
}

/** The screen [link] leads to: its board, [join]ed, or the field again with why it names no host. */
fun screenFor(link: String, join: (HostAddress) -> BoardClient): AppScreen = try {
    AppScreen.OnBoard(join(HostAddress.parse(link)))
} catch (unusable: IllegalArgumentException) {
    AppScreen.AskForBoard(unusable.message)
}

/** The board as [board] holds it: for now, its open surfaces' titles as plain text (#156). */
@Composable
fun Board(board: BoardClient) {
    val state by board.state.collectAsState()
    SurfaceTitles(state.openSurfaceTitles())
}

/** [titles], one per line, or the one quiet hint an empty board shows. */
@Composable
fun SurfaceTitles(titles: List<String>) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (titles.isEmpty()) BasicText("Nothing on the board yet.", style = Palette.quiet)
        titles.forEach { BasicText(it, Modifier.testTag(SURFACE_TITLE_TAG), style = Palette.title) }
    }
}

/** The tag each surface title carries, for the UI tests. */
const val SURFACE_TITLE_TAG: String = "surface-title"

/** The one field: paste a board's link, or its join link, and press Go. */
@Composable
private fun AskForBoard(problem: String?, onLink: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Board link", style = Palette.quiet)
        BasicTextField(
            value = link,
            onValueChange = { link = it },
            modifier = Modifier
                .fillMaxWidth()
                // A desktop keyboard's Enter is Go. Consumed here, so the IME action can't join twice.
                .onPreviewKeyEvent { event ->
                    (event.key == Key.Enter && event.type == KeyEventType.KeyDown).also { if (it) onLink(link) }
                }
                .border(1.dp, Palette.line)
                .background(Palette.card)
                .padding(12.dp),
            textStyle = Palette.body,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onLink(link) }),
        )
        problem?.let { BasicText(it, style = Palette.alert) }
    }
}

/** bee's page palette (bee-web's index.html), light only for now. */
private object Palette {
    val bg = Color(0xFFF6F5F2)
    val card = Color(0xFFFFFFFF)
    val ink = Color(0xFF1F2328)
    val muted = Color(0xFF656D76)
    val line = Color(0xFFD8D6D0)
    val alertInk = Color(0xFFB42318)

    val body = TextStyle(color = ink, fontSize = 16.sp)
    val title = TextStyle(color = ink, fontSize = 20.sp)
    val quiet = TextStyle(color = muted, fontSize = 14.sp)
    val alert = TextStyle(color = alertInk, fontSize = 14.sp)
}
