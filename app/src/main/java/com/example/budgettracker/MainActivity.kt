package com.example.budgettracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.budgettracker.data.ShopLocation
import com.example.budgettracker.receipt.ProcessorConfig
import com.example.budgettracker.ui.*
import com.example.budgettracker.ui.theme.BackgroundDark
import com.example.budgettracker.ui.theme.BudgetTrackerTheme
import com.example.budgettracker.ui.theme.CardDark
import com.example.budgettracker.ui.theme.Negative
import com.example.budgettracker.ui.theme.Positive
import java.time.YearMonth

private sealed class Screen {
    object Main : Screen()
    object History : Screen()
    object Settings : Screen()
    object ShopManagement : Screen()
    object ProcessorManagement : Screen()
    object Changelog : Screen()
    object CommonNames : Screen()
    data class CommonNameDetail(val commonNameId: Long) : Screen()
    object Categories : Screen()
    /** Receipt import/review pipeline — driven by viewModel.receiptImportState */
    object ReceiptFlow : Screen()
    /** fromMonth = null when navigated from MainScreen (back goes to Main) */
    data class ReceiptDetail(val expenseId: Long, val fromMonth: YearMonth?) : Screen()
    data class MonthDetail(val yearMonth: YearMonth) : Screen()
    /** shopId = shop to attach the location to; existingLocation = null for add, non-null for edit */
    data class MapPicker(val shopId: Long, val existingLocation: ShopLocation?) : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture share intent data before entering Compose
        val sharedUri: Uri? = if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else null
        val sharedMime: String = intent?.type ?: ""

