package dev.escriba.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.escriba.mobile.ui.theme.DarkColors
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.EscribaTheme
import dev.escriba.mobile.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Componentes del sistema de diseño en Compose. */
@RunWith(RobolectricTestRunner::class)
class ComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `un estado siempre lleva su palabra`() {
        compose.setContent { EscribaTheme(dark = false) { StatusChip("Esperando al cargador", Tone.WAIT) } }
        compose.onNodeWithText("Esperando al cargador").assertIsDisplayed()
    }

    @Test
    fun `la metrica muestra la cifra y el desglose`() {
        compose.setContent {
            EscribaTheme(dark = false) {
                MetricTile(
                    "Esta semana", "6 h 25 min",
                    listOf(MetricPart("Trabajo", Color.Green, 250, "4 h 10 min"), MetricPart("Casual", Color.Blue, 135, "2 h 15 min")),
                )
            }
        }
        compose.onNodeWithText("6 h 25 min").assertIsDisplayed()
        compose.onNodeWithText("Trabajo").assertIsDisplayed()
        compose.onNodeWithText("2 h 15 min").assertIsDisplayed()
    }

    @Test
    fun `la transcripcion muestra hora y hablante`() {
        compose.setContent {
            EscribaTheme(dark = true) {
                TranscriptBlock(listOf(TranscriptParagraph("21:11:06", "Ana López", true, "Preparo los precios.")))
            }
        }
        compose.onNodeWithText("21:11:06").assertIsDisplayed()
        compose.onNodeWithText("Ana López").assertIsDisplayed()
        compose.onNodeWithText("Preparo los precios.").assertIsDisplayed()
    }

    @Test
    fun `el chip de perfil y la fila de ajustes responden al toque`() {
        var profileTaps = 0
        var rowTaps = 0
        compose.setContent {
            EscribaTheme(dark = false) {
                Column {
                    ProfileChip("Trabajo", Color.Green, onClick = { profileTaps++ })
                    SettingRow(Icons.Rounded.Settings, "Modelos", "11 modelos", onClick = { rowTaps++ })
                }
            }
        }
        compose.onNodeWithText("Trabajo").performClick()
        compose.onNodeWithText("Modelos").performClick()
        assertEquals(1, profileTaps)
        assertEquals(1, rowTaps)
    }

    @Test
    fun `el tema claro y el oscuro dan sus colores`() {
        var light: Color? = null
        var dark: Color? = null
        compose.setContent {
            EscribaTheme(dark = false) { light = Escriba.colors.paper }
            EscribaTheme(dark = true) { dark = Escriba.colors.paper }
        }
        compose.waitForIdle()
        assertEquals(LightColors.paper, light)
        assertEquals(DarkColors.paper, dark)
    }

    @Test
    fun `los colores de la boveda se traducen a la paleta de perfiles`() {
        // #2E7D32 (el verde por defecto de Material) cae en «pino»; un color raro, en el más cercano.
        assertEquals(LightColors.profiles[0], LightColors.profileColor("#2E7D32"))
        assertEquals(DarkColors.profiles[0], DarkColors.profileColor("#2E7D32"))
        assertEquals(LightColors.profiles[1], LightColors.profileColor("#1565C0"))
        assertEquals(LightColors.inkMuted, LightColors.profileColor("no es un color"))
    }
}
