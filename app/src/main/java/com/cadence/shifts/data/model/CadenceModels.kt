package com.cadence.shifts.data.model

import androidx.annotation.StringRes
import com.cadence.shifts.R
import com.google.firebase.Timestamp

enum class UserRole(val value: String, @StringRes val labelRes: Int) {
    STAFF("staff", R.string.role_staff),
    MANAGER("manager", R.string.role_manager);

    companion object {
        fun from(raw: String?): UserRole =
            entries.firstOrNull { it.value == raw } ?: STAFF
    }
}

enum class ShiftStatus(val value: String, val label: String, @StringRes val labelRes: Int) {
    SCHEDULED("scheduled", "Scheduled", R.string.status_scheduled),
    OPEN("open", "Open / Blank", R.string.status_open),
    COMPLETED("completed", "Completed", R.string.status_completed),
    CONFLICT("conflict", "Overlap Conflict", R.string.status_conflict),
    PENDING_CHANGE("pending_change", "Pending Change", R.string.status_pending_change);

    companion object {
        fun from(raw: String?): ShiftStatus =
            entries.firstOrNull { it.value == raw } ?: SCHEDULED
    }
}

enum class ScheduleChangeType(val value: String, val label: String, @StringRes val labelRes: Int) {
    TIME_CHANGE("time_change", "Time Change", R.string.change_type_time_change),
    SWAP("swap", "Swap Assignee", R.string.change_type_swap),
    DROP("drop", "Drop Shift", R.string.change_type_drop),
    CLAIM("claim", "Claim Open Shift", R.string.change_type_claim);

    companion object {
        fun from(raw: String?): ScheduleChangeType =
            entries.firstOrNull { it.value == raw } ?: TIME_CHANGE
    }
}

enum class ApprovalStatus(val value: String, val label: String, @StringRes val labelRes: Int) {
    PENDING("pending", "Pending", R.string.approval_pending),
    APPROVED("approved", "Approved", R.string.approval_approved),
    DENIED("denied", "Denied", R.string.approval_denied);

    companion object {
        fun from(raw: String?): ApprovalStatus =
            entries.firstOrNull { it.value == raw } ?: PENDING
    }
}

enum class ShiftReportStatus(val value: String, val label: String, @StringRes val labelRes: Int) {
    SUBMITTED("submitted", "Submitted", R.string.report_status_submitted),
    VERIFIED("verified", "Verified", R.string.report_status_verified),
    FLAGGED("flagged", "Flagged", R.string.report_status_flagged);

    companion object {
        fun from(raw: String?): ShiftReportStatus =
            entries.firstOrNull { it.value == raw } ?: SUBMITTED
    }
}

enum class NotificationCategory(val value: String, val label: String, @StringRes val labelRes: Int) {
    SHIFT_APPROVED("shift_approved", "Approved", R.string.notif_cat_shift_approved),
    SHIFT_DENIED("shift_denied", "Denied", R.string.notif_cat_shift_denied),
    FAIRNESS_REBALANCED("fairness_rebalanced", "Fairness Rebalance", R.string.notif_cat_fairness_rebalanced),
    REPORT_VERIFIED("report_verified", "Report Verified", R.string.notif_cat_report_verified),
    SHIFT_ASSIGNED("shift_assigned", "Shift Assigned", R.string.notif_cat_shift_assigned),
    TIME_OFF_APPROVED("time_off_approved", "Time Off Approved", R.string.notif_cat_time_off_approved),
    TIME_OFF_DENIED("time_off_denied", "Time Off Denied", R.string.notif_cat_time_off_denied);

    companion object {
        fun from(raw: String?): NotificationCategory =
            entries.firstOrNull { it.value == raw } ?: SHIFT_ASSIGNED
    }
}

enum class SubmissionStatus(val value: String, val label: String, @StringRes val labelRes: Int) {
    SUBMITTED("submitted", "Submitted", R.string.sub_status_submitted),
    WITHDRAWN("withdrawn", "Withdrawn", R.string.sub_status_withdrawn);

