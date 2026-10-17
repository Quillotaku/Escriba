package dev.escriba.mobile.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.escriba.mobile.R
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.ui.components.EmptyState
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.ui.today.ConversationRow
import dev.escriba.mobile.ui.today.APP_LOCALE
import dev.escriba.mobile.ui.today.local
import dev.escriba.mobile.ui.today.rememberConversations
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", APP_LOCALE)

/** Every conversation, grouped by day, from the most recent to the oldest. */
@Composable
fun SessionsScreen(app: EscribaApp, padding: PaddingValues, openSession: (String, Int) -> Unit) {
    val conversations = rememberConversations(app, 0L)
    val byDay = remember(conversations) { conversations.groupBy { it.startMs.local().toLocalDate() } }
    val today = LocalDate.now()
    val c = Escriba.colors

    LazyColumn(
        contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = padding.calculateTopPadding() + Space.s2, bottom = padding.calculateBottomPadding() + Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        if (byDay.isEmpty()) {
            item {
                EmptyState(
                    stringResource(R.string.sessions_empty_title),
                    stringResource(R.string.sessions_empty_body),
                )
            }
        }
        byDay.forEach { (day, list) ->
            item(key = "day-$day") {
                val label = when (day) {
                    today -> stringResource(R.string.tab_today)
                    today.minusDays(1) -> stringResource(R.string.yesterday)
                    else -> day.format(DAY)
                }
                Overline(label, Modifier.padding(top = Space.s3))
            }
            item(key = "list-$day") {
                EsCard {
                    list.forEachIndexed { i, conv ->
                        if (i > 0) HorizontalDivider(color = c.rule)
                        ConversationRow(conv) { openSession(conv.sessionId, conv.index) }
                    }
                }
            }
        }
    }
}
