package com.zaramella.zzitto

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.core.licensing.PurchaseOutcome
import com.zaramella.zzitto.dictation.settings.DictationSettings
import com.zaramella.zzitto.engine.ModelInstallState
import com.zaramella.zzitto.ui.DictionaryScreen
import com.zaramella.zzitto.ui.HistoryScreen
import com.zaramella.zzitto.ui.PurchaseScreen
import com.zaramella.zzitto.ui.SetupActions
import com.zaramella.zzitto.ui.SystemStatus
import com.zaramella.zzitto.ui.TourScreen
import com.zaramella.zzitto.ui.ZzittoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test UI Compose delle schermate (Spec §12). Girano su dispositivo con `./gradlew connectedCheck`.
 * Aggiunto il 22/10/2026 alle 11:59.
 */
@RunWith(AndroidJUnit4::class)
class ScreensUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val noActions = SetupActions({}, {}, {}, {})

    @Test
    fun tourAvanzaFinoAllaFineConInformativaAccessibilita() {
        var finished = false
        var accessibilityOpened = 0
        compose.setContent {
            ZzittoTheme {
                TourScreen(
                    status = SystemStatus(accessibilityEnabled = false, microphoneGranted = false, batteryUnrestricted = false),
                    modelState = ModelInstallState.Idle,
                    modelsInstalled = false,
                    actions = SetupActions({ accessibilityOpened++ }, {}, {}, {}),
                    onFinish = { finished = true },
                )
            }
        }
        compose.onNodeWithTag("tourNext").performClick()
        // Spec §5.1-bis: informativa ben visibile prima di aprire le impostazioni.
        compose.onNodeWithTag("accessibilityDisclosure").assertIsDisplayed()
        compose.onNodeWithTag("disclosureDecline").performClick()
        assertEquals(0, accessibilityOpened)
        compose.onNodeWithTag("disclosureReopen").performClick()
        compose.onNodeWithTag("disclosureAccept").performClick()
        assertEquals(1, accessibilityOpened)
        repeat(4) { compose.onNodeWithTag("tourNext").performClick() }
        compose.onNodeWithTag("tourFinish").performClick()
        assertTrue(finished)
    }

    @Test
    fun guidaContestualeSiApre() {
        compose.setContent {
            ZzittoTheme {
                DictionaryScreen(emptyList(), null, {}, {}, {})
            }
        }
        compose.onNodeWithTag("help").performClick()
        compose.onNodeWithTag("helpDialog").assertIsDisplayed()
    }

    @Test
    fun dizionarioAggiungeParola() {
        val added = mutableListOf<String>()
        compose.setContent {
            ZzittoTheme {
                DictionaryScreen(emptyList(), null, { added += it }, {}, {})
            }
        }
        compose.onNodeWithTag("wordInput").performTextInput("Zaramella")
        compose.onNodeWithTag("wordAdd").performClick()
        assertEquals(listOf("Zaramella"), added)
    }

    @Test
    fun cronologiaVuotaNonPermetteCancellaTutto() {
        compose.setContent {
            ZzittoTheme {
                HistoryScreen(DictationSettings(), emptyList(), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("historyDeleteAll").assertIsNotEnabled()
    }

    @Test
    fun acquistoMostraStatoEPulsanti() {
        var bought = 0
        compose.setContent {
            ZzittoTheme {
                PurchaseScreen(LicenseState.TrialExpired, busy = false, outcome = PurchaseOutcome.Cancelled, onPurchase = { bought++ }, onRestore = {}, onBack = {})
            }
        }
        compose.onNodeWithTag("purchaseButton").performClick()
        assertEquals(1, bought)
        compose.onNodeWithTag("purchaseOutcome").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.purchase_restore)).assertExists()
        compose.onNodeWithTag("licenseState").assertTextContains(context.getString(R.string.license_expired))
    }
}
