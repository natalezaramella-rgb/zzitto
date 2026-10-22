package com.zaramella.zzitto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zaramella.zzitto.R
import com.zaramella.zzitto.core.licensing.LicenseState
import com.zaramella.zzitto.core.licensing.PurchaseOutcome
import com.zaramella.zzitto.ui.licenses.LicenseBlock
import com.zaramella.zzitto.ui.licenses.LicensesText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Licenze open source (Spec §2.2) e acquisto (Spec §8.1). Aggiunto il 22/10/2026 alle 11:59.
 */

/** Stato di caricamento del file delle licenze. */
private sealed interface LicensesLoad {
    data object Loading : LicensesLoad
    data object Missing : LicensesLoad
    data class Loaded(val blocks: List<LicenseBlock>) : LicensesLoad
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var load by remember { mutableStateOf<LicensesLoad>(LicensesLoad.Loading) }
    LaunchedEffect(Unit) {
        load = withContext(Dispatchers.IO) {
            LicensesText.load(context.assets)?.let { LicensesLoad.Loaded(LicensesText.parse(it)) } ?: LicensesLoad.Missing
        }
    }
    ZzScaffold(stringResource(R.string.screen_licenses), stringResource(R.string.help_licenses), onBack) { padding ->
        when (val l = load) {
            LicensesLoad.Loading -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) { CircularProgressIndicator() }
            LicensesLoad.Missing -> Text(
                stringResource(R.string.licenses_missing),
                modifier = Modifier.padding(padding).padding(16.dp).testTag("licensesMissing"),
            )
            is LicensesLoad.Loaded -> LicensesList(l.blocks, padding)
        }
    }
}

@Composable
fun LicensesList(blocks: List<LicenseBlock>, padding: PaddingValues) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).testTag("licensesList"),
        contentPadding = PaddingValues(16.dp),
    ) {
        items(blocks) { block ->
            when (block) {
                is LicenseBlock.Heading -> Text(
                    block.text,
                    style = if (block.level <= 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                is LicenseBlock.Bullet -> Text("• " + block.text, modifier = Modifier.padding(vertical = 2.dp))
                is LicenseBlock.Paragraph -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

/** Spec §8.1: prova di 30 giorni, acquisto una tantum, ripristino dopo reinstallazione. */
@Composable
fun PurchaseScreen(
    state: LicenseState,
    busy: Boolean,
    outcome: PurchaseOutcome?,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
) {
    ZzScaffold(stringResource(R.string.screen_purchase), stringResource(R.string.help_purchase), onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(licenseText(state), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("licenseState"))
            Text(stringResource(R.string.purchase_body), modifier = Modifier.padding(vertical = 12.dp))
            if (state != LicenseState.Purchased) {
                Button(
                    onClick = onPurchase,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("purchaseButton"),
                ) { Text(stringResource(R.string.purchase_buy)) }
                OutlinedButton(
                    onClick = onRestore,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(stringResource(R.string.purchase_restore)) }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(top = 16.dp))
            outcome?.let {
                Text(
                    stringResource(
                        when (it) {
                            PurchaseOutcome.Purchased -> R.string.purchase_outcome_ok
                            PurchaseOutcome.AlreadyOwned -> R.string.purchase_outcome_owned
                            PurchaseOutcome.Pending -> R.string.purchase_outcome_pending
                            PurchaseOutcome.Cancelled -> R.string.purchase_outcome_cancelled
                            PurchaseOutcome.Error -> R.string.purchase_outcome_error
                        },
                    ),
                    modifier = Modifier.padding(top = 16.dp).testTag("purchaseOutcome"),
                )
            }
            Text(stringResource(R.string.purchase_privacy), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 24.dp))
        }
    }
}
