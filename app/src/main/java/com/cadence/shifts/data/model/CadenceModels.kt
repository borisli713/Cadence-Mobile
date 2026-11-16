package com.cadence.shifts.data.model

import com.google.firebase.Timestamp

enum class UserRole(val value: String) {
    STAFF("staff"),
    MANAGER("manager");

    companion object {
        fun from(raw: String?): UserRole =
            entries.firstOrNull { it.value == raw } ?: STAFF
    }
}

enum class ShiftStatus(val value: String, val label: String) {
    SCHEDULED("scheduled", "Scheduled"),
    OPEN("open", "Open / Blank"),
    COMPLETED("completed", "Completed"),
    CONFLICT("conflict", "Overlap Conflict"),
    PENDING_CHANGE("pending_change", "Pending Change");

    companion object {
        fun from(raw: String?): ShiftStatus =
            entries.firstOrNull { it.value == raw } ?: SCHEDULED
    }
}

enum class ScheduleChangeType(val value: String, val label: String) {
    TIME_CHANGE("time_change", "Time Change"),
    SWAP("swap", "Swap Assignee"),
    DROP("drop", "Drop Shift"),
    CLAIM("claim", "Claim Open Shift");

    companion object {
        fun from(raw: String?): ScheduleChangeType =
            entries.firstOrNull { it.value == raw } ?: TIME_CHANGE
    }
}

enum class ApprovalStatus(val value: String, val label: String) {
    PENDING("pending", "Pending"),
    APPROVED("approved", "Approved"),
    DENIED("denied", "Denied");

    companion object {
        fun from(raw: String?): ApprovalStatus =
            entries.firstOrNull { it.value == raw } ?: PENDING
    }
}

enum class ShiftReportStatus(val value: String, val label: String) {
    SUBMITTED("submitted", "Submitted"),
    VERIFIED("verified", "Verified"),
    FLAGGED("flagged", "Flagged");

    companion object {
        fun from(raw: String?): ShiftReportStatus =
            entries.firstOrNull { it.value == raw } ?: SUBMITTED
    }
}

enum class NotificationCategory(val value: String, val label: String) {
    SHIFT_APPROVED("shift_approved", "Approved"),
    SHIFT_DENIED("shift_denied", "Denied"),
    FAIRNESS_REBALANCED("fairness_rebalanced", "Fairness Rebalance"),
    REPORT_VERIFIED("report_verified", "Report Verified"),
    SHIFT_ASSIGNED("shift_assigned", "Shift Assigned"),
    TIME_OFF_APPROVED("time_off_approved", "Time Off Approved"),
    TIME_OFF_DENIED("time_off_denied", "Time Off Denied");

    companion object {
        fun from(raw: String?): NotificationCategory =
            entries.firstOrNull { it.value == raw } ?: SHIFT_ASSIGNED
    }
}

enum class SubmissionStatus(val value: String, val label: String) {
    SUBMITTED("submitted", "Submitted"),
    WITHDRAWN("withdrawn", "Withdrawn");

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
