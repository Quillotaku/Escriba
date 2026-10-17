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

/** The design system's Compose components. */
@RunWith(RobolectricTestRunner::class)
class ComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a state always carries its word`() {
        compose.setContent { EscribaTheme(dark = false) { StatusChip("Waiting for the charger", Tone.WAIT) } }
        compose.onNodeWithText("Waiting for the charger").assertIsDisplayed()
    }

    @Test
    fun `the metric shows the figure and the breakdown`() {
        compose.setContent {
            EscribaTheme(dark = false) {
                MetricTile(
                    "This week", "6 h 25 min",
                    listOf(MetricPart("Work", Color.Green, 250, "4 h 10 min"), MetricPart("Personal", Color.Blue, 135, "2 h 15 min")),
                )
            }
        }
        compose.onNodeWithText("6 h 25 min").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("2 h 15 min").assertIsDisplayed()
    }

    @Test
    fun `the transcript shows time and speaker`() {
        compose.setContent {
            EscribaTheme(dark = true) {
                TranscriptBlock(listOf(TranscriptParagraph("21:11:06", "Ana López", true, "I'll prepare the prices.")))
            }
        }
        compose.onNodeWithText("21:11:06").assertIsDisplayed()
        compose.onNodeWithText("Ana López").assertIsDisplayed()
        compose.onNodeWithText("I'll prepare the prices.").assertIsDisplayed()
    }

    @Test
    fun `the profile chip and the settings row respond to taps`() {
        var profileTaps = 0
        var rowTaps = 0
        compose.setContent {
            EscribaTheme(dark = false) {
                Column {
                    ProfileChip("Work", Color.Green, onClick = { profileTaps++ })
                    SettingRow(Icons.Rounded.Settings, "Models", "11 models", onClick = { rowTaps++ })
                }
            }
        }
        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("Models").performClick()
        assertEquals(1, profileTaps)
        assertEquals(1, rowTaps)
    }

    @Test
    fun `the light and dark themes give their colours`() {
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
    fun `vault colours map to the profile palette`() {
        // #2E7D32 (Material's default green) lands on "pine"; an odd colour, on the closest one.
        assertEquals(LightColors.profiles[0], LightColors.profileColor("#2E7D32"))
        assertEquals(DarkColors.profiles[0], DarkColors.profileColor("#2E7D32"))
        assertEquals(LightColors.profiles[1], LightColors.profileColor("#1565C0"))
        assertEquals(LightColors.inkMuted, LightColors.profileColor("not a colour"))
    }
}
