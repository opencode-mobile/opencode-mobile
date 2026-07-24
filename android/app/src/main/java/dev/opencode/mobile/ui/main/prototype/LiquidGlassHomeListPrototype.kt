package dev.opencode.mobile.ui.main.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencode.mobile.theme.LetterAccent
import dev.opencode.mobile.theme.LocalOpenCodeDarkTheme
import dev.opencode.mobile.theme.OpenCodeTheme
import dev.opencode.mobile.theme.badgeColors
import dev.opencode.mobile.theme.openCodeColor
import dev.opencode.mobile.ui.components.OpenCodeSessionConnectionStatus
import dev.opencode.mobile.ui.components.SessionListFilterTab
import dev.opencode.mobile.ui.components.SessionListSession
import dev.opencode.mobile.ui.components.SessionListStatus
import dev.opencode.mobile.ui.icons.OpenCodeIcons

// PROTOTYPE — throwaway branch prototype/liquid-glass
// Android parity of iOS C: dense list + floating bottom glass-like dock.

private val DockCornerRadius = 26.dp
private val GlassButtonShape = RoundedCornerShape(22.dp)
private val ChipShape = RoundedCornerShape(999.dp)

/** Fade starts this far above the dock card top (iOS parity). */
private val ListBottomFadeOverhang = 16.dp

