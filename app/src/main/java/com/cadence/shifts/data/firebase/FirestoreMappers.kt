package com.cadence.shifts.data.firebase

import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.NotificationCategory
import com.cadence.shifts.data.model.PushNotificationRecord
import com.cadence.shifts.data.model.ScheduleChangeRequest
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftReport
import com.cadence.shifts.data.model.ShiftReportStatus
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.data.model.ShiftSubmission
import com.cadence.shifts.data.model.StaffDirectoryEntry
import com.cadence.shifts.data.model.SubmissionStatus
import com.cadence.shifts.data.model.TimeOffRequest
import com.cadence.shifts.data.model.UserProfile
import com.cadence.shifts.data.model.UserRole
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior

private fun DocumentSnapshot.doubleOr(field: String, default: Double = 0.0): Double =
    (get(field) as? Number)?.toDouble() ?: default

private fun DocumentSnapshot.intOr(field: String, default: Int = 0): Int =
    (get(field) as? Number)?.toInt() ?: default

fun DocumentSnapshot.toUserProfile(): UserProfile? {
    if (!exists()) return null
    return UserProfile(
        uid = getString("uid") ?: id,
        email = getString("email") ?: "",
        displayName = getString("displayName") ?: "",
        role = UserRole.from(getString("role")),
        department = getString("department") ?: "Operations",
        weeklyHoursTarget = doubleOr("weeklyHoursTarget", 40.0),
        pushNotificationsEnabled = getBoolean("pushNotificationsEnabled") ?: true,
        createdAt = getTimestamp("createdAt", ServerTimestampBehavior.ESTIMATE),
        updatedAt = getTimestamp("updatedAt", ServerTimestampBehavior.ESTIMATE)
    )
}

fun DocumentSnapshot.toStaffDirectoryEntry(): StaffDirectoryEntry? {
    if (!exists()) return null
    return StaffDirectoryEntry(
        uid = getString("uid") ?: id,
        displayName = getString("displayName") ?: "",
        role = UserRole.from(getString("role")),
        department = getString("department") ?: "Operations",
        totalHoursWorked = doubleOr("totalHoursWorked", 0.0),
        scheduledHoursThisWeek = doubleOr("scheduledHoursThisWeek", 0.0),
        weeklyHoursTarget = doubleOr("weeklyHoursTarget", 40.0),
        lastAssignedAt = getString("lastAssignedAt") ?: "",
        isSampleRoster = getBoolean("isSampleRoster") ?: false,
        updatedAt = getTimestamp("updatedAt", ServerTimestampBehavior.ESTIMATE)
    )
}

fun DocumentSnapshot.toShift(): Shift? {
    if (!exists()) return null
    val rawCandidates = get("candidateUids") as? List<*>
    val candidates = rawCandidates?.mapNotNull { it as? String } ?: emptyList()
    return Shift(
        id = getString("id") ?: id,
        title = getString("title") ?: "",
        department = getString("department") ?: "Operations",
        date = getString("date") ?: "",
        startTime = getString("startTime") ?: "09:00",
        endTime = getString("endTime") ?: "17:00",
        durationHours = doubleOr("durationHours", 8.0),
        assignedUid = getString("assignedUid") ?: "",
        assignedName = getString("assignedName") ?: "",
        candidateUids = candidates,
        status = ShiftStatus.from(getString("status")),
        visibility = getString("visibility") ?: "team",
        fairnessNote = getString("fairnessNote") ?: "",
        createdBy = getString("createdBy") ?: "",
        createdAt = getTimestamp("createdAt", ServerTimestampBehavior.ESTIMATE),
        updatedAt = getTimestamp("updatedAt", ServerTimestampBehavior.ESTIMATE)
    )
}

