package com.cadence.shifts.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cadence.shifts.R
import com.cadence.shifts.data.firebase.unwrapFirestoreErrorMessage
import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.PushNotificationRecord
import com.cadence.shifts.data.model.ScheduleChangeRequest
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftReport
import com.cadence.shifts.data.model.ShiftReportStatus
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.data.model.ShiftSubmission
import com.cadence.shifts.data.model.StaffDirectoryEntry
import com.cadence.shifts.data.model.TimeOffRequest
import com.cadence.shifts.data.model.UserProfile
import com.cadence.shifts.data.model.UserRole
import com.cadence.shifts.data.model.WeekSlotConfig
import com.cadence.shifts.data.repository.CadenceRepository
import com.cadence.shifts.domain.FairnessEngine
import com.cadence.shifts.util.LocaleHelper
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Ready(val profile: UserProfile) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}

data class WorkspaceUiData(
    val profile: UserProfile,
    val staffDirectory: List<StaffDirectoryEntry> = emptyList(),
    val shifts: List<Shift> = emptyList(),
    val scheduleChanges: List<ScheduleChangeRequest> = emptyList(),
    val shiftReports: List<ShiftReport> = emptyList(),
    val shiftSubmissions: List<ShiftSubmission> = emptyList(),
    val timeOffRequests: List<TimeOffRequest> = emptyList(),
    val notifications: List<PushNotificationRecord> = emptyList(),
    val streamError: String? = null
) {
    val isManager: Boolean get() = profile.role == UserRole.MANAGER
    val myAssignedShifts: List<Shift>
        get() = shifts.filter { it.assignedUid == profile.uid && it.status != ShiftStatus.OPEN }
    val pendingApprovalsCount: Int get() = scheduleChanges.count { it.status == ApprovalStatus.PENDING }
    val pendingTimeOffCount: Int get() = timeOffRequests.count { it.status == ApprovalStatus.PENDING }
    val unreadNotificationsCount: Int get() = notifications.count { !it.isRead }
    val conflictShiftsCount: Int get() = shifts.count { it.status == ShiftStatus.CONFLICT || it.candidateUids.size > 1 }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CadenceViewModel(
    private val repository: CadenceRepository,
    private val currentUserId: String,
    private val appContext: Context? = null
) : ViewModel() {

    private fun msg(@StringRes resId: Int, vararg args: Any): String? {
        val ctx = appContext ?: return null
        val loc = LocaleHelper.localizedContext(ctx)
        return if (args.isEmpty()) loc.getString(resId) else loc.getString(resId, *args)
    }

    private val _profileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileState: StateFlow<ProfileUiState> = _profileState.asStateFlow()

    private val _profileFlow = MutableStateFlow<UserProfile?>(null)

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _busyAction = MutableStateFlow<String?>(null)
    val busyAction: StateFlow<String?> = _busyAction.asStateFlow()

    private val _weekSlots = MutableStateFlow(FairnessEngine.DEFAULT_WEEK_SLOTS)
    val weekSlots: StateFlow<List<WeekSlotConfig>> = _weekSlots.asStateFlow()

    private var profileSyncJob: Job? = null

    val workspaceData: StateFlow<WorkspaceUiData?> = _profileFlow
        .filterNotNull()
        .distinctUntilChanged { old, new ->
            old.uid == new.uid && old.role == new.role
        }
        .flatMapLatest { baseProfile ->
            val isManager = baseProfile.role == UserRole.MANAGER
            val primaryStreams = combine(
                repository.observeStaffDirectory(currentUserId),
                repository.observeShifts(currentUserId),
                repository.observeScheduleChanges(currentUserId, isManager),
                repository.observeShiftReports(currentUserId, isManager)
            ) { staff, shifts, changes, reports ->
                Quad(staff, shifts, changes, reports)
            }

            val secondaryStreams = combine(
                repository.observeShiftSubmissions(currentUserId, isManager),
                repository.observeTimeOffRequests(currentUserId, isManager),
                repository.observeNotifications(currentUserId, isManager)
            ) { subs, timeOff, notifs ->
                Triple(subs, timeOff, notifs)
            }

            combine(_profileFlow.filterNotNull(), primaryStreams, secondaryStreams) { latestProfile, p, s ->
                WorkspaceUiData(
                    profile = latestProfile,
                    staffDirectory = p.first,
                    shifts = p.second,
                    scheduleChanges = p.third,
                    shiftReports = p.fourth,
                    shiftSubmissions = s.first,
                    timeOffRequests = s.second,
                    notifications = s.third,
                    streamError = null
                )
            }.catch { error ->
                if (error is CancellationException && unwrapFirestoreErrorMessage(error, "") == error.message) {
                    throw error
                }
                val msg = unwrapFirestoreErrorMessage(
                    error,
                    msg(R.string.vm_err_stream_sync) ?: "Failed to sync workspace data."
                )
                Log.w(TAG, "Stream error: $msg", error)
                emit(WorkspaceUiData(profile = baseProfile, streamError = msg))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    init {
        hydrateCurrentUser()
    }

    fun hydrateCurrentUser() {
        val user = Firebase.auth.currentUser
        if (user == null || user.uid != currentUserId) {
            _profileState.value = ProfileUiState.Error(
                msg(R.string.vm_err_session_expired) ?: "User session expired. Please sign in again."
            )
            return
        }
        _profileState.value = ProfileUiState.Loading
        profileSyncJob?.cancel()
        profileSyncJob = viewModelScope.launch {
            try {
                val initialProfile = repository.ensureVerifiedUserProvisioned(user)
                _profileFlow.value = initialProfile
                _profileState.value = ProfileUiState.Ready(initialProfile)
                repository.observeUserProfile(user.uid, initialProfile.email, initialProfile)
                    .catch { error ->
                        if (error is CancellationException && unwrapFirestoreErrorMessage(error, "") == error.message) {
                            throw error
                        }
                        Log.w(TAG, "Profile sync warning: ${error.message}")
                    }
                    .collect { liveProfile ->
                        _profileFlow.value = liveProfile
                        _profileState.value = ProfileUiState.Ready(liveProfile)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = unwrapFirestoreErrorMessage(
                    e,
                    msg(R.string.vm_err_init_profile) ?: "Unable to initialize Cadence profile."
                )
                _profileState.value = ProfileUiState.Error(msg)
            }
        }
    }

    fun showBanner(msg: String) {
        _bannerMessage.value = msg
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun updateWeekSlots(slots: List<WeekSlotConfig>) {
        _weekSlots.value = slots
        showBanner(msg(R.string.vm_banner_slots_updated) ?: "Weekly shift slot templates updated.")
    }

    private fun launchAction(actionKey: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busyAction.value = actionKey
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showBanner(
                    unwrapFirestoreErrorMessage(
                        e,
                        msg(R.string.vm_err_action_failed) ?: "Action failed. Please check permissions."
                    )
                )
            } finally {
                _busyAction.value = null
            }
        }
    }

    fun updatePreferences(
        displayName: String,
        department: String,
        weeklyHoursTarget: Double,
        pushNotificationsEnabled: Boolean
    ) = launchAction("preferences") {
        val current = _profileFlow.value ?: return@launchAction
        repository.updateUserPreferences(
            uid = current.uid,
            displayName = displayName,
            department = department,
            weeklyHoursTarget = weeklyHoursTarget,
            pushNotificationsEnabled = pushNotificationsEnabled
        )
        val updated = current.copy(
            displayName = displayName.trim(),
            department = department.trim(),
            weeklyHoursTarget = weeklyHoursTarget,
            pushNotificationsEnabled = pushNotificationsEnabled
        )
        _profileFlow.value = updated
        _profileState.value = ProfileUiState.Ready(updated)
        showBanner(msg(R.string.vm_banner_prefs_saved) ?: "Profile preferences saved.")
    }

    fun createShift(
        title: String,
        department: String,
        date: String,
        startTime: String,
        endTime: String,
        candidateUids: List<String>,
        createAsConflict: Boolean,
        leaveOpen: Boolean
    ) = launchAction("create_shift") {
        val data = workspaceData.value ?: return@launchAction
        val staffMap = data.staffDirectory.associateBy { it.uid }
        val status: ShiftStatus
        val assignedUid: String
        val assignedName: String
        val note: String

        if (leaveOpen) {
            status = ShiftStatus.OPEN
            assignedUid = ""
            assignedName = ""
            note = "Left open/blank for staff submission or fairness fill."
        } else if (createAsConflict && candidateUids.size >= 2) {
            status = ShiftStatus.CONFLICT
            val first = staffMap[candidateUids.first()]
            assignedUid = first?.uid.orEmpty()
            assignedName = first?.displayName.orEmpty()
            note = "${candidateUids.size} overlapping candidates — queued for fairness resolution."
        } else {
            val picked = FairnessEngine.pickFairestStaffForShift(
                candidateUids = candidateUids,
                staffDirectory = data.staffDirectory,
                allShifts = data.shifts,
                targetDate = date,
                startTime = startTime,
                endTime = endTime,
                timeOffRequests = data.timeOffRequests
            )
            if (picked == null) {
                status = ShiftStatus.OPEN
                assignedUid = ""
                assignedName = ""
                note = "No available staff — left open."
            } else {
                status = ShiftStatus.SCHEDULED
                assignedUid = picked.uid
                assignedName = picked.displayName
                note = "Assigned by fairness (${FairnessEngine.formatHours(picked.totalHoursWorked)}h logged, ${picked.shiftsThisWeekCount} shifts)"
            }
        }

        repository.createShift(
            title = title,
            department = department,
            date = date,
            startTime = startTime,
            endTime = endTime,
            assignedUid = assignedUid,
            assignedName = assignedName,
            candidateUids = candidateUids,
            status = status,
            fairnessNote = note,
            createdBy = currentUserId
        )
        showBanner(
            if (status == ShiftStatus.OPEN) {
                msg(R.string.vm_banner_open_shift_added) ?: "Open shift added."
            } else {
                msg(R.string.vm_banner_shift_scheduled, assignedName) ?: "Shift scheduled for $assignedName."
            }
        )
    }

    fun updateShift(
        shiftId: String,
        title: String,
        department: String,
        date: String,
        startTime: String,
        endTime: String,
        assignedUid: String,
        assignedName: String,
        status: ShiftStatus,
        fairnessNote: String
    ) = launchAction("update_shift") {
        repository.updateShift(
            shiftId = shiftId,
            title = title,
            department = department,
            date = date,
            startTime = startTime,
            endTime = endTime,
            assignedUid = assignedUid,
            assignedName = assignedName,
            status = status,
            fairnessNote = fairnessNote
        )
        showBanner(msg(R.string.vm_banner_shift_updated) ?: "Shift updated.")
    }

    fun deleteShift(shiftId: String) = launchAction("delete_shift") {
        repository.deleteShift(shiftId)
        showBanner(msg(R.string.vm_banner_shift_removed) ?: "Shift removed.")
    }

    fun arrangeAllOverlapsByFairness() = launchAction("arrange_overlaps") {
        val data = workspaceData.value ?: return@launchAction
        val plan = FairnessEngine.buildFairnessArrangementPlan(
            shifts = data.shifts,
            staffDirectory = data.staffDirectory,
            timeOffRequests = data.timeOffRequests
        )
        if (plan.isEmpty()) {
            showBanner(msg(R.string.vm_banner_no_overlaps) ?: "No overlapping or multi-candidate shifts to arrange.")
            return@launchAction
        }
        val count = repository.commitFairnessArrangementPlan(plan, currentUserId)
        showBanner(
            msg(R.string.vm_banner_overlaps_arranged, count)
                ?: "Arranged $count overlapping shift(s) by fairness."
        )
    }

    fun assignOpenShiftsByFairness(requireExplicitSubmission: Boolean = false) = launchAction("assign_open") {
        val data = workspaceData.value ?: return@launchAction
        val plan = FairnessEngine.buildOpenShiftAssignmentPlan(
            shifts = data.shifts,
            staffDirectory = data.staffDirectory,
            submissions = data.shiftSubmissions,
            timeOffRequests = data.timeOffRequests,
            requireExplicitSubmission = requireExplicitSubmission
        )
        if (plan.isEmpty()) {
            showBanner(msg(R.string.vm_banner_no_open_eligible) ?: "No eligible open shifts matched available staff.")
            return@launchAction
        }
        val count = repository.commitOpenShiftAssignmentPlan(plan, currentUserId)
        showBanner(
            msg(R.string.vm_banner_open_assigned, count)
                ?: "Assigned $count open shift(s) by fairness."
        )
    }

    fun generateWeeklySchedule(weekStartDate: String) = launchAction("generate_week") {
        val data = workspaceData.value ?: return@launchAction
        val planned = FairnessEngine.generateWeeklyFairnessSchedule(
            weekStartDate = weekStartDate,
            slots = _weekSlots.value,
            staffDirectory = data.staffDirectory,
            existingShifts = data.shifts,
            submissions = data.shiftSubmissions,
            timeOffRequests = data.timeOffRequests
        )
        val (assigned, open) = repository.commitWeeklyScheduleBatch(planned, currentUserId)
        val overlapPlan = FairnessEngine.buildFairnessArrangementPlan(
            shifts = data.shifts,
            staffDirectory = data.staffDirectory,
            timeOffRequests = data.timeOffRequests
        )
        val resolvedOverlaps = if (overlapPlan.isNotEmpty()) {
            repository.commitFairnessArrangementPlan(overlapPlan, currentUserId)
        } else 0

        if (planned.isEmpty() && resolvedOverlaps == 0) {
            showBanner(msg(R.string.vm_banner_week_populated) ?: "This week's shift slots are already populated.")
        } else {
            showBanner(
                msg(R.string.vm_banner_week_generated, assigned, open, resolvedOverlaps)
                    ?: "Generated $assigned assigned + $open open shift(s); resolved $resolvedOverlaps overlap(s)."
            )
        }
    }

    fun submitScheduleChange(
        shift: Shift,
        type: ScheduleChangeType,
        proposedDate: String,
        proposedStartTime: String,
        proposedEndTime: String,
        proposedAssigneeUid: String,
        proposedAssigneeName: String,
        reason: String
    ) = launchAction("submit_change") {
        val profile = _profileFlow.value ?: return@launchAction
        repository.submitScheduleChangeRequest(
            shift = shift,
            requesterUid = profile.uid,
            requesterName = profile.displayName,
            type = type,
            proposedDate = proposedDate,
            proposedStartTime = proposedStartTime,
            proposedEndTime = proposedEndTime,
            proposedAssigneeUid = proposedAssigneeUid,
            proposedAssigneeName = proposedAssigneeName,
            reason = reason
        )
        showBanner(msg(R.string.vm_banner_change_submitted) ?: "Schedule change request submitted for manager approval.")
    }

    fun decideScheduleChange(
        req: ScheduleChangeRequest,
        decision: ApprovalStatus,
        reviewNote: String
    ) = launchAction("decide_change_${req.id}") {
        val data = workspaceData.value ?: return@launchAction
        val shift = data.shifts.firstOrNull { it.id == req.shiftId }
        repository.decideScheduleChange(
            req = req,
            shift = shift,
            decision = decision,
            reviewerUid = data.profile.uid,
            reviewerName = data.profile.displayName,
            reviewNote = reviewNote
        )
        showBanner(
            if (decision == ApprovalStatus.APPROVED) {
                msg(R.string.vm_banner_change_approved, req.requesterName)
                    ?: "Approved ${req.requesterName}'s request & notified staff."
            } else {
                msg(R.string.vm_banner_change_denied, req.requesterName)
                    ?: "Denied ${req.requesterName}'s request & notified staff."
            }
        )
    }

    fun acceptAllPendingChanges() = launchAction("accept_all") {
        val data = workspaceData.value ?: return@launchAction
        val pending = data.scheduleChanges.filter { it.status == ApprovalStatus.PENDING }
        if (pending.isEmpty()) {
            showBanner(msg(R.string.vm_banner_no_pending_approvals) ?: "No pending schedule requests to approve.")
            return@launchAction
        }
        val count = repository.acceptAllPendingChanges(
            pendingRequests = pending,
            shifts = data.shifts,
            reviewerUid = data.profile.uid,
            reviewerName = data.profile.displayName
        )
        showBanner(
            msg(R.string.vm_banner_accept_all_done, count)
                ?: "Accept All complete: approved $count request(s) and notified staff."
        )
    }

    fun submitShiftReport(
        shiftId: String,
        shiftTitle: String,
        department: String,
        date: String,
        actualStartTime: String,
        actualEndTime: String,
        breakMinutes: Int,
        handoffNotes: String
    ) = launchAction("submit_report") {
        val profile = _profileFlow.value ?: return@launchAction
        repository.submitShiftReport(
            shiftId = shiftId,
            shiftTitle = shiftTitle,
            staffUid = profile.uid,
            staffName = profile.displayName,
            department = department,
            date = date,
            actualStartTime = actualStartTime,
            actualEndTime = actualEndTime,
            breakMinutes = breakMinutes,
            handoffNotes = handoffNotes
        )
        showBanner(msg(R.string.vm_banner_report_submitted) ?: "Shift report submitted for manager verification.")
    }

    fun verifyOrFlagReport(report: ShiftReport, decision: ShiftReportStatus) =
        launchAction("verify_report_${report.id}") {
            val data = workspaceData.value ?: return@launchAction
            val staffEntry = data.staffDirectory.firstOrNull { it.uid == report.staffUid }
            val linkedShift = if (report.shiftId.isNotBlank()) {
                data.shifts.firstOrNull { it.id == report.shiftId }
            } else null
            repository.verifyOrFlagShiftReport(
                report = report,
                status = decision,
                verifierUid = data.profile.uid,
                staffEntry = staffEntry,
                linkedShift = linkedShift
            )
            val hrs = FairnessEngine.formatHours(report.workedHours)
            showBanner(
                if (decision == ShiftReportStatus.VERIFIED) {
                    msg(R.string.vm_banner_report_verified, report.staffName, hrs)
                        ?: "Verified ${report.staffName}'s shift report (+${hrs}h)."
                } else {
                    msg(R.string.vm_banner_report_flagged, report.staffName)
                        ?: "Flagged ${report.staffName}'s shift report."
                }
            )
        }

    fun submitShiftAvailability(
        dates: List<String>,
        startTime: String,
        endTime: String,
        shiftTitle: String,
        notes: String
    ) = launchAction("submit_availability") {
        val profile = _profileFlow.value ?: return@launchAction
        val count = repository.submitShiftAvailabilityBatch(
            staffUid = profile.uid,
            staffName = profile.displayName,
            department = profile.department,
            dates = dates,
            startTime = startTime,
            endTime = endTime,
            shiftTitle = shiftTitle,
            notes = notes
        )
        showBanner(
            msg(R.string.vm_banner_availability_submitted, count)
                ?: "Submitted shift availability for $count date(s)."
        )
    }

    fun withdrawSubmission(submissionId: String) = launchAction("withdraw_sub_$submissionId") {
        repository.withdrawShiftSubmission(submissionId)
        showBanner(msg(R.string.vm_banner_availability_withdrawn) ?: "Shift availability submission withdrawn.")
    }

    fun createTimeOffRequest(startDate: String, endDate: String, reason: String) =
        launchAction("create_time_off") {
            val profile = _profileFlow.value ?: return@launchAction
            repository.createTimeOffRequest(
                staffUid = profile.uid,
                staffName = profile.displayName,
                department = profile.department,
                startDate = startDate,
                endDate = endDate,
                reason = reason
            )
            showBanner(msg(R.string.vm_banner_time_off_submitted) ?: "Time off request submitted.")
        }

    fun withdrawTimeOffRequest(requestId: String) = launchAction("withdraw_to_$requestId") {
        repository.withdrawTimeOffRequest(requestId)
        showBanner(msg(R.string.vm_banner_time_off_withdrawn) ?: "Time off request withdrawn.")
    }

    fun decideTimeOffRequest(
        req: TimeOffRequest,
        decision: ApprovalStatus,
        reviewNote: String
    ) = launchAction("decide_to_${req.id}") {
        val profile = _profileFlow.value ?: return@launchAction
        repository.decideTimeOffRequest(
            req = req,
            decision = decision,
            reviewerUid = profile.uid,
            reviewerName = profile.displayName,
            reviewNote = reviewNote
        )
        showBanner(
            if (decision == ApprovalStatus.APPROVED) {
                msg(R.string.vm_banner_time_off_approved, req.staffName) ?: "Approved time off for ${req.staffName}."
            } else {
                msg(R.string.vm_banner_time_off_denied, req.staffName) ?: "Denied time off for ${req.staffName}."
            }
        )
    }

    fun markNotificationRead(id: String, isRead: Boolean) = launchAction("mark_notif_$id") {
        repository.markNotificationRead(id, isRead)
    }

    fun markAllNotificationsRead() = launchAction("mark_all_read") {
        val data = workspaceData.value ?: return@launchAction
        repository.markAllNotificationsRead(data.notifications)
        showBanner(msg(R.string.vm_banner_all_read) ?: "All notifications marked as read.")
    }

    fun sendTestAlert() = launchAction("test_alert") {
        val profile = _profileFlow.value ?: return@launchAction
        repository.sendTestPushNotification(profile.uid, profile.displayName)
        showBanner(msg(R.string.vm_banner_test_alert_sent) ?: "Test notification dispatched.")
    }

    fun addRosterMember(
        displayName: String,
        department: String,
        weeklyHoursTarget: Double,
        role: UserRole
    ) = launchAction("add_roster") {
        repository.addRosterStaffMember(displayName, department, weeklyHoursTarget, role)
        showBanner(msg(R.string.vm_banner_member_added, displayName) ?: "Added $displayName to team roster.")
    }

    fun toggleManagerRole(entry: StaffDirectoryEntry, grantManager: Boolean) =
        launchAction("toggle_role_${entry.uid}") {
            repository.toggleManagerRole(entry, grantManager, currentUserId)
            showBanner(
                if (grantManager) {
                    msg(R.string.vm_banner_promoted_manager, entry.displayName) ?: "${entry.displayName} promoted to Manager."
                } else {
                    msg(R.string.vm_banner_set_staff, entry.displayName) ?: "${entry.displayName} set to Staff."
                }
            )
        }

    fun seedSampleData() = launchAction("seed_sample") {
        val profile = _profileFlow.value ?: return@launchAction
        repository.seedSampleWorkspaceData(profile.uid, profile.displayName)
        showBanner(
            msg(R.string.vm_banner_sample_loaded)
                ?: "Loaded sample overlaps, pending approvals, report, and time off data."
        )
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private companion object {
        const val TAG = "CadenceVM"
    }
}