@Composable
fun LiquidGlassHomeListPrototype(
    sessions: List<SessionListSession>,
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    onFilterSelected: (String) -> Unit,
    onSessionSelected: (SessionListSession) -> Unit,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    createError: String? = null,
    liveStatus: OpenCodeSessionConnectionStatus? = null,
) {
    val visibleSessions =
        remember(sessions, selectedFilterId) {
            if (selectedFilterId == SessionListFilterTab.ALL_ID) {
                sessions
            } else {
                sessions.filter { it.worktree == selectedFilterId }
            }
        }

    val background = openCodeColor("background-base")
    val textStrong = openCodeColor("text-strong")
    val textBase = openCodeColor("text-base")
    val textWeaker = openCodeColor("text-weaker")
    val borderWeaker = openCodeColor("border-weaker-base")
    val surfaceRaised = openCodeColor("surface-raised-base")
    val borderWeak = openCodeColor("border-weak-base")
    val interactive = openCodeColor("interactive")
    val error = openCodeColor("error")
    val iconBase = openCodeColor("icon-base")
    val success = openCodeColor("success")
    val warning = openCodeColor("warning")
    val isDark = LocalOpenCodeDarkTheme.current
    val density = LocalDensity.current

    // Full dock block height (card + outer padding + nav-bar inset), screen-bottom anchored.
    var dockBlockHeight by remember { mutableStateOf(0.dp) }
    val listBottomFadeHeight =
        if (dockBlockHeight > 0.dp) {
            dockBlockHeight + ListBottomFadeOverhang
        } else {
            // Fallback before first measure (approx. card + padding + nav).
            168.dp
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PrototypeHeader(
                liveStatus = liveStatus,
                textStrong = textStrong,
                iconBase = iconBase,
                surfaceRaised = surfaceRaised,
                success = success,
                warning = warning,
                textWeaker = textWeaker,
                onSettings = onSettings,
            )

            LazyColumn(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = listBottomFadeHeight),
            ) {
                itemsIndexed(visibleSessions, key = { _, session -> session.id }) { index, session ->
                    DenseSessionRow(
                        session = session,
                        textStrong = textStrong,
                        textBase = textBase,
                        textWeaker = textWeaker,
                        surfaceRaised = surfaceRaised,
                        interactive = interactive,
                        isDark = isDark,
                        onClick = { onSessionSelected(session) },
                    )
                    if (index < visibleSessions.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 60.dp),
                            color = borderWeaker,
                        )
                    }
                }
            }
        }

        // Between list and dock. Top = card top + 16dp; bottom = physical screen edge.
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(listBottomFadeHeight)
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0f to background.copy(alpha = 0f),
                                    0.5f to background.copy(alpha = 0.7f),
                                    1f to background.copy(alpha = 0.7f),
                                ),
                        ),
                    ),
        )

        FloatingGlassDock(
            filterTabs = filterTabs,
            selectedFilterId = selectedFilterId,
            createError = createError,
            textStrong = textStrong,
            textBase = textBase,
            textWeaker = textWeaker,
            surfaceRaised = surfaceRaised,
            borderWeak = borderWeak,
            iconBase = iconBase,
            error = error,
            onFilterSelected = onFilterSelected,
            onAddProject = onAddProject,
            onNewSession = onNewSession,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val height: Dp = with(density) { coordinates.size.height.toDp() }
                        if (height != dockBlockHeight) {
                            dockBlockHeight = height
                        }
                    }
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PrototypeHeader(
    liveStatus: OpenCodeSessionConnectionStatus?,
    textStrong: Color,
    iconBase: Color,
    surfaceRaised: Color,
    success: Color,
    warning: Color,
    textWeaker: Color,
    onSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sessions",
            color = textStrong,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (liveStatus != null) {
            Box(
                modifier =
                    Modifier
                        .padding(end = 10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor(liveStatus, success, warning, textWeaker)),
            )
        }
        Surface(
            shape = CircleShape,
            color = surfaceRaised.copy(alpha = 0.92f),
            modifier = Modifier.size(40.dp),
        ) {
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = OpenCodeIcons.Settings,
                    contentDescription = "Settings",
                    tint = iconBase,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun DenseSessionRow(
    session: SessionListSession,
    textStrong: Color,
    textBase: Color,
    textWeaker: Color,
    surfaceRaised: Color,
    interactive: Color,
    isDark: Boolean,
    onClick: () -> Unit,
) {
    val (badgeFill, badgeTint) = session.letterAccent.badgeColors(isDark)
    val running = session.status == SessionListStatus.Running

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(badgeFill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = session.letter.toString(),
                color = badgeTint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title,
                color = textStrong,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = compactHomePath(session.subtitle),
                color = textBase,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            if (session.caption.isNotEmpty()) {
                Text(
                    text = session.caption,
                    color = textWeaker,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            Text(
                text = if (running) "Running" else "Idle",
                color = if (running) interactive else textWeaker,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clip(ChipShape)
                        .background(
                            if (running) interactive.copy(alpha = 0.12f) else surfaceRaised,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FloatingGlassDock(
    filterTabs: List<SessionListFilterTab>,
    selectedFilterId: String,
    createError: String?,
    textStrong: Color,
    textBase: Color,
    textWeaker: Color,
    surfaceRaised: Color,
    borderWeak: Color,
    iconBase: Color,
    error: Color,
    onFilterSelected: (String) -> Unit,
    onAddProject: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Raised surface approximates iOS glass underlay (surfaceRaised @ 0.92).
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(DockCornerRadius),
        color = surfaceRaised.copy(alpha = 0.92f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            createError?.let {
                Text(
                    text = it,
                    color = error,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filterTabs.forEach { tab ->
                    FilterChip(
                        title = tab.title,
                        count = tab.count,
                        selected = tab.id == selectedFilterId,
                        textStrong = textStrong,
                        textBase = textBase,
                        textWeaker = textWeaker,
                        surfaceRaised = surfaceRaised,
                        borderWeak = borderWeak,
                        onClick = { onFilterSelected(tab.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCta(
                    onClick = onAddProject,
                    borderWeak = borderWeak,
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = OpenCodeIcons.Folder,
                        contentDescription = "Add project",
                        tint = iconBase,
                        modifier = Modifier.size(22.dp),
                    )
                }
                GlassCta(
                    onClick = onNewSession,
                    borderWeak = borderWeak,
                    shape = GlassButtonShape,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(52.dp),
                ) {
                    Text(
                        text = "New session",
                        color = textStrong,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    title: String,
    count: Int,
    selected: Boolean,
    textStrong: Color,
    textBase: Color,
    textWeaker: Color,
    surfaceRaised: Color,
    borderWeak: Color,
    onClick: () -> Unit,
) {
    val labelColor = if (selected) textStrong else textWeaker
    val fill =
        if (selected) {
            textStrong.copy(alpha = 0.14f)
        } else {
            Color.Transparent
        }
    Row(
        modifier =
            Modifier
                .semantics { this.selected = selected }
                .clip(ChipShape)
                .background(fill)
                .border(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) borderWeak else borderWeak.copy(alpha = 0.55f),
                    shape = ChipShape,
                )
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            color = labelColor,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
            maxLines = 1,
        )
        Text(
            text = count.toString(),
            color = if (selected) textStrong else textBase,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            modifier =
                Modifier
                    .clip(ChipShape)
                    .background(textStrong.copy(alpha = if (selected) 0.16f else 0.06f))
                    .padding(
                        horizontal = if (count >= 10) 8.dp else 6.dp,
                        vertical = 2.dp,
                    )
                    .then(
                        if (count >= 100) {
                            Modifier.widthIn(min = 34.dp)
                        } else if (count >= 10) {
                            Modifier.widthIn(min = 28.dp)
                        } else {
                            Modifier.widthIn(min = 22.dp)
                        },
                    ),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GlassCta(
    onClick: () -> Unit,
    borderWeak: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Transparent fill + visible stroke so CTAs read against the dock surface.
    Box(
        modifier =
            modifier
                .clip(shape)
                .border(width = 1.5.dp, color = borderWeak, shape = shape)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun statusDotColor(
    status: OpenCodeSessionConnectionStatus,
    success: Color,
    warning: Color,
    weaker: Color,
): Color =
    when (status) {
        OpenCodeSessionConnectionStatus.Live -> success
        OpenCodeSessionConnectionStatus.Connecting -> warning
        OpenCodeSessionConnectionStatus.Offline -> weaker
    }

private fun compactHomePath(path: String): String {
    val home = System.getProperty("user.home").orEmpty()
    if (home.isEmpty()) return path
    if (path == home) return "~"
    if (path.startsWith("$home/")) return "~" + path.removePrefix(home)
    return path
}

@Preview(showBackground = true)
@Composable
private fun LiquidGlassHomeListPrototypePreview() {
    OpenCodeTheme(darkTheme = false) {
        LiquidGlassHomeListPrototype(
            sessions =
                listOf(
                    SessionListSession(
                        id = "1",
                        title = "Refactor Navigation",
                        worktree = "/Users/demo/opencode-mobile",
                        letter = 'O',
                        letterAccent = LetterAccent.Violet,
                        subtitle = "/Users/demo/opencode-mobile",
                        status = SessionListStatus.Running,
                        caption = "now",
                    ),
                    SessionListSession(
                        id = "2",
                        title = "Bug: Header Clipping",
                        worktree = "/Users/demo/playground",
                        letter = 'P',
                        letterAccent = LetterAccent.Teal,
                        subtitle = "/Users/demo/playground",
                        status = SessionListStatus.Idle,
                        caption = "14m ago",
                    ),
                    SessionListSession(
                        id = "3",
                        title = "Wire session abort",
                        worktree = "/Users/demo/opencode-mobile",
                        letter = 'O',
                        letterAccent = LetterAccent.Violet,
                        subtitle = "/Users/demo/opencode-mobile",
                        status = SessionListStatus.Idle,
                        caption = "1h ago",
                    ),
                ),
            filterTabs =
                listOf(
                    SessionListFilterTab(id = SessionListFilterTab.ALL_ID, title = "All", count = 3),
                    SessionListFilterTab(
                        id = "/Users/demo/opencode-mobile",
                        title = "opencode-mobile",
                        count = 2,
                    ),
                    SessionListFilterTab(id = "/Users/demo/playground", title = "playground", count = 1),
                ),
            selectedFilterId = SessionListFilterTab.ALL_ID,
            liveStatus = OpenCodeSessionConnectionStatus.Live,
            onFilterSelected = {},
            onSessionSelected = {},
            onAddProject = {},
            onNewSession = {},
            onSettings = {},
        )
    }
}
