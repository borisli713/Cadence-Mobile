package com.cadence.shifts.data.repository

import android.content.Context
import com.cadence.shifts.R
import com.cadence.shifts.data.firebase.OperationType
import com.cadence.shifts.data.firebase.handleFirestoreError
import com.cadence.shifts.data.firebase.toPushNotification
import com.cadence.shifts.data.firebase.toScheduleChangeRequest
import com.cadence.shifts.data.firebase.toShift
import com.cadence.shifts.data.firebase.toShiftReport
import com.cadence.shifts.data.firebase.toShiftSubmission
import com.cadence.shifts.data.firebase.toStaffDirectoryEntry
import com.cadence.shifts.data.firebase.toTimeOffRequest
import com.cadence.shifts.data.firebase.toUserProfile
import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.FairnessArrangementItem
import com.cadence.shifts.data.model.NotificationCategory
import com.cadence.shifts.data.model.OpenShiftAssignmentItem
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
import com.cadence.shifts.domain.FairnessEngine
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.round

class CadenceRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    companion object {
        const val BOOTSTRAPPED_ADMIN_EMAIL = "yatheili713@gmail.com"
        private const val CHUNK_SIZE = 400
    }

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in before accessing Firestore.")

    private fun newId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    private fun round1(value: Double): Double = round(value * 10.0) / 10.0
    private fun round2(value: Double): Double = round(value * 100.0) / 100.0

    private fun <T> Flow<T>.withFirestoreLogging(operation: OperationType, path: String, expectedUid: String): Flow<T> =
        catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val toLog = firestoreError ?: (error as? Exception)
            if (toLog != null && auth.currentUser?.uid == expectedUid) {
                handleFirestoreError(toLog, operation, path)
            }
            throw error
        }

    // --- Real-Time Streams ---

    fun observeStaffDirectory(uid: String): Flow<List<StaffDirectoryEntry>> =
        db.collection("staffDirectory")
            .whereIn("role", listOf("staff", "manager"))
            .snapshots()
            .map { snap ->
                snap.documents.mapNotNull { it.toStaffDirectoryEntry() }
                    .sortedBy { it.displayName.lowercase() }
            }
            .withFirestoreLogging(OperationType.LIST, "staffDirectory", uid)

    fun observeShifts(uid: String): Flow<List<Shift>> {
        val teamStream = db.collection("shifts")
            .whereEqualTo("visibility", "team")
            .snapshots()
        val assignedStream = db.collection("shifts")
            .whereEqualTo("assignedUid", uid)
            .snapshots()
        return combine(teamStream, assignedStream) { teamSnap, assignedSnap ->
            val byId = LinkedHashMap<String, Shift>()
            teamSnap.documents.mapNotNull { it.toShift() }.forEach { byId[it.id] = it }
            assignedSnap.documents.mapNotNull { it.toShift() }.forEach { byId[it.id] = it }
            byId.values.sortedWith(compareBy<Shift> { it.date }.thenBy { it.startTime })
        }.withFirestoreLogging(OperationType.LIST, "shifts", uid)
    }

    fun observeScheduleChanges(uid: String, isManager: Boolean): Flow<List<ScheduleChangeRequest>> {
        if (isManager) {
            return db.collection("scheduleChanges")
                .snapshots()
                .map { snap ->
                    snap.documents.mapNotNull { it.toScheduleChangeRequest() }
                        .sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .withFirestoreLogging(OperationType.LIST, "scheduleChanges", uid)
        }
        val requestedStream = db.collection("scheduleChanges")
            .whereEqualTo("requesterUid", uid)
            .snapshots()
        val swapTargetStream = db.collection("scheduleChanges")
            .whereEqualTo("proposedAssigneeUid", uid)
            .snapshots()
        return combine(requestedStream, swapTargetStream) { reqSnap, swapSnap ->
            val byId = LinkedHashMap<String, ScheduleChangeRequest>()
            reqSnap.documents.mapNotNull { it.toScheduleChangeRequest() }.forEach { byId[it.id] = it }
            swapSnap.documents.mapNotNull { it.toScheduleChangeRequest() }.forEach { byId[it.id] = it }
            byId.values.sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
        }.withFirestoreLogging(OperationType.LIST, "scheduleChanges", uid)
    }

    fun observeShiftReports(uid: String, isManager: Boolean): Flow<List<ShiftReport>> {
        val query = if (isManager) {
            db.collection("shiftReports")
        } else {
            db.collection("shiftReports").whereEqualTo("staffUid", uid)
        }
        return query.snapshots()
            .map { snap ->
                snap.documents.mapNotNull { it.toShiftReport() }
                    .sortedByDescending { it.date }
            }
            .withFirestoreLogging(OperationType.LIST, "shiftReports", uid)
    }

    fun observeShiftSubmissions(uid: String, isManager: Boolean): Flow<List<ShiftSubmission>> {
        val query = if (isManager) {
            db.collection("shiftSubmissions")
        } else {
            db.collection("shiftSubmissions").whereEqualTo("staffUid", uid)
        }
        return query.snapshots()
            .map { snap ->
                snap.documents.mapNotNull { it.toShiftSubmission() }
                    .sortedWith(compareBy<ShiftSubmission> { it.date }.thenBy { it.startTime })
            }
            .withFirestoreLogging(OperationType.LIST, "shiftSubmissions", uid)
    }

    fun observeTimeOffRequests(uid: String, isManager: Boolean): Flow<List<TimeOffRequest>> {
        val query = if (isManager) {
            db.collection("timeOffRequests")
        } else {
            db.collection("timeOffRequests").whereEqualTo("staffUid", uid)
        }
        return query.snapshots()
            .map { snap ->
                snap.documents.mapNotNull { it.toTimeOffRequest() }
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .withFirestoreLogging(OperationType.LIST, "timeOffRequests", uid)
    }

    fun observeNotifications(uid: String, isManager: Boolean): Flow<List<PushNotificationRecord>> {
        val query = if (isManager) {
            db.collection("notifications")
        } else {
            db.collection("notifications").whereEqualTo("recipientUid", uid)
        }
        return query.snapshots()
            .map { snap ->
                snap.documents.mapNotNull { it.toPushNotification() }
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .withFirestoreLogging(OperationType.LIST, "notifications", uid)
    }

    fun observeUserProfile(
        uid: String,
        email: String,
        initialProfile: UserProfile
    ): Flow<UserProfile> {
        val isBootstrapAdmin = email.trim().equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)
        val userStream = db.collection("users").document(uid).snapshots()
        val managerStream = db.collection("managers").document(uid).snapshots()
        return combine(userStream, managerStream) { userSnap, managerSnap ->
            val base = userSnap.toUserProfile() ?: initialProfile
            val effectiveRole = if (isBootstrapAdmin || managerSnap.exists()) {
                UserRole.MANAGER
            } else {
                base.role
            }
            base.copy(role = effectiveRole)
        }.withFirestoreLogging(OperationType.GET, "users/$uid", uid)
    }

    // --- User Provisioning & Preferences ---

    suspend fun ensureVerifiedUserProvisioned(user: FirebaseUser): UserProfile {
        val uid = user.uid
        val email = (user.email ?: "staff@cadence.app").trim()
        val isBootstrapAdmin = email.equals(BOOTSTRAPPED_ADMIN_EMAIL, ignoreCase = true)

        val userRef = db.collection("users").document(uid)
        val managerRef = db.collection("managers").document(uid)
        val staffRef = db.collection("staffDirectory").document(uid)

        val userSnap = try {
            userRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, userRef.path)
            throw e
        }

        val managerSnap = try {
            managerRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

        val existingProfile = userSnap.toUserProfile()
        val effectiveRole = if (isBootstrapAdmin || managerSnap?.exists() == true) {
            UserRole.MANAGER
        } else {
            existingProfile?.role ?: UserRole.STAFF
        }

        val displayName = existingProfile?.displayName?.takeIf { it.isNotBlank() }
            ?: user.displayName?.takeIf { it.isNotBlank() }
            ?: email.substringBefore("@").ifBlank { "Team Member" }

        val profile: UserProfile = if (existingProfile == null) {
            val payload = mapOf(
                "uid" to uid,
                "email" to email,
                "displayName" to displayName,
                "role" to effectiveRole.value,
                "department" to "Operations",
                "weeklyHoursTarget" to 40.0,
                "pushNotificationsEnabled" to true,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                userRef.set(payload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, userRef.path)
                throw e
            }
            UserProfile(
                uid = uid,
                email = email,
                displayName = displayName,
                role = effectiveRole,
                department = "Operations",
                weeklyHoursTarget = 40.0,
                pushNotificationsEnabled = true
            )
        } else {
            if (existingProfile.role != effectiveRole) {
                try {
                    userRef.update(
                        mapOf(
                            "role" to effectiveRole.value,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.UPDATE, userRef.path)
                }
            }
            existingProfile.copy(role = effectiveRole)
        }

        val staffSnap = try {
            staffRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, staffRef.path)
            throw e
        }

        if (!staffSnap.exists()) {
            val staffPayload = mapOf(
                "uid" to uid,
                "displayName" to profile.displayName,
                "role" to effectiveRole.value,
                "department" to profile.department,
                "totalHoursWorked" to 0.0,
                "scheduledHoursThisWeek" to 0.0,
                "weeklyHoursTarget" to profile.weeklyHoursTarget,
                "lastAssignedAt" to "",
                "isSampleRoster" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            try {
                staffRef.set(staffPayload).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.CREATE, staffRef.path)
                throw e
            }
        } else {
            val existingStaff = staffSnap.toStaffDirectoryEntry()
            if (existingStaff != null && existingStaff.role != effectiveRole) {
                try {
                    staffRef.update(
                        mapOf(
                            "role" to effectiveRole.value,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }

        return profile
    }

    suspend fun updateUserPreferences(
        uid: String,
        displayName: String,
        department: String,
        weeklyHoursTarget: Double,
        pushNotificationsEnabled: Boolean
    ) {
        val currentUid = requireUserId()
        require(currentUid == uid) { "Cannot update another user's preferences." }
        val userRef = db.collection("users").document(uid)
        val staffRef = db.collection("staffDirectory").document(uid)

        try {
            userRef.update(
                mapOf(
                    "displayName" to displayName.trim(),
                    "department" to department.trim(),
                    "weeklyHoursTarget" to weeklyHoursTarget,
                    "pushNotificationsEnabled" to pushNotificationsEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            staffRef.update(
                mapOf(
                    "displayName" to displayName.trim(),
                    "department" to department.trim(),
                    "weeklyHoursTarget" to weeklyHoursTarget,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
            throw e
        }
    }

    // --- Shifts ---

    suspend fun createShift(
        title: String,
        department: String,
        date: String,
        startTime: String,
        endTime: String,
        assignedUid: String,
        assignedName: String,
        candidateUids: List<String>,
        status: ShiftStatus,
        fairnessNote: String,
        createdBy: String
    ): String {
        val actorUid = requireUserId()
        val id = newId("shift")
        val duration = FairnessEngine.computeShiftHours(startTime, endTime)
        val docRef = db.collection("shifts").document(id)

        val effectiveUid = if (status == ShiftStatus.OPEN) "" else assignedUid
        val effectiveName = if (status == ShiftStatus.OPEN) "" else assignedName
        val cleanTitle = title.trim().ifBlank { "Assigned Shift" }.take(120)

        val payload = mapOf(
            "id" to id,
            "title" to cleanTitle,
            "department" to department.trim(),
            "date" to date,
            "startTime" to startTime,
            "endTime" to endTime,
            "durationHours" to duration,
            "assignedUid" to effectiveUid,
            "assignedName" to effectiveName,
            "candidateUids" to candidateUids.take(20),
            "status" to status.value,
            "visibility" to "team",
            "fairnessNote" to fairnessNote.take(300),
            "createdBy" to (createdBy.ifBlank { actorUid }),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val batch = db.batch()
        batch.set(docRef, payload)

        if (effectiveUid.isNotBlank() && effectiveUid != actorUid && status != ShiftStatus.OPEN) {
            val notifId = newId("ntf")
            batch.set(
                db.collection("notifications").document(notifId),
                mapOf(
                    "id" to notifId,
                    "recipientUid" to effectiveUid,
                    "title" to "New Shift Assigned",
                    "message" to "$cleanTitle on $date ($startTime-$endTime) was assigned to you.".take(400),
                    "category" to NotificationCategory.SHIFT_ASSIGNED.value,
                    "relatedShiftId" to id,
                    "isRead" to false,
                    "actorUid" to actorUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        return id
    }

    suspend fun updateShift(
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
    ) {
        val actorUid = requireUserId()
        val docRef = db.collection("shifts").document(shiftId)
        val previousShift = runCatching { docRef.get().await().toShift() }.getOrNull()

        val duration = FairnessEngine.computeShiftHours(startTime, endTime)
        val effectiveUid = if (status == ShiftStatus.OPEN) "" else assignedUid
        val effectiveName = if (status == ShiftStatus.OPEN) "" else assignedName
        val cleanTitle = title.trim().ifBlank { "Assigned Shift" }.take(120)

        val payload = mapOf(
            "title" to cleanTitle,
            "department" to department.trim(),
            "date" to date,
            "startTime" to startTime,
            "endTime" to endTime,
            "durationHours" to duration,
            "assignedUid" to effectiveUid,
            "assignedName" to effectiveName,
            "status" to status.value,
            "fairnessNote" to fairnessNote.take(300),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val batch = db.batch()
        batch.update(docRef, payload)

        if (effectiveUid.isNotBlank() && effectiveUid != actorUid && status != ShiftStatus.OPEN) {
            val isNewAssignment = previousShift == null ||
                previousShift.assignedUid != effectiveUid ||
                previousShift.status == ShiftStatus.OPEN
            val scheduleChanged = previousShift != null && (
                previousShift.date != date ||
                    previousShift.startTime != startTime ||
                    previousShift.endTime != endTime ||
                    previousShift.title != cleanTitle
                )
            if (isNewAssignment || scheduleChanged) {
                val notifId = newId("ntf")
                val notifTitle = if (isNewAssignment) "New Shift Assigned" else "Shift Schedule Updated"
                val notifMessage = if (isNewAssignment) {
                    "$cleanTitle on $date ($startTime-$endTime) was assigned to you."
                } else {
                    "Your shift $cleanTitle was updated to $date ($startTime-$endTime)."
                }
                batch.set(
                    db.collection("notifications").document(notifId),
                    mapOf(
                        "id" to notifId,
                        "recipientUid" to effectiveUid,
                        "title" to notifTitle,
                        "message" to notifMessage.take(400),
                        "category" to NotificationCategory.SHIFT_ASSIGNED.value,
                        "relatedShiftId" to shiftId,
                        "isRead" to false,
                        "actorUid" to actorUid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun deleteShift(shiftId: String) {
        requireUserId()
        val docRef = db.collection("shifts").document(shiftId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Schedule Change Requests & Approvals ---

    suspend fun submitScheduleChangeRequest(
        shift: Shift,
        requesterUid: String,
        requesterName: String,
        type: ScheduleChangeType,
        proposedDate: String,
        proposedStartTime: String,
        proposedEndTime: String,
        proposedAssigneeUid: String,
        proposedAssigneeName: String,
        reason: String
    ): String {
        requireUserId()
        val id = newId("chg")
        val docRef = db.collection("scheduleChanges").document(id)
        val payload = mapOf(
            "id" to id,
            "shiftId" to shift.id,
            "shiftTitle" to shift.title,
            "requesterUid" to requesterUid,
            "requesterName" to requesterName,
            "type" to type.value,
            "currentDate" to shift.date,
            "currentStartTime" to shift.startTime,
            "currentEndTime" to shift.endTime,
            "proposedDate" to proposedDate,
            "proposedStartTime" to proposedStartTime,
            "proposedEndTime" to proposedEndTime,
            "proposedAssigneeUid" to proposedAssigneeUid,
            "proposedAssigneeName" to proposedAssigneeName,
            "reason" to reason.trim().take(400),
            "status" to ApprovalStatus.PENDING.value,
            "reviewedByUid" to "",
            "reviewedByName" to "",
            "reviewNote" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
            if (type != ScheduleChangeType.CLAIM && shift.assignedUid == requesterUid) {
                db.collection("shifts").document(shift.id).update(
                    mapOf(
                        "status" to ShiftStatus.PENDING_CHANGE.value,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        return id
    }

    private fun buildShiftApprovalUpdates(req: ScheduleChangeRequest, shift: Shift?): Map<String, Any> {
        val duration = FairnessEngine.computeShiftHours(req.proposedStartTime, req.proposedEndTime)
        return when {
            req.type == ScheduleChangeType.DROP -> mapOf(
                "status" to ShiftStatus.OPEN.value,
                "assignedUid" to "",
                "assignedName" to "",
                "fairnessNote" to "Opened via approved drop request",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            req.proposedAssigneeUid.isNotBlank() && req.proposedAssigneeName.isNotBlank() -> mapOf(
                "date" to req.proposedDate,
                "startTime" to req.proposedStartTime,
                "endTime" to req.proposedEndTime,
                "durationHours" to duration,
                "assignedUid" to req.proposedAssigneeUid,
                "assignedName" to req.proposedAssigneeName,
                "status" to ShiftStatus.SCHEDULED.value,
                "fairnessNote" to "Updated via approved ${req.type.value.replace("_", " ")}",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            else -> mapOf(
                "date" to req.proposedDate,
                "startTime" to req.proposedStartTime,
                "endTime" to req.proposedEndTime,
                "durationHours" to duration,
                "status" to ShiftStatus.SCHEDULED.value,
                "fairnessNote" to "Time updated via approved request (was ${shift?.startTime ?: req.currentStartTime}-${shift?.endTime ?: req.currentEndTime})",
                "updatedAt" to FieldValue.serverTimestamp()
            )
        }
    }

    suspend fun decideScheduleChange(
        req: ScheduleChangeRequest,
        shift: Shift?,
        decision: ApprovalStatus,
        reviewerUid: String,
        reviewerName: String,
        reviewNote: String
    ) {
        requireUserId()
        val batch = db.batch()
        val changeRef = db.collection("scheduleChanges").document(req.id)
        batch.update(
            changeRef,
            mapOf(
                "status" to decision.value,
                "reviewedByUid" to reviewerUid,
                "reviewedByName" to reviewerName,
                "reviewNote" to reviewNote.trim().take(300),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        if (shift != null) {
            val shiftRef = db.collection("shifts").document(shift.id)
            if (decision == ApprovalStatus.APPROVED) {
                batch.update(shiftRef, buildShiftApprovalUpdates(req, shift))
            } else {
                val restoredStatus = if (shift.assignedUid.isBlank()) ShiftStatus.OPEN else ShiftStatus.SCHEDULED
                batch.update(
                    shiftRef,
                    mapOf(
                        "status" to restoredStatus.value,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }

        val notifId = newId("ntf")
        val notifRef = db.collection("notifications").document(notifId)
        val isApproved = decision == ApprovalStatus.APPROVED
        batch.set(
            notifRef,
            mapOf(
                "id" to notifId,
                "recipientUid" to req.requesterUid,
                "title" to if (isApproved) "Schedule Change Approved" else "Schedule Change Denied",
                "message" to if (isApproved) {
                    "Your ${req.type.label.lowercase()} for ${req.shiftTitle} (${req.proposedDate} ${req.proposedStartTime}-${req.proposedEndTime}) was approved by $reviewerName."
                } else {
                    "Your ${req.type.label.lowercase()} for ${req.shiftTitle} was denied by $reviewerName." +
                        if (reviewNote.isNotBlank()) " Note: $reviewNote" else ""
                },
                "category" to if (isApproved) NotificationCategory.SHIFT_APPROVED.value else NotificationCategory.SHIFT_DENIED.value,
                "relatedShiftId" to req.shiftId,
                "isRead" to false,
                "actorUid" to reviewerUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        if (isApproved && req.proposedAssigneeUid.isNotBlank() && req.proposedAssigneeUid != req.requesterUid) {
            val swapNotifId = newId("ntf")
            batch.set(
                db.collection("notifications").document(swapNotifId),
                mapOf(
                    "id" to swapNotifId,
                    "recipientUid" to req.proposedAssigneeUid,
                    "title" to "Shift Reassigned to You",
                    "message" to "${req.shiftTitle} on ${req.proposedDate} (${req.proposedStartTime}-${req.proposedEndTime}) was approved for you by $reviewerName.",
                    "category" to NotificationCategory.SHIFT_ASSIGNED.value,
                    "relatedShiftId" to req.shiftId,
                    "isRead" to false,
                    "actorUid" to reviewerUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, changeRef.path)
            throw e
        }
    }

    suspend fun acceptAllPendingChanges(
        pendingRequests: List<ScheduleChangeRequest>,
        shifts: List<Shift>,
        reviewerUid: String,
        reviewerName: String
    ): Int {
        requireUserId()
        if (pendingRequests.isEmpty()) return 0
        val shiftMap = shifts.associateBy { it.id }

        // Group operations into batches respecting Firestore's 500 write limit
        var batch = db.batch()
        var opCount = 0

        suspend fun flushIfNeeded(needed: Int) {
            if (opCount + needed > CHUNK_SIZE) {
                batch.commit().await()
                batch = db.batch()
                opCount = 0
            }
        }

        try {
            for (req in pendingRequests) {
                val shift = shiftMap[req.shiftId]
                val extraNotif = req.proposedAssigneeUid.isNotBlank() && req.proposedAssigneeUid != req.requesterUid
                val opsNeeded = 2 + (if (shift != null) 1 else 0) + (if (extraNotif) 1 else 0)
                flushIfNeeded(opsNeeded)

                batch.update(
                    db.collection("scheduleChanges").document(req.id),
                    mapOf(
                        "status" to ApprovalStatus.APPROVED.value,
                        "reviewedByUid" to reviewerUid,
                        "reviewedByName" to reviewerName,
                        "reviewNote" to "Approved via Accept All",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                opCount++

                if (shift != null) {
                    batch.update(db.collection("shifts").document(shift.id), buildShiftApprovalUpdates(req, shift))
                    opCount++
                }

                val notifId = newId("ntf")
                batch.set(
                    db.collection("notifications").document(notifId),
                    mapOf(
                        "id" to notifId,
                        "recipientUid" to req.requesterUid,
                        "title" to "Schedule Change Approved",
                        "message" to "Your ${req.type.label.lowercase()} for ${req.shiftTitle} (${req.proposedDate} ${req.proposedStartTime}-${req.proposedEndTime}) was batch-approved by $reviewerName.",
                        "category" to NotificationCategory.SHIFT_APPROVED.value,
                        "relatedShiftId" to req.shiftId,
                        "isRead" to false,
                        "actorUid" to reviewerUid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                opCount++

                if (extraNotif) {
                    val swapNotifId = newId("ntf")
                    batch.set(
                        db.collection("notifications").document(swapNotifId),
                        mapOf(
                            "id" to swapNotifId,
                            "recipientUid" to req.proposedAssigneeUid,
                            "title" to "Shift Reassigned to You",
                            "message" to "${req.shiftTitle} on ${req.proposedDate} (${req.proposedStartTime}-${req.proposedEndTime}) was approved for you by $reviewerName.",
                            "category" to NotificationCategory.SHIFT_ASSIGNED.value,
                            "relatedShiftId" to req.shiftId,
                            "isRead" to false,
                            "actorUid" to reviewerUid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    opCount++
                }
            }
            if (opCount > 0) {
                batch.commit().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "scheduleChanges")
            throw e
        }
        return pendingRequests.size
    }

    // --- Shift Reports ---

    suspend fun submitShiftReport(
        shiftId: String,
        shiftTitle: String,
        staffUid: String,
        staffName: String,
        department: String,
        date: String,
        actualStartTime: String,
        actualEndTime: String,
        breakMinutes: Int,
        handoffNotes: String
    ): String {
        requireUserId()
        val id = newId("rep")
        val workedHours = FairnessEngine.computeShiftHours(actualStartTime, actualEndTime, breakMinutes)
        val docRef = db.collection("shiftReports").document(id)
        val payload = mapOf(
            "id" to id,
            "shiftId" to shiftId,
            "shiftTitle" to shiftTitle.trim().take(120),
            "staffUid" to staffUid,
            "staffName" to staffName,
            "department" to department.trim().take(60),
            "date" to date,
            "actualStartTime" to actualStartTime,
            "actualEndTime" to actualEndTime,
            "breakMinutes" to breakMinutes.coerceIn(0, 180),
            "workedHours" to workedHours,
            "handoffNotes" to handoffNotes.trim().take(600),
            "status" to ShiftReportStatus.SUBMITTED.value,
            "verifiedByUid" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        return id
    }

    suspend fun verifyOrFlagShiftReport(
        report: ShiftReport,
        status: ShiftReportStatus,
        verifierUid: String,
        staffEntry: StaffDirectoryEntry?,
        linkedShift: Shift?
    ) {
        requireUserId()
        val batch = db.batch()
        val reportRef = db.collection("shiftReports").document(report.id)
        batch.update(
            reportRef,
            mapOf(
                "status" to status.value,
                "verifiedByUid" to verifierUid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        if (status == ShiftReportStatus.VERIFIED && report.status != ShiftReportStatus.VERIFIED) {
            if (staffEntry != null) {
                val staffRef = db.collection("staffDirectory").document(staffEntry.uid)
                batch.update(
                    staffRef,
                    mapOf(
                        "totalHoursWorked" to round2(staffEntry.totalHoursWorked + report.workedHours),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            if (linkedShift != null && linkedShift.status != ShiftStatus.COMPLETED) {
                batch.update(
                    db.collection("shifts").document(linkedShift.id),
                    mapOf(
                        "status" to ShiftStatus.COMPLETED.value,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }

        val notifId = newId("ntf")
        batch.set(
            db.collection("notifications").document(notifId),
            mapOf(
                "id" to notifId,
                "recipientUid" to report.staffUid,
                "title" to if (status == ShiftReportStatus.VERIFIED) "Shift Report Verified" else "Shift Report Flagged",
                "message" to if (status == ShiftReportStatus.VERIFIED) {
                    "Your ${FairnessEngine.formatHours(report.workedHours)}h shift report for ${report.shiftTitle} (${report.date}) was verified."
                } else {
                    "Your shift report for ${report.shiftTitle} (${report.date}) was flagged for review."
                },
                "category" to NotificationCategory.REPORT_VERIFIED.value,
                "relatedShiftId" to report.shiftId,
                "isRead" to false,
                "actorUid" to verifierUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, reportRef.path)
            throw e
        }
    }

    // --- Notifications ---

    suspend fun markNotificationRead(notificationId: String, isRead: Boolean) {
        requireUserId()
        val ref = db.collection("notifications").document(notificationId)
        try {
            ref.update(
                mapOf(
                    "isRead" to isRead,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    suspend fun markAllNotificationsRead(notifications: List<PushNotificationRecord>) {
        requireUserId()
        val unread = notifications.filter { !it.isRead }
        if (unread.isEmpty()) return
        try {
            unread.chunked(CHUNK_SIZE).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { n ->
                    batch.update(
                        db.collection("notifications").document(n.id),
                        mapOf(
                            "isRead" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "notifications")
            throw e
        }
    }

    suspend fun sendTestPushNotification(uid: String, displayName: String) {
        val actorUid = requireUserId()
        val id = newId("ntf")
        val ref = db.collection("notifications").document(id)
        try {
            ref.set(
                mapOf(
                    "id" to id,
                    "recipientUid" to uid,
                    "title" to "Cadence Alert Test",
                    "message" to "Real-time notification channel active for $displayName.",
                    "category" to NotificationCategory.SHIFT_ASSIGNED.value,
                    "relatedShiftId" to "",
                    "isRead" to false,
                    "actorUid" to actorUid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            throw e
        }
    }

    // --- Fairness Plans & Weekly Schedule ---

    suspend fun commitFairnessArrangementPlan(
        plan: List<FairnessArrangementItem>,
        actorUid: String
    ): Int {
        requireUserId()
        if (plan.isEmpty()) return 0
        val nowIso = Instant.now().toString()
        var batch = db.batch()
        var ops = 0

        suspend fun flushIfNeeded(needed: Int) {
            if (ops + needed > CHUNK_SIZE) {
                batch.commit().await()
                batch = db.batch()
                ops = 0
            }
        }

        try {
            for (item in plan) {
                flushIfNeeded(3)
                batch.update(
                    db.collection("shifts").document(item.shift.id),
                    mapOf(
                        "assignedUid" to item.chosenUid,
                        "assignedName" to item.chosenName,
                        "status" to ShiftStatus.SCHEDULED.value,
                        "fairnessNote" to item.reason.take(300),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                ops++

                batch.update(
                    db.collection("staffDirectory").document(item.chosenUid),
                    mapOf(
                        "lastAssignedAt" to nowIso,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                ops++

                val notifId = newId("ntf")
                batch.set(
                    db.collection("notifications").document(notifId),
                    mapOf(
                        "id" to notifId,
                        "recipientUid" to item.chosenUid,
                        "title" to "Overlap Resolved by Fairness",
                        "message" to "${item.shift.title} (${item.shift.date} ${item.shift.startTime}-${item.shift.endTime}) assigned to you. ${item.reason}",
                        "category" to NotificationCategory.FAIRNESS_REBALANCED.value,
                        "relatedShiftId" to item.shift.id,
                        "isRead" to false,
                        "actorUid" to actorUid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                ops++
            }
            if (ops > 0) batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "shifts")
            throw e
        }
        return plan.size
    }

    suspend fun commitOpenShiftAssignmentPlan(
        plan: List<OpenShiftAssignmentItem>,
        actorUid: String
    ): Int {
        requireUserId()
        if (plan.isEmpty()) return 0
        val nowIso = Instant.now().toString()
        var batch = db.batch()
        var ops = 0

        suspend fun flushIfNeeded(needed: Int) {
            if (ops + needed > CHUNK_SIZE) {
                batch.commit().await()
                batch = db.batch()
                ops = 0
            }
        }

        try {
            for (item in plan) {
                flushIfNeeded(3)
                batch.update(
                    db.collection("shifts").document(item.shift.id),
                    mapOf(
                        "assignedUid" to item.chosenUid,
                        "assignedName" to item.chosenName,
                        "status" to ShiftStatus.SCHEDULED.value,
                        "fairnessNote" to item.reason.take(300),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                ops++

                batch.update(
                    db.collection("staffDirectory").document(item.chosenUid),
                    mapOf(
                        "lastAssignedAt" to nowIso,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                ops++

                val notifId = newId("ntf")
                batch.set(
                    db.collection("notifications").document(notifId),
                    mapOf(
                        "id" to notifId,
                        "recipientUid" to item.chosenUid,
                        "title" to "Open Shift Assigned",
                        "message" to "${item.shift.title} on ${item.shift.date} (${item.shift.startTime}-${item.shift.endTime}) assigned to you.",
                        "category" to NotificationCategory.SHIFT_ASSIGNED.value,
                        "relatedShiftId" to item.shift.id,
                        "isRead" to false,
                        "actorUid" to actorUid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                ops++
            }
            if (ops > 0) batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "shifts")
            throw e
        }
        return plan.size
    }

    suspend fun commitWeeklyScheduleBatch(
        planned: List<FairnessEngine.GeneratedShiftSpec>,
        actorUid: String
    ): Pair<Int, Int> {
        requireUserId()
        if (planned.isEmpty()) return 0 to 0
        val nowIso = Instant.now().toString()
        var batch = db.batch()
        var ops = 0
        var assignedCount = 0
        var openCount = 0
        val touchedStaffUids = mutableSetOf<String>()

        suspend fun flushIfNeeded(needed: Int) {
            if (ops + needed > CHUNK_SIZE) {
                batch.commit().await()
                batch = db.batch()
                ops = 0
            }
        }

        try {
            for (spec in planned) {
                val shiftId = newId("shift")
                val hasAssignee = spec.status != ShiftStatus.OPEN && spec.assignedUid.isNotBlank()
                if (hasAssignee) assignedCount++ else openCount++

                flushIfNeeded(if (hasAssignee) 2 else 1)
                batch.set(
                    db.collection("shifts").document(shiftId),
                    mapOf(
                        "id" to shiftId,
                        "title" to spec.title,
                        "department" to spec.department,
                        "date" to spec.date,
                        "startTime" to spec.startTime,
                        "endTime" to spec.endTime,
                        "durationHours" to spec.durationHours,
                        "assignedUid" to if (hasAssignee) spec.assignedUid else "",
                        "assignedName" to if (hasAssignee) spec.assignedName else "",
                        "candidateUids" to spec.candidateUids.take(20),
                        "status" to spec.status.value,
                        "visibility" to "team",
                        "fairnessNote" to spec.fairnessNote.take(300),
                        "createdBy" to actorUid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                ops++

                if (hasAssignee) {
                    touchedStaffUids.add(spec.assignedUid)
                    val notifId = newId("ntf")
                    batch.set(
                        db.collection("notifications").document(notifId),
                        mapOf(
                            "id" to notifId,
                            "recipientUid" to spec.assignedUid,
                            "title" to "Weekly Schedule Published",
                            "message" to "You were scheduled for ${spec.title} on ${spec.date} (${spec.startTime}-${spec.endTime}).",
                            "category" to NotificationCategory.SHIFT_ASSIGNED.value,
                            "relatedShiftId" to shiftId,
                            "isRead" to false,
                            "actorUid" to actorUid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    ops++
                }
            }

            for (uid in touchedStaffUids) {
                flushIfNeeded(1)
                batch.update(
                    db.collection("staffDirectory").document(uid),
                    mapOf(
                        "lastAssignedAt" to nowIso,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                ops++
            }

            if (ops > 0) batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "shifts")
            throw e
        }
        return assignedCount to openCount
    }

    // --- Shift Submissions ---

    suspend fun submitShiftAvailabilityBatch(
        staffUid: String,
        staffName: String,
        department: String,
        dates: List<String>,
        startTime: String,
        endTime: String,
        shiftTitle: String,
        notes: String
    ): Int {
        requireUserId()
        if (dates.isEmpty()) return 0
        try {
            dates.distinct().chunked(CHUNK_SIZE).forEach { chunk ->
                val batch = db.batch()
                for (d in chunk) {
                    val id = newId("sub")
                    batch.set(
                        db.collection("shiftSubmissions").document(id),
                        mapOf(
                            "id" to id,
                            "staffUid" to staffUid,
                            "staffName" to staffName,
                            "department" to department.trim().take(60),
                            "date" to d,
                            "startTime" to startTime,
                            "endTime" to endTime,
                            "shiftTitle" to shiftTitle.trim().take(120),
                            "notes" to notes.trim().take(300),
                            "status" to SubmissionStatus.SUBMITTED.value,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "shiftSubmissions")
            throw e
        }
        return dates.distinct().size
    }

    suspend fun withdrawShiftSubmission(submissionId: String) {
        requireUserId()
        val ref = db.collection("shiftSubmissions").document(submissionId)
        try {
            ref.update(
                mapOf(
                    "status" to SubmissionStatus.WITHDRAWN.value,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    // --- Time Off Requests ---

    suspend fun createTimeOffRequest(
        staffUid: String,
        staffName: String,
        department: String,
        startDate: String,
        endDate: String,
        reason: String
    ): String {
        requireUserId()
        val id = newId("to")
        val ref = db.collection("timeOffRequests").document(id)
        val payload = mapOf(
            "id" to id,
            "staffUid" to staffUid,
            "staffName" to staffName,
            "department" to department.trim().take(60),
            "startDate" to startDate,
            "endDate" to endDate,
            "reason" to reason.trim().take(400),
            "status" to ApprovalStatus.PENDING.value,
            "reviewedByUid" to "",
            "reviewedByName" to "",
            "reviewNote" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            ref.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            throw e
        }
        return id
    }

    suspend fun withdrawTimeOffRequest(requestId: String) {
        requireUserId()
        val ref = db.collection("timeOffRequests").document(requestId)
        try {
            ref.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, ref.path)
            throw e
        }
    }

    suspend fun decideTimeOffRequest(
        req: TimeOffRequest,
        decision: ApprovalStatus,
        reviewerUid: String,
        reviewerName: String,
        reviewNote: String
    ) {
        requireUserId()
        val batch = db.batch()
        val ref = db.collection("timeOffRequests").document(req.id)
        batch.update(
            ref,
            mapOf(
                "status" to decision.value,
                "reviewedByUid" to reviewerUid,
                "reviewedByName" to reviewerName,
                "reviewNote" to reviewNote.trim().take(300),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val isApproved = decision == ApprovalStatus.APPROVED
        val notifId = newId("ntf")
        batch.set(
            db.collection("notifications").document(notifId),
            mapOf(
                "id" to notifId,
                "recipientUid" to req.staffUid,
                "title" to if (isApproved) "Time Off Approved" else "Time Off Denied",
                "message" to if (isApproved) {
                    "Your time off request (${req.startDate} to ${req.endDate}) was approved by $reviewerName."
                } else {
                    "Your time off request (${req.startDate} to ${req.endDate}) was denied by $reviewerName." +
                        if (reviewNote.isNotBlank()) " Note: $reviewNote" else ""
                },
                "category" to if (isApproved) NotificationCategory.TIME_OFF_APPROVED.value else NotificationCategory.TIME_OFF_DENIED.value,
                "relatedShiftId" to "",
                "isRead" to false,
                "actorUid" to reviewerUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, ref.path)
            throw e
        }
    }

    // --- Team Roster & Roles ---

    suspend fun addRosterStaffMember(
        displayName: String,
        department: String,
        weeklyHoursTarget: Double,
        role: UserRole
    ): String {
        requireUserId()
        val uid = newId("roster")
        val ref = db.collection("staffDirectory").document(uid)
        try {
            ref.set(
                mapOf(
                    "uid" to uid,
                    "displayName" to displayName.trim().take(80),
                    "role" to role.value,
                    "department" to department.trim().take(60),
                    "totalHoursWorked" to 0.0,
                    "scheduledHoursThisWeek" to 0.0,
                    "weeklyHoursTarget" to weeklyHoursTarget.coerceIn(1.0, 80.0),
                    "lastAssignedAt" to "",
                    "isSampleRoster" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            throw e
        }
        return uid
    }

    suspend fun toggleManagerRole(
        targetEntry: StaffDirectoryEntry,
        grantManager: Boolean,
        actorUid: String
    ) {
        requireUserId()
        val newRole = if (grantManager) UserRole.MANAGER else UserRole.STAFF
        val batch = db.batch()
        val managerRef = db.collection("managers").document(targetEntry.uid)
        val staffRef = db.collection("staffDirectory").document(targetEntry.uid)

        if (grantManager) {
            batch.set(
                managerRef,
                mapOf(
                    "uid" to targetEntry.uid,
                    "grantedBy" to actorUid,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        } else {
            batch.delete(managerRef)
        }

        batch.update(
            staffRef,
            mapOf(
                "role" to newRole.value,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val userRef = db.collection("users").document(targetEntry.uid)
        val userSnap = runCatching { userRef.get().await() }.getOrNull()
        if (userSnap != null && userSnap.exists()) {
            batch.update(
                userRef,
                mapOf(
                    "role" to newRole.value,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
        }

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, staffRef.path)
            throw e
        }
    }

    suspend fun seedSampleWorkspaceData(actorUid: String, actorName: String) {
        requireUserId()
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        val monday = FairnessEngine.getMondayOfWeek(LocalDate.now())
        val day0 = monday.format(fmt)
        val day1 = monday.plusDays(1).format(fmt)
        val day2 = monday.plusDays(2).format(fmt)
        val day3 = monday.plusDays(3).format(fmt)

        val batch = db.batch()

        val sampleStaff = listOf(
            mapOf(
                "uid" to "sample_maya_patel",
                "displayName" to "Maya Patel",
                "role" to "staff",
                "department" to "Operations",
                "totalHoursWorked" to 18.0,
                "scheduledHoursThisWeek" to 8.0,
                "weeklyHoursTarget" to 40.0,
                "lastAssignedAt" to "2026-10-01T08:00:00Z",
                "isSampleRoster" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            mapOf(
                "uid" to "sample_liam_brooks",
                "displayName" to "Liam Brooks",
                "role" to "staff",
                "department" to "Support",
                "totalHoursWorked" to 36.0,
                "scheduledHoursThisWeek" to 16.0,
                "weeklyHoursTarget" to 40.0,
                "lastAssignedAt" to "2026-11-10T08:00:00Z",
                "isSampleRoster" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            mapOf(
                "uid" to "sample_noah_kim",
                "displayName" to "Noah Kim",
                "role" to "staff",
                "department" to "Operations",
                "totalHoursWorked" to 24.5,
                "scheduledHoursThisWeek" to 8.0,
                "weeklyHoursTarget" to 32.0,
                "lastAssignedAt" to "2026-11-05T08:00:00Z",
                "isSampleRoster" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        for (member in sampleStaff) {
            val uid = member["uid"] as String
            batch.set(db.collection("staffDirectory").document(uid), member)
        }

        val shift1Id = "sample_shift_overlap_1"
        batch.set(
            db.collection("shifts").document(shift1Id),
            mapOf(
                "id" to shift1Id,
                "title" to "Morning Operations Lead",
                "department" to "Operations",
                "date" to day1,
                "startTime" to "07:00",
                "endTime" to "15:00",
                "durationHours" to 8.0,
                "assignedUid" to "sample_liam_brooks",
                "assignedName" to "Liam Brooks",
                "candidateUids" to listOf("sample_liam_brooks", "sample_maya_patel"),
                "status" to ShiftStatus.CONFLICT.value,
                "visibility" to "team",
                "fairnessNote" to "2 overlapping candidates — ready for fairness arrange",
                "createdBy" to actorUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val shift2Id = "sample_shift_overlap_2"
        batch.set(
            db.collection("shifts").document(shift2Id),
            mapOf(
                "id" to shift2Id,
                "title" to "Midday Floor Support",
                "department" to "Support",
                "date" to day2,
                "startTime" to "11:00",
                "endTime" to "19:00",
                "durationHours" to 8.0,
                "assignedUid" to "sample_liam_brooks",
                "assignedName" to "Liam Brooks",
                "candidateUids" to listOf("sample_liam_brooks", "sample_noah_kim", "sample_maya_patel"),
                "status" to ShiftStatus.CONFLICT.value,
                "visibility" to "team",
                "fairnessNote" to "3 overlapping candidates — ready for fairness arrange",
                "createdBy" to actorUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val shift3Id = "sample_shift_pending_change"
        batch.set(
            db.collection("shifts").document(shift3Id),
            mapOf(
                "id" to shift3Id,
                "title" to "Closing Desk Coverage",
                "department" to "Operations",
                "date" to day0,
                "startTime" to "15:00",
                "endTime" to "23:00",
                "durationHours" to 8.0,
                "assignedUid" to "sample_noah_kim",
                "assignedName" to "Noah Kim",
                "candidateUids" to listOf("sample_noah_kim"),
                "status" to ShiftStatus.PENDING_CHANGE.value,
                "visibility" to "team",
                "fairnessNote" to "Change request pending manager decision",
                "createdBy" to actorUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val shift4Id = "sample_shift_open_slot"
        batch.set(
            db.collection("shifts").document(shift4Id),
            mapOf(
                "id" to shift4Id,
                "title" to "Evening Dispatch",
                "department" to "Operations",
                "date" to day3,
                "startTime" to "15:00",
                "endTime" to "23:00",
                "durationHours" to 8.0,
                "assignedUid" to "",
                "assignedName" to "",
                "candidateUids" to listOf("sample_maya_patel", "sample_noah_kim"),
                "status" to ShiftStatus.OPEN.value,
                "visibility" to "team",
                "fairnessNote" to "Open shift awaiting fairness assignment",
                "createdBy" to actorUid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val chg1Id = "sample_change_req_1"
        batch.set(
            db.collection("scheduleChanges").document(chg1Id),
            mapOf(
                "id" to chg1Id,
                "shiftId" to shift3Id,
                "shiftTitle" to "Closing Desk Coverage",
                "requesterUid" to "sample_noah_kim",
                "requesterName" to "Noah Kim",
                "type" to ScheduleChangeType.SWAP.value,
                "currentDate" to day0,
                "currentStartTime" to "15:00",
                "currentEndTime" to "23:00",
                "proposedDate" to day0,
                "proposedStartTime" to "15:00",
                "proposedEndTime" to "23:00",
                "proposedAssigneeUid" to "sample_maya_patel",
                "proposedAssigneeName" to "Maya Patel",
                "reason" to "Swapping with Maya to balance weekly hours.",
                "status" to ApprovalStatus.PENDING.value,
                "reviewedByUid" to "",
                "reviewedByName" to "",
                "reviewNote" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val chg2Id = "sample_change_req_2"
        batch.set(
            db.collection("scheduleChanges").document(chg2Id),
            mapOf(
                "id" to chg2Id,
                "shiftId" to shift1Id,
                "shiftTitle" to "Morning Operations Lead",
                "requesterUid" to "sample_liam_brooks",
                "requesterName" to "Liam Brooks",
                "type" to ScheduleChangeType.TIME_CHANGE.value,
                "currentDate" to day1,
                "currentStartTime" to "07:00",
                "currentEndTime" to "15:00",
                "proposedDate" to day1,
                "proposedStartTime" to "08:00",
                "proposedEndTime" to "16:00",
                "proposedAssigneeUid" to "",
                "proposedAssigneeName" to "",
                "reason" to "Transit schedule starts 1 hour later.",
                "status" to ApprovalStatus.PENDING.value,
                "reviewedByUid" to "",
                "reviewedByName" to "",
                "reviewNote" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val repId = "sample_report_1"
        batch.set(
            db.collection("shiftReports").document(repId),
            mapOf(
                "id" to repId,
                "shiftId" to "",
                "shiftTitle" to "Morning Operations Lead",
                "staffUid" to actorUid,
                "staffName" to actorName,
                "department" to "Operations",
                "date" to day0,
                "actualStartTime" to "07:00",
                "actualEndTime" to "15:30",
                "breakMinutes" to 30,
                "workedHours" to 8.0,
                "handoffNotes" to "Checklist complete; inventory restocked for midday team.",
                "status" to ShiftReportStatus.SUBMITTED.value,
                "verifiedByUid" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        val toId = "sample_timeoff_1"
        batch.set(
            db.collection("timeOffRequests").document(toId),
            mapOf(
                "id" to toId,
                "staffUid" to "sample_maya_patel",
                "staffName" to "Maya Patel",
                "department" to "Operations",
                "startDate" to monday.plusDays(4).format(fmt),
                "endDate" to monday.plusDays(5).format(fmt),
                "reason" to "Family weekend out of town.",
                "status" to ApprovalStatus.PENDING.value,
                "reviewedByUid" to "",
                "reviewedByName" to "",
                "reviewNote" to "",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        )

        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "staffDirectory")
            throw e
        }
    }
}
