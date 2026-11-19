package com.cadence.shifts.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cadence.shifts.R
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.repository.CadenceRepository
import com.cadence.shifts.notifications.BackgroundSyncScheduler
import com.cadence.shifts.ui.auth.AuthScreen
import com.cadence.shifts.ui.components.CadenceCard
import com.cadence.shifts.ui.screens.ManagerApprovalsScreen
import com.cadence.shifts.ui.screens.MyScheduleScreen
import com.cadence.shifts.ui.screens.NotificationsScreen
import com.cadence.shifts.ui.screens.OverviewScreen
import com.cadence.shifts.ui.screens.ScheduleFairnessScreen
import com.cadence.shifts.ui.screens.ShiftHistoryScreen
import com.cadence.shifts.ui.screens.ShiftReportsScreen
import com.cadence.shifts.ui.screens.TeamHoursScreen
import com.cadence.shifts.ui.screens.TimeOffRequestsScreen
import com.cadence.shifts.ui.theme.Amber600
import com.cadence.shifts.ui.theme.PureWhite
import com.cadence.shifts.ui.theme.Rose600
import com.cadence.shifts.ui.theme.Slate50
import com.cadence.shifts.ui.theme.Slate600
import com.cadence.shifts.ui.theme.Slate900
import com.cadence.shifts.ui.viewmodel.CadenceViewModel
import com.cadence.shifts.ui.viewmodel.ProfileUiState
import com.cadence.shifts.ui.viewmodel.WorkspaceUiData
import com.cadence.shifts.util.BatteryOptimizationHelper
import com.cadence.shifts.util.GoogleAuthHelper
import com.cadence.shifts.util.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class CadenceTab(val route: String, val label: String, val icon: ImageVector) {
    OVERVIEW("overview", "Overview", Icons.Rounded.Dashboard),
    MY_SCHEDULE("my_schedule", "My Schedule", Icons.Rounded.Today),
    SCHEDULE("schedule", "Schedule", Icons.Rounded.CalendarMonth),
    REPORTS("reports", "Reports", Icons.Rounded.Assignment),
    APPROVALS("approvals", "Approvals", Icons.Rounded.FactCheck),
    HISTORY("history", "Shift History", Icons.Rounded.History),
    TEAM("team", "Team", Icons.Rounded.Groups),
    TIME_OFF("time_off", "Time Off", Icons.Rounded.EventBusy),
    NOTIFICATIONS("notifications", "Alerts", Icons.Rounded.Notifications)
}

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun CadenceApp(
    requestedTabRoute: String? = null,
    onConsumedRequestedTab: () -> Unit = {}
) {
    val auth = Firebase.auth
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val currentUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)
    var verificationTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        NotificationHelper.ensureChannel(context)
        if (auth.currentUser == null) {
            GoogleAuthHelper.attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = { verificationTick++ },
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    val isVerified = remember(user, verificationTick) {
        (Firebase.auth.currentUser ?: user)?.isEmailVerified == true
    }

    LaunchedEffect(user, isVerified) {
        if (user == null || !isVerified) {
            BackgroundSyncScheduler.stopBackgroundSync(context)
        }
    }

    if (user == null || !isVerified) {
        AuthScreen(
            unverifiedUser = user,
            onAuthSuccess = { verificationTick++ },
            onVerificationConfirmed = { verificationTick++ }
        )
    } else {
        val uid = user.uid
        key(uid) {
            WorkspaceRoot(
                currentUserId = uid,
                requestedTabRoute = requestedTabRoute,
                onConsumedRequestedTab = onConsumedRequestedTab
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceRoot(
    currentUserId: String,
    requestedTabRoute: String?,
    onConsumedRequestedTab: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: CadenceViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                CadenceViewModel(CadenceRepository(db, Firebase.auth), currentUserId)
            }
        }
    )

    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val workspaceData by viewModel.workspaceData.collectAsStateWithLifecycle()
    val weekSlots by viewModel.weekSlots.collectAsStateWithLifecycle()
    val busyAction by viewModel.busyAction.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var activeTab by remember { mutableStateOf(CadenceTab.OVERVIEW) }
    var openReportModalFromOverview by remember { mutableStateOf(false) }
    var reportPreselectedShift by remember { mutableStateOf<Shift?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var hasOsNotifPermission by remember {
        mutableStateOf(NotificationHelper.hasNotificationPermission(context))
    }
    var isUnrestrictedBattery by remember {
        mutableStateOf(BatteryOptimizationHelper.isUnrestrictedBattery(context))
    }
    var showBatteryOptimizationDialog by remember(currentUserId) { mutableStateOf(false) }
    var batteryPromptedThisSession by remember(currentUserId) { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasOsNotifPermission = granted
        if (granted) {
            viewModel.showBanner("Device shift notifications enabled.")
            val ready = profileState as? ProfileUiState.Ready
            if (ready != null) {
                BackgroundSyncScheduler.ensureBackgroundSync(context, ready.profile.pushNotificationsEnabled)
            }
        }
    }

    var askedNotificationPermissionOnce by remember(currentUserId) { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, profileState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOsNotifPermission = NotificationHelper.hasNotificationPermission(context)
                isUnrestrictedBattery = BatteryOptimizationHelper.isUnrestrictedBattery(context)
                val ready = profileState as? ProfileUiState.Ready
                if (ready != null) {
                    BackgroundSyncScheduler.ensureBackgroundSync(context, ready.profile.pushNotificationsEnabled)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(requestedTabRoute) {
        val target = CadenceTab.entries.firstOrNull { it.route == requestedTabRoute }
        if (target != null) {
            activeTab = target
            onConsumedRequestedTab()
        }
    }

    LaunchedEffect(profileState, hasOsNotifPermission, isUnrestrictedBattery) {
        val ready = profileState as? ProfileUiState.Ready ?: return@LaunchedEffect
        BackgroundSyncScheduler.ensureBackgroundSync(context, ready.profile.pushNotificationsEnabled)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasOsNotifPermission &&
            ready.profile.pushNotificationsEnabled &&
            !askedNotificationPermissionOnce
        ) {
            askedNotificationPermissionOnce = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return@LaunchedEffect
        }

        if (ready.profile.pushNotificationsEnabled &&
            !isUnrestrictedBattery &&
            !batteryPromptedThisSession
        ) {
            batteryPromptedThisSession = true
            showBatteryOptimizationDialog = true
        }
    }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearBanner()
    }

    LaunchedEffect(workspaceData) {
        val liveData = workspaceData ?: return@LaunchedEffect
        val alerts = NotificationHelper.filterUnnotifiedAlerts(
            context = context,
            uid = liveData.profile.uid,
            notifications = liveData.notifications,
            pushEnabled = liveData.profile.pushNotificationsEnabled
        )
        if (alerts.isNotEmpty()) {
            NotificationHelper.dispatchNotificationsSequentially(context, alerts)
            val latest = alerts.first()
            viewModel.showBanner("${latest.title}: ${latest.message}")
        }

        if (liveData.isManager) {
            val managerPayloads = NotificationHelper.buildManagerWorkPayloads(
                managerUid = liveData.profile.uid,
                scheduleChanges = liveData.scheduleChanges,
                timeOffRequests = liveData.timeOffRequests,
                shiftReports = liveData.shiftReports
            )
            val mgrAlerts = NotificationHelper.filterNewManagerWorkAlerts(
                context = context,
                uid = liveData.profile.uid,
                items = managerPayloads,
                pushEnabled = liveData.profile.pushNotificationsEnabled
            )
            if (mgrAlerts.isNotEmpty()) {
                NotificationHelper.dispatchManagerPayloadsSequentially(context, mgrAlerts)
                if (alerts.isEmpty()) {
                    val latest = mgrAlerts.first()
                    viewModel.showBanner("${latest.title}: ${latest.message}")
                }
            }
        }
    }

    // Back press from secondary tabs returns to Overview cleanly
    BackHandler(enabled = activeTab != CadenceTab.OVERVIEW) {
        activeTab = CadenceTab.OVERVIEW
    }

    when (val pState = profileState) {
        ProfileUiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.loading_workspace),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate600
                    )
                }
            }
        }

        is ProfileUiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CadenceCard(modifier = Modifier.widthIn(max = 460.dp)) {
                    Text("Unable to load Cadence workspace", style = MaterialTheme.typography.titleLarge, color = Slate900)
                    Text(pState.message, style = MaterialTheme.typography.bodySmall, color = Slate600)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.hydrateCurrentUser() }) {
                            Text("Retry")
                        }
                        OutlinedButton(onClick = {
                            BackgroundSyncScheduler.stopBackgroundSync(context)
                            GoogleAuthHelper.signOut(credentialManager, scope)
                        }) {
                            Text("Sign Out")
                        }
                    }
                }
            }
        }

        is ProfileUiState.Ready -> {
            val data: WorkspaceUiData = workspaceData ?: WorkspaceUiData(profile = pState.profile)
            val bottomBarTabs = listOf(
                CadenceTab.OVERVIEW,
                CadenceTab.SCHEDULE,
                CadenceTab.REPORTS,
                CadenceTab.APPROVALS,
                CadenceTab.TEAM
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useRail = maxWidth >= 600.dp

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    containerColor = Slate50,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = PureWhite,
                                titleContentColor = Slate900
                            ),
                            title = {
                                Column {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        text = activeTab.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slate600
                                    )
                                }
                            },
                            actions = {
                                if (!useRail) {
                                    IconButton(
                                        onClick = { activeTab = CadenceTab.HISTORY },
                                        modifier = Modifier.testTag("top_history_button")
                                    ) {
                                        Icon(Icons.Rounded.History, contentDescription = "Shift History")
                                    }

                                    IconButton(
                                        onClick = { activeTab = CadenceTab.TIME_OFF },
                                        modifier = Modifier.testTag("top_timeoff_button")
                                    ) {
                                        BadgedBox(
                                            badge = {
                                                if (data.pendingTimeOffCount > 0) {
                                                    Badge { Text("${data.pendingTimeOffCount}") }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Rounded.EventBusy, contentDescription = "Time Off Requests")
                                        }
                                    }

                                    IconButton(
                                        onClick = { activeTab = CadenceTab.NOTIFICATIONS },
                                        modifier = Modifier.testTag("top_notifications_button")
                                    ) {
                                        BadgedBox(
                                            badge = {
                                                if (data.unreadNotificationsCount > 0) {
                                                    Badge { Text("${data.unreadNotificationsCount}") }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Rounded.Notifications, contentDescription = "Notifications")
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { showProfileDialog = true },
                                    modifier = Modifier.testTag("top_profile_button")
                                ) {
                                    Icon(Icons.Rounded.Settings, contentDescription = "Profile & Preferences")
                                }
                            }
                        )
                    },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar(
                                containerColor = PureWhite,
                                modifier = Modifier.navigationBarsPadding()
                            ) {
                                bottomBarTabs.forEach { tab ->
                                    val selected = activeTab == tab
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { activeTab = tab },
                                        icon = {
                                            val badgeCount = when (tab) {
                                                CadenceTab.APPROVALS -> data.pendingApprovalsCount
                                                CadenceTab.SCHEDULE -> data.conflictShiftsCount
                                                else -> 0
                                            }
                                            BadgedBox(
                                                badge = {
                                                    if (badgeCount > 0) {
                                                        Badge { Text("$badgeCount") }
                                                    }
                                                }
                                            ) {
                                                Icon(tab.icon, contentDescription = tab.label)
                                            }
                                        },
                                        label = { Text(tab.label) },
                                        modifier = Modifier.testTag("nav_${tab.route}")
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useRail) {
                            NavigationRail(
                                containerColor = PureWhite,
                                modifier = Modifier.fillMaxHeight()
                            ) {
                                CadenceTab.entries.forEach { tab ->
                                    val badgeCount = when (tab) {
                                        CadenceTab.APPROVALS -> data.pendingApprovalsCount
                                        CadenceTab.TIME_OFF -> data.pendingTimeOffCount
                                        CadenceTab.NOTIFICATIONS -> data.unreadNotificationsCount
                                        CadenceTab.SCHEDULE -> data.conflictShiftsCount
                                        else -> 0
                                    }
                                    NavigationRailItem(
                                        selected = activeTab == tab,
                                        onClick = { activeTab = tab },
                                        icon = {
                                            BadgedBox(
                                                badge = {
                                                    if (badgeCount > 0) {
                                                        Badge { Text("$badgeCount") }
                                                    }
                                                }
                                            ) {
                                                Icon(tab.icon, contentDescription = tab.label)
                                            }
                                        },
                                        label = { Text(tab.label) },
                                        modifier = Modifier.testTag("rail_${tab.route}")
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .widthIn(max = 1040.dp)
                            ) {
                                when (activeTab) {
                                    CadenceTab.OVERVIEW -> OverviewScreen(
                                        data = data,
                                        onNavigateToMySchedule = { activeTab = CadenceTab.MY_SCHEDULE },
                                        onNavigateToSchedule = { activeTab = CadenceTab.SCHEDULE },
                                        onNavigateToApprovals = { activeTab = CadenceTab.APPROVALS },
                                        onNavigateToHistory = { activeTab = CadenceTab.HISTORY },
                                        onNavigateToTimeOff = { activeTab = CadenceTab.TIME_OFF },
                                        onNavigateToNotifications = { activeTab = CadenceTab.NOTIFICATIONS },
                                        onOpenLogReport = {
                                            reportPreselectedShift = null
                                            openReportModalFromOverview = true
                                            activeTab = CadenceTab.REPORTS
                                        },
                                        onSubmitScheduleChange = viewModel::submitScheduleChange
                                    )

                                    CadenceTab.MY_SCHEDULE -> MyScheduleScreen(
                                        data = data,
                                        onNavigateToTeamSchedule = { activeTab = CadenceTab.SCHEDULE },
                                        onOpenLogReportForShift = { shift ->
                                            reportPreselectedShift = shift
                                            openReportModalFromOverview = true
                                            activeTab = CadenceTab.REPORTS
                                        },
                                        onSubmitScheduleChange = viewModel::submitScheduleChange,
                                        onUpdateShift = viewModel::updateShift,
                                        onDeleteShift = viewModel::deleteShift
                                    )

                                    CadenceTab.SCHEDULE -> ScheduleFairnessScreen(
                                        data = data,
                                        weekSlots = weekSlots,
                                        busyAction = busyAction,
                                        onNavigateToMySchedule = { activeTab = CadenceTab.MY_SCHEDULE },
                                        onArrangeOverlaps = viewModel::arrangeAllOverlapsByFairness,
                                        onAssignOpenShifts = { viewModel.assignOpenShiftsByFairness(false) },
                                        onGenerateWeekSchedule = viewModel::generateWeeklySchedule,
                                        onUpdateWeekSlots = viewModel::updateWeekSlots,
                                        onCreateShift = viewModel::createShift,
                                        onUpdateShift = viewModel::updateShift,
                                        onDeleteShift = viewModel::deleteShift,
                                        onSubmitScheduleChange = viewModel::submitScheduleChange,
                                        onSubmitAvailability = viewModel::submitShiftAvailability,
                                        onWithdrawSubmission = viewModel::withdrawSubmission
                                    )

                                    CadenceTab.REPORTS -> ShiftReportsScreen(
                                        data = data,
                                        busyAction = busyAction,
                                        openModalInitially = openReportModalFromOverview,
                                        initialShiftForReport = reportPreselectedShift,
                                        onConsumedInitialModal = {
                                            openReportModalFromOverview = false
                                            reportPreselectedShift = null
                                        },
                                        onSubmitReport = viewModel::submitShiftReport,
                                        onVerifyOrFlag = viewModel::verifyOrFlagReport
                                    )

                                    CadenceTab.APPROVALS -> ManagerApprovalsScreen(
                                        data = data,
                                        busyAction = busyAction,
                                        onAcceptAll = viewModel::acceptAllPendingChanges,
                                        onDecide = viewModel::decideScheduleChange,
                                        onSubmitScheduleChange = viewModel::submitScheduleChange,
                                        onNavigateToHistory = { activeTab = CadenceTab.HISTORY }
                                    )

                                    CadenceTab.HISTORY -> ShiftHistoryScreen(
                                        data = data
                                    )

                                    CadenceTab.TIME_OFF -> TimeOffRequestsScreen(
                                        data = data,
                                        busyAction = busyAction,
                                        onCreateTimeOff = viewModel::createTimeOffRequest,
                                        onWithdrawTimeOff = viewModel::withdrawTimeOffRequest,
                                        onDecideTimeOff = viewModel::decideTimeOffRequest
                                    )

                                    CadenceTab.NOTIFICATIONS -> NotificationsScreen(
                                        data = data,
                                        hasOsNotificationPermission = hasOsNotifPermission,
                                        isUnrestrictedBattery = isUnrestrictedBattery,
                                        busyAction = busyAction,
                                        onRequestOsPermission = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        },
                                        onOpenBatteryDialog = { showBatteryOptimizationDialog = true },
                                        onSendTestAlert = viewModel::sendTestAlert,
                                        onMarkAllRead = viewModel::markAllNotificationsRead,
                                        onToggleRead = viewModel::markNotificationRead
                                    )

                                    CadenceTab.TEAM -> TeamHoursScreen(
                                        data = data,
                                        busyAction = busyAction,
                                        onSeedSampleData = viewModel::seedSampleData,
                                        onAddRosterStaff = viewModel::addRosterMember,
                                        onToggleManagerRole = viewModel::toggleManagerRole
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showProfileDialog) {
                ProfileSettingsDialog(
                    data = data,
                    onDismiss = { showProfileDialog = false },
                    onSave = { name, dept, target, push ->
                        viewModel.updatePreferences(name, dept, target, push)
                        BackgroundSyncScheduler.ensureBackgroundSync(context, push)
                        showProfileDialog = false
                    },
                    onSignOut = {
                        showProfileDialog = false
                        BackgroundSyncScheduler.stopBackgroundSync(context)
                        GoogleAuthHelper.signOut(credentialManager, scope)
                    }
                )
            }

            if (showBatteryOptimizationDialog) {
                BatteryOptimizationDialog(
                    onOpenAppSettings = {
                        showBatteryOptimizationDialog = false
                        BatteryOptimizationHelper.openAppBatterySettings(context)
                    },
                    onOpenBatteryList = {
                        showBatteryOptimizationDialog = false
                        BatteryOptimizationHelper.openBatteryOptimizationList(context)
                    },
                    onDismiss = { showBatteryOptimizationDialog = false }
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationDialog(
    onOpenAppSettings: () -> Unit,
    onOpenBatteryList: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.BatteryAlert,
                contentDescription = null,
                tint = Amber600
            )
        },
        title = {
            Text(
                text = stringResource(R.string.battery_dialog_title),
                color = Slate900
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.battery_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate600
                )
                Text(
                    text = stringResource(R.string.battery_dialog_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate900
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenAppSettings,
                modifier = Modifier.testTag("battery_dialog_open_settings")
            ) {
                Text(stringResource(R.string.battery_dialog_open_settings))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onOpenBatteryList,
                    modifier = Modifier.testTag("battery_dialog_all_list")
                ) {
                    Text(stringResource(R.string.battery_dialog_all_list))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("battery_dialog_later")
                ) {
                    Text(stringResource(R.string.battery_dialog_later))
                }
            }
        }
    )
}

@Composable
private fun ProfileSettingsDialog(
    data: WorkspaceUiData,
    onDismiss: () -> Unit,
    onSave: (name: String, dept: String, weeklyTarget: Double, pushEnabled: Boolean) -> Unit,
    onSignOut: () -> Unit
) {
    var name by remember { mutableStateOf(data.profile.displayName) }
    var dept by remember { mutableStateOf(data.profile.department) }
    var targetText by remember { mutableStateOf(data.profile.weeklyHoursTarget.toInt().toString()) }
    var pushEnabled by remember { mutableStateOf(data.profile.pushNotificationsEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${data.profile.email} • ${if (data.isManager) "Manager" else "Staff"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dept,
                    onValueChange = { dept = it },
                    label = { Text("Department") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Weekly Hours Target") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.enable_push_alerts), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = pushEnabled, onCheckedChange = { pushEnabled = it })
                }

                Spacer(modifier = Modifier.size(4.dp))

                OutlinedButton(
                    onClick = onSignOut,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Rose600),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sign_out_button")
                ) {
                    Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.sign_out))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = (targetText.toDoubleOrNull() ?: 40.0).coerceIn(1.0, 80.0)
                    onSave(name, dept, target, pushEnabled)
                },
                enabled = name.isNotBlank() && dept.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