fun DocumentSnapshot.toScheduleChangeRequest(): ScheduleChangeRequest? {
    if (!exists()) return null
    return ScheduleChangeRequest(
        id = getString("id") ?: id,
        shiftId = getString("shiftId") ?: "",
        shiftTitle = getString("shiftTitle") ?: "",
        requesterUid = getString("requesterUid") ?: "",
        requesterName = getString("requesterName") ?: "",
        type = ScheduleChangeType.from(getString("type")),
        currentDate = getString("currentDate") ?: "",
        currentStartTime = getString("currentStartTime") ?: "",
        currentEndTime = getString("currentEndTime") ?: "",
        proposedDate = getString("proposedDate") ?: "",
        proposedStartTime = getString("proposedStartTime") ?: "",
        proposedEndTime = getString("proposedEndTime") ?: "",
        proposedAssigneeUid = getString("proposedAssigneeUid") ?: "",
        proposedAssigneeName = getString("proposedAssigneeName") ?: "",
        reason = getString("reason") ?: "",
        status = ApprovalStatus.from(getString("status")),
        reviewedByUid = getString("reviewedByUid") ?: "",
        reviewedByName = getString("reviewedByName") ?: "",
        reviewNote = getString("reviewNote") ?: "",
        createdAt = getTimestamp("createdAt", ServerTimestampBehavior.ESTIMATE),
        updatedAt = getTimestamp("updatedAt", ServerTimestampBehavior.ESTIMATE)
    )
}

fun DocumentSnapshot.toShiftReport(): ShiftReport? {
    if (!exists()) return null
    return ShiftReport(
        id = getString("id") ?: id,
        shiftId = getString("shiftId") ?: "",
        shiftTitle = getString("shiftTitle") ?: "",
        staffUid = getString("staffUid") ?: "",
        staffName = getString("staffName") ?: "",
        department = getString("department") ?: "Operations",
        date = getString("date") ?: "",
        actualStartTime = getString("actualStartTime") ?: "",
        actualEndTime = getString("actualEndTime") ?: "",
        breakMinutes = intOr("breakMinutes", 0),
        workedHours = doubleOr("workedHours", 0.0),
        handoffNotes = getString("handoffNotes") ?: "",
        status = ShiftReportStatus.from(getString("status")),
        verifiedByUid = getString("verifiedByUid") ?: "",
        createdAt = getTimestamp("createdAt", ServerTimestampBehavior.ESTIMATE),
        updatedAt = getTimestamp("updatedAt", ServerTimestampBehavior.ESTIMATE)
    )
}

fun DocumentSnapshot.toPushNotification(): PushNotificationRecord? {
    if (!exists()) return null
    return PushNotificationRecord(
        id = getString("id") ?: id,
        recipientUid = getString("recipientUid") ?: "",
        title = getString("title") ?: "",
        message = getString("message") ?: "",
        category = NotificationCategory.from(getString("category")),
        relatedShiftId = getString("relatedShiftId") ?: "",
        isRead = getBoolean("isRead") ?: false,
        actorUid = getString("actorUid") ?: "",
        createdAt = getTimestamp("createdAt", ServerTimestampBehavior.ESTIMATE),
        updatedAt = getTimestamp("updatedAt", ServerTimestampBehavior.ESTIMATE)
    )
}

fun DocumentSnapshot.toShiftSubmission(): ShiftSubmission? {
    if (!exists()) return null
    return ShiftSubmission(
        id = getString("id") ?: id,
        staffUid = getString("staffUid") ?: "",
        staffName = getString("staffName") ?: "",
        department = getString("department") ?: "Operations",
        date = getString("date") ?: "",
        startTime = getString("startTime") ?: "",
        endTime = getString("endTime") ?: "",
        shiftTitle = getString("shiftTitle") ?: "",
        notes = getString("notes") ?: "",
        status = SubmissionStatus.from(getString("status")),
        createdAt = getTimestamp("createdAt", ServerTimestampBehavior.ESTIMATE),
        updatedAt = getTimestamp("updatedAt", ServerTimestampBehavior.ESTIMATE)
    )
}

fun DocumentSnapshot.toTimeOffRequest(): TimeOffRequest? {
    if (!exists()) return null
    return TimeOffRequest(
        id = getString("id") ?: id,
        staffUid = getString("staffUid") ?: "",
        staffName = getString("staffName") ?: "",
        department = getString("department") ?: "Operations",
        startDate = getString("startDate") ?: "",
        endDate = getString("endDate") ?: "",
        reason = getString("reason") ?: "",
        status = ApprovalStatus.from(getString("status")),
        reviewedByUid = getString("reviewedByUid") ?: "",
        reviewedByName = getString("reviewedByName") ?: "",
        reviewNote = getString("reviewNote") ?: "",
        createdAt = getTimestamp("createdAt", ServerTimestampBehavior.ESTIMATE),
        updatedAt = getTimestamp("updatedAt", ServerTimestampBehavior.ESTIMATE)
    )
}