        setContent {
            BudgetTrackerTheme {
                BudgetApp(
                    initialSharedUri = sharedUri,
                    initialSharedMime = sharedMime
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-launch when the app is already running and a new share arrives
        setIntent(intent)
    }
}

@Composable
private fun BudgetApp(
    initialSharedUri: Uri? = null,
    initialSharedMime: String = ""
) {
    val viewModel: BudgetViewModel = viewModel()
    var screen by remember {
        mutableStateOf<Screen>(Screen.Main)
    }

    // Handle a shared receipt URI on first composition
    LaunchedEffect(initialSharedUri) {
        if (initialSharedUri != null &&
            (initialSharedMime.startsWith("image/") || initialSharedMime == "application/pdf")
        ) {
            viewModel.startReceiptImport(initialSharedUri, initialSharedMime)
            screen = Screen.ReceiptFlow
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        when (val current = screen) {

            is Screen.Main -> MainScreen(
                viewModel = viewModel,
                onOpenHistory = { screen = Screen.History },
                onOpenSettings = { screen = Screen.Settings },
                onOpenReceiptDetail = { expenseId ->
                    screen = Screen.ReceiptDetail(expenseId, fromMonth = null)
                }
            )

            is Screen.History -> HistoryScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Main },
                onOpenMonth = { month -> screen = Screen.MonthDetail(month) }
            )

            is Screen.Settings -> SettingsScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Main },
                onOpenShopManagement = { screen = Screen.ShopManagement },
                onOpenProcessorManagement = { screen = Screen.ProcessorManagement },
                onOpenCommonNames = { screen = Screen.CommonNames },
                onOpenCategories = { screen = Screen.Categories },
                onOpenChangelog = { screen = Screen.Changelog }
            )

            is Screen.Changelog -> ChangelogScreen(
                onBack = { screen = Screen.Settings }
            )

            is Screen.CommonNames -> CommonNamesScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Settings },
                onOpen = { id -> screen = Screen.CommonNameDetail(id) }
            )

            is Screen.CommonNameDetail -> CommonNameDetailScreen(
                viewModel = viewModel,
                commonNameId = current.commonNameId,
                onBack = { screen = Screen.CommonNames }
            )

            is Screen.Categories -> CategoriesScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Settings }
            )

            is Screen.ShopManagement -> ShopManagementScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Settings },
                onPickLocation = { shopId, existing ->
                    screen = Screen.MapPicker(shopId, existing)
                }
            )

            is Screen.ProcessorManagement -> ProcessorManagementScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Settings }
            )

            is Screen.MapPicker -> MapPickerScreen(
                existingLocation = current.existingLocation,
                mapStyle = viewModel.mapStyle.collectAsState().value,
                onBack = { screen = Screen.ShopManagement },
                onConfirm = { lat, lng, label ->
                    if (current.existingLocation != null) {
                        viewModel.updateShopLocation(
                            current.existingLocation.copy(
                                latitude = lat,
                                longitude = lng,
                                label = label
                            )
                        )
                    } else {
                        viewModel.addShopLocation(current.shopId, lat, lng, label)
                    }
                    screen = Screen.ShopManagement
                }
            )

            is Screen.MonthDetail -> MonthDetailScreen(
                viewModel = viewModel,
                yearMonth = current.yearMonth,
                onBack = { screen = Screen.History },
                onOpenReceiptDetail = { expenseId ->
                    screen = Screen.ReceiptDetail(expenseId, current.yearMonth)
                }
            )

            is Screen.ReceiptDetail -> ReceiptDetailScreen(
                expenseId = current.expenseId,
                viewModel = viewModel,
                onBack = {
                    val fromMonth = current.fromMonth
                    screen = if (fromMonth != null) Screen.MonthDetail(fromMonth) else Screen.Main
                }
            )

            is Screen.ReceiptFlow -> ReceiptFlowHost(
                viewModel = viewModel,
                onDone = { screen = Screen.Main },
                onBack = {
                    viewModel.resetReceiptImport()
                    screen = Screen.Main
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// ReceiptFlowHost — shows the right composable based on receiptImportState
// ---------------------------------------------------------------------------

@Composable
private fun ReceiptFlowHost(
    viewModel: BudgetViewModel,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val importState by viewModel.receiptImportState.collectAsState()
    val llmApiKey by viewModel.llmApiKey.collectAsState()

    when (val state = importState) {

        is ReceiptImportState.Idle -> {
            // State reset — navigate out
            LaunchedEffect(Unit) { onDone() }
        }

        is ReceiptImportState.Parsing -> {
            ReceiptLoadingScreen(message = "Parsing receipt…", onCancel = onBack)
        }

        is ReceiptImportState.LlmGenerating -> {
            ReceiptLoadingScreen(message = "Generating processor with LLM…", onCancel = onBack)
        }

        is ReceiptImportState.NeedsProcessor -> {
            // Show a neutral background + the processor selector dialog.
            // When a processor was auto-detected it is pre-highlighted in the dialog;
            // the user can confirm it or choose a different one.
            val message = if (state.preselectedProcessor != null)
                "Detected: ${state.preselectedProcessor.name}"
            else
                "Select a processor"
            ReceiptLoadingScreen(message = message, onCancel = onBack)
            SelectProcessorDialog(
                doc = state.doc,
                detectedShop = state.detectedShop,
                processors = state.availableProcessors,
                llmApiKeySet = llmApiKey.isNotBlank(),
                preselectedProcessor = state.preselectedProcessor,
                onSelect = { config: ProcessorConfig ->
                    viewModel.applyProcessor(state.doc, config, state.detectedShop)
                },
                onGenerateWithLlm = {
                    viewModel.generateProcessorWithLlm(state.doc, state.detectedShop)
                },
                onCopyPrompt = {
                    viewModel.copyLlmPrompt(state.doc, state.detectedShop)
                },
                onCopyJson = {
                    viewModel.copyLevel0Json(state.doc)
                },
                onDismiss = onBack
            )
        }

        is ReceiptImportState.Review -> {
            ReceiptReviewScreen(
                doc = state.doc,
                parsed = state.parsed,
                processorName = state.processorName,
                detectedShop = state.detectedShop,
                viewModel = viewModel,
                onBack = onBack,
                onConfirmed = onDone
            )
        }

        is ReceiptImportState.Error -> {
            ReceiptErrorScreen(message = state.message, onBack = onBack)
        }
    }
}

// ---------------------------------------------------------------------------
// Simple helper screens
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptLoadingScreen(
    message: String,
    onCancel: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Receipt", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { pv ->
        Box(Modifier.fillMaxSize().padding(pv), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(color = Positive)
                Text(message, color = Color(0xFF888888), fontSize = 14.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptErrorScreen(
    message: String,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Error", color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { pv ->
        Box(Modifier.fillMaxSize().padding(pv).padding(24.dp), contentAlignment = Alignment.Center) {
            Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Receipt parsing failed", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(message, color = Color(0xFFAAAAAA), fontSize = 13.sp, lineHeight = 20.sp)
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = Negative)
                    ) {
                        Text("Go back", color = Color.White)
                    }
                }
            }
        }
    }
}