    companion object {
        fun from(raw: String?): SubmissionStatus =
            entries.firstOrNull { it.value == raw } ?: SUBMITTED
    }
}

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: UserRole = UserRole.STAFF,
    val department: String = "Operations",
    val weeklyHoursTarget: Double = 40.0,
    val pushNotificationsEnabled: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class StaffDirectoryEntry(
    val uid: String = "",
    val displayName: String = "",
    val role: UserRole = UserRole.STAFF,
    val department: String = "Operations",
    val totalHoursWorked: Double = 0.0,
    val scheduledHoursThisWeek: Double = 0.0,
    val weeklyHoursTarget: Double = 40.0,
    val lastAssignedAt: String = "",
    val isSampleRoster: Boolean = false,
    val updatedAt: Timestamp? = null
)

data class Shift(
    val id: String = "",
    val title: String = "",
    val department: String = "Operations",
    val date: String = "",
    val startTime: String = "09:00",
    val endTime: String = "17:00",
    val durationHours: Double = 8.0,
    val assignedUid: String = "",
    val assignedName: String = "",
    val candidateUids: List<String> = emptyList(),
    val status: ShiftStatus = ShiftStatus.SCHEDULED,
    val visibility: String = "team",
    val fairnessNote: String = "",
    val createdBy: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class ScheduleChangeRequest(
    val id: String = "",
    val shiftId: String = "",
    val shiftTitle: String = "",
    val requesterUid: String = "",
    val requesterName: String = "",
    val type: ScheduleChangeType = ScheduleChangeType.TIME_CHANGE,
    val currentDate: String = "",
    val currentStartTime: String = "",
    val currentEndTime: String = "",
    val proposedDate: String = "",
    val proposedStartTime: String = "",
    val proposedEndTime: String = "",
    val proposedAssigneeUid: String = "",
    val proposedAssigneeName: String = "",
    val reason: String = "",
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val reviewedByUid: String = "",
    val reviewedByName: String = "",
    val reviewNote: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class ShiftReport(
    val id: String = "",
    val shiftId: String = "",
    val shiftTitle: String = "",
    val staffUid: String = "",
    val staffName: String = "",
    val department: String = "Operations",
    val date: String = "",
    val actualStartTime: String = "",
    val actualEndTime: String = "",
    val breakMinutes: Int = 30,
    val workedHours: Double = 0.0,
    val handoffNotes: String = "",
    val status: ShiftReportStatus = ShiftReportStatus.SUBMITTED,
    val verifiedByUid: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class PushNotificationRecord(
    val id: String = "",
    val recipientUid: String = "",
    val title: String = "",
    val message: String = "",
    val category: NotificationCategory = NotificationCategory.SHIFT_ASSIGNED,
    val relatedShiftId: String = "",
    val isRead: Boolean = false,
    val actorUid: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class ShiftSubmission(
    val id: String = "",
    val staffUid: String = "",
    val staffName: String = "",
    val department: String = "Operations",
    val date: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val shiftTitle: String = "",
    val notes: String = "",
    val status: SubmissionStatus = SubmissionStatus.SUBMITTED,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class TimeOffRequest(
    val id: String = "",
    val staffUid: String = "",
    val staffName: String = "",
    val department: String = "Operations",
    val startDate: String = "",
    val endDate: String = "",
    val reason: String = "",
    val status: ApprovalStatus = ApprovalStatus.PENDING,
    val reviewedByUid: String = "",
    val reviewedByName: String = "",
    val reviewNote: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)

data class WeekSlotConfig(
    val id: String,
    val label: String,
    val title: String,
    val department: String,
    val startTime: String,
    val endTime: String
)

data class FairnessScoreBreakdown(
    val uid: String,
    val displayName: String,
    val department: String,
    val totalHoursWorked: Double,
    val scheduledHoursThisWeek: Double,
    val weeklyHoursTarget: Double,
    val utilizationRatio: Double,
    val shiftsThisWeekCount: Int,
    val lastAssignedAt: String,
    val hasConflict: Boolean,
    val hasApprovedTimeOff: Boolean = false,
    val timeOffReason: String? = null
)

data class FairnessArrangementItem(
    val shift: Shift,
    val previousUid: String,
    val previousName: String,
    val chosenUid: String,
    val chosenName: String,
    val reason: String
)

data class OpenShiftAssignmentItem(
    val shift: Shift,
    val chosenUid: String,
    val chosenName: String,
    val reason: String
)

data class TimeOffConflict(
    val hasTimeOff: Boolean,
    val reason: String? = null
)
