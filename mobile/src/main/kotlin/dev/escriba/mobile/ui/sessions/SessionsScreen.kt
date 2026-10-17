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
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.ui.components.EmptyState
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.ui.today.ConversationRow
import dev.escriba.mobile.ui.today.ES
import dev.escriba.mobile.ui.today.local
import dev.escriba.mobile.ui.today.rememberConversations
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES)

/** Todas las conversaciones, agrupadas por día, de la más reciente a la más antigua. */
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
                    "Aún no hay sesiones",
                    "Cuando el reloj grabe algo y lo envíe, cada conversación aparecerá aquí con su hora, su perfil y su estado.",
                )
            }
        }
        byDay.forEach { (day, list) ->
            item(key = "dia-$day") {
                val label = when (day) {
                    today -> "Hoy"
                    today.minusDays(1) -> "Ayer"
                    else -> day.format(DAY)
                }
                Overline(label, Modifier.padding(top = Space.s3))
            }
            item(key = "lista-$day") {
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
