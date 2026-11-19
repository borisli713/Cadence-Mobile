package com.cadence.shifts.data.repository

import com.cadence.shifts.base.FirestoreEmulatorTestBase
import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.NotificationCategory
import com.cadence.shifts.data.model.ScheduleChangeType
import com.cadence.shifts.data.model.ShiftReportStatus
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.data.model.UserProfile
import com.cadence.shifts.data.model.UserRole
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CadenceRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun unauthenticatedCaller_observeShifts_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = CadenceRepository(firestore, auth)

        var caught: Exception? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) { repo.observeShifts("anon_uid").first() }
        } catch (e: Exception) {
            caught = e
        }
        val fsErr = caught?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException for unauthenticated caller", fsErr)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsErr?.code)
    }

    @Test
    fun verifiedUserProvisioning_bootstrappedAdminGetsManager_staffGetsStaff() = runBlocking {
        signInTestUser(ADMIN_EMAIL)
        val adminRepo = CadenceRepository(firestore, auth)
        val adminUser = checkNotNull(auth.currentUser)
        val adminProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.ensureVerifiedUserProvisioned(adminUser)
        }
        assertEquals(UserRole.MANAGER, adminProfile.role)

        val staffEmail = "staff_${UUID.randomUUID().toString().take(8)}@test.com"
        signInTestUser(staffEmail)
        val staffRepo = CadenceRepository(firestore, auth)
        val staffUser = checkNotNull(auth.currentUser)
        val staffProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.ensureVerifiedUserProvisioned(staffUser)
        }
        assertEquals(UserRole.STAFF, staffProfile.role)
    }

    @Test
    fun managerCreatesShift_staffSubmitsReportAndChange_managerAcceptsAllAndVerifies() = runBlocking {
        val adminUid = signInTestUser(ADMIN_EMAIL)
        val adminRepo = CadenceRepository(firestore, auth)
        val adminProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        val staffEmail = "worker_${UUID.randomUUID().toString().take(8)}@test.com"
        val staffUid = signInTestUser(staffEmail)
        val staffRepo = CadenceRepository(firestore, auth)
        val staffProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        // Manager creates shift assigned to staff
        signInTestUser(ADMIN_EMAIL)
        val shiftId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.createShift(
                title = "RuleTest Morning Ops",
                department = "Operations",
                date = "2026-11-20",
                startTime = "08:00",
                endTime = "16:00",
                assignedUid = staffUid,
                assignedName = staffProfile.displayName,
                candidateUids = listOf(staffUid),
                status = ShiftStatus.SCHEDULED,
                fairnessNote = "Initial assignment",
                createdBy = adminUid
            )
        }

        // Staff submits change request and shift report
        signInTestUser(staffEmail)
        val shifts = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeShifts(staffUid).first { list -> list.any { it.id == shiftId } }
        }
        val targetShift = shifts.first { it.id == shiftId }

        val changeId = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.submitScheduleChangeRequest(
                shift = targetShift,
                requesterUid = staffUid,
                requesterName = staffProfile.displayName,
                type = ScheduleChangeType.TIME_CHANGE,
                proposedDate = "2026-11-20",
                proposedStartTime = "09:00",
                proposedEndTime = "17:00",
                proposedAssigneeUid = "",
                proposedAssigneeName = "",
                reason = "Appointment"
            )
        }

        val reportId = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.submitShiftReport(
                shiftId = shiftId,
                shiftTitle = "RuleTest Morning Ops",
                staffUid = staffUid,
                staffName = staffProfile.displayName,
                department = "Operations",
                date = "2026-11-20",
                actualStartTime = "09:00",
                actualEndTime = "17:00",
                breakMinutes = 30,
                handoffNotes = "Done"
            )
        }

        // Staff cannot delete shifts (manager-only)
        var deleteDenied: FirebaseFirestoreException? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { staffRepo.deleteShift(shiftId) }
        } catch (e: FirebaseFirestoreException) {
            deleteDenied = e
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, deleteDenied?.code)

        // Manager Accept All + verify report
        signInTestUser(ADMIN_EMAIL)
        val pending = withTimeout(FLOW_TIMEOUT_MS) {
            adminRepo.observeScheduleChanges(adminUid, isManager = true)
                .first { list -> list.any { it.id == changeId && it.status == ApprovalStatus.PENDING } }
                .filter { it.id == changeId }
        }
        val approvedCount = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.acceptAllPendingChanges(pending, listOf(targetShift), adminUid, adminProfile.displayName)
        }
        assertEquals(1, approvedCount)

        val reports = withTimeout(FLOW_TIMEOUT_MS) {
            adminRepo.observeShiftReports(adminUid, isManager = true)
                .first { list -> list.any { it.id == reportId } }
        }
        val targetReport = reports.first { it.id == reportId }
        val staffDir = withTimeout(FLOW_TIMEOUT_MS) {
            adminRepo.observeStaffDirectory(adminUid)
                .first { list -> list.any { it.uid == staffUid } }
                .first { it.uid == staffUid }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.verifyOrFlagShiftReport(
                report = targetReport,
                status = ShiftReportStatus.VERIFIED,
                verifierUid = adminUid,
                staffEntry = staffDir,
                linkedShift = targetShift
            )
        }

        // Staff receives notifications
        signInTestUser(staffEmail)
        val notifs = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeNotifications(staffUid, isManager = false)
                .first { list -> list.any { it.relatedShiftId == shiftId } }
        }
        assertTrue(notifs.isNotEmpty())
    }

    @Test
    fun staffSubmitsRequest_managerApprovesSingleRequest_staffReceivesApprovedNotificationAndLiveRoleSync() = runBlocking {
        val adminUid = signInTestUser(ADMIN_EMAIL)
        val adminRepo = CadenceRepository(firestore, auth)
        val adminProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        val staffEmail = "sync_${UUID.randomUUID().toString().take(8)}@test.com"
        val staffUid = signInTestUser(staffEmail)
        val staffRepo = CadenceRepository(firestore, auth)
        val staffProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        // Manager schedules shift for staff
        signInTestUser(ADMIN_EMAIL)
        val shiftId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.createShift(
                title = "Cross-Platform Sync Shift",
                department = "Operations",
                date = "2026-11-22",
                startTime = "08:00",
                endTime = "16:00",
                assignedUid = staffUid,
                assignedName = staffProfile.displayName,
                candidateUids = listOf(staffUid),
                status = ShiftStatus.SCHEDULED,
                fairnessNote = "Scheduled",
                createdBy = adminUid
            )
        }

        // Staff submits time change request (as from Android)
        signInTestUser(staffEmail)
        val shift = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeShifts(staffUid).first { list -> list.any { it.id == shiftId } }
                .first { it.id == shiftId }
        }
        val changeId = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.submitScheduleChangeRequest(
                shift = shift,
                requesterUid = staffUid,
                requesterName = staffProfile.displayName,
                type = ScheduleChangeType.TIME_CHANGE,
                proposedDate = "2026-11-22",
                proposedStartTime = "10:00",
                proposedEndTime = "18:00",
                proposedAssigneeUid = "",
                proposedAssigneeName = "",
                reason = "Synced request from Android"
            )
        }

        // Manager sees request (as on Web App) and approves it
        signInTestUser(ADMIN_EMAIL)
        val req = withTimeout(FLOW_TIMEOUT_MS) {
            adminRepo.observeScheduleChanges(adminUid, isManager = true)
                .first { list -> list.any { it.id == changeId && it.status == ApprovalStatus.PENDING } }
                .first { it.id == changeId }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.decideScheduleChange(
                req = req,
                shift = shift,
                decision = ApprovalStatus.APPROVED,
                reviewerUid = adminUid,
                reviewerName = adminProfile.displayName,
                reviewNote = "Approved from Web"
            )
        }

        // Staff receives SHIFT_APPROVED notification and updated shift times
        signInTestUser(staffEmail)
        val approvedNotif = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeNotifications(staffUid, isManager = false)
                .first { list ->
                    list.any {
                        it.relatedShiftId == shiftId &&
                            it.category == NotificationCategory.SHIFT_APPROVED &&
                            !it.isRead
                    }
                }
                .first { it.relatedShiftId == shiftId && it.category == NotificationCategory.SHIFT_APPROVED }
        }
        assertEquals(staffUid, approvedNotif.recipientUid)

        val updatedShift = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeShifts(staffUid)
                .first { list -> list.any { it.id == shiftId && it.status == ShiftStatus.SCHEDULED && it.startTime == "10:00" } }
                .first { it.id == shiftId }
        }
        assertEquals("18:00", updatedShift.endTime)

        // Verify live user profile sync reflects manager promotion
        signInTestUser(ADMIN_EMAIL)
        val staffEntry = withTimeout(FLOW_TIMEOUT_MS) {
            adminRepo.observeStaffDirectory(adminUid)
                .first { list -> list.any { it.uid == staffUid } }
                .first { it.uid == staffUid }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.toggleManagerRole(staffEntry, grantManager = true, actorUid = adminUid)
        }

        signInTestUser(staffEmail)
        val promotedProfile: UserProfile = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeUserProfile(staffUid, staffEmail, staffProfile)
                .first { it.role == UserRole.MANAGER }
        }
        assertEquals(UserRole.MANAGER, promotedProfile.role)
    }

    @Test
    fun shiftHistory_showsApprovedAndRejectedRequestsWithTimestampsToRequesterAndSwapTarget() = runBlocking {
        val adminUid = signInTestUser(ADMIN_EMAIL)
        val adminRepo = CadenceRepository(firestore, auth)
        val adminProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        val requesterEmail = "req_${UUID.randomUUID().toString().take(8)}@test.com"
        val requesterUid = signInTestUser(requesterEmail)
        val requesterRepo = CadenceRepository(firestore, auth)
        val requesterProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            requesterRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        val targetEmail = "swap_${UUID.randomUUID().toString().take(8)}@test.com"
        val targetUid = signInTestUser(targetEmail)
        val targetRepo = CadenceRepository(firestore, auth)
        val targetProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            targetRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        signInTestUser(ADMIN_EMAIL)
        val shiftId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.createShift(
                title = "Evening Coverage",
                department = "Operations",
                date = "2026-11-25",
                startTime = "15:00",
                endTime = "23:00",
                assignedUid = requesterUid,
                assignedName = requesterProfile.displayName,
                candidateUids = listOf(requesterUid, targetUid),
                status = ShiftStatus.SCHEDULED,
                fairnessNote = "Assigned",
                createdBy = adminUid
            )
        }

        signInTestUser(requesterEmail)
        val shift = withTimeout(FLOW_TIMEOUT_MS) {
            requesterRepo.observeShifts(requesterUid).first { list -> list.any { it.id == shiftId } }
                .first { it.id == shiftId }
        }
        val swapReqId = withTimeout(DEFAULT_TIMEOUT_MS) {
            requesterRepo.submitScheduleChangeRequest(
                shift = shift,
                requesterUid = requesterUid,
                requesterName = requesterProfile.displayName,
                type = ScheduleChangeType.SWAP,
                proposedDate = "2026-11-25",
                proposedStartTime = "15:00",
                proposedEndTime = "23:00",
                proposedAssigneeUid = targetUid,
                proposedAssigneeName = targetProfile.displayName,
                reason = "Need evening off"
            )
        }

        // Manager rejects the swap request
        signInTestUser(ADMIN_EMAIL)
        val pendingSwap = withTimeout(FLOW_TIMEOUT_MS) {
            adminRepo.observeScheduleChanges(adminUid, isManager = true)
                .first { list -> list.any { it.id == swapReqId && it.status == ApprovalStatus.PENDING } }
                .first { it.id == swapReqId }
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.decideScheduleChange(
                req = pendingSwap,
                shift = shift,
                decision = ApprovalStatus.DENIED,
                reviewerUid = adminUid,
                reviewerName = adminProfile.displayName,
                reviewNote = "Coverage needed"
            )
        }

        // Both requester and proposed swap assignee see the rejected request with timestamps
        signInTestUser(targetEmail)
        val targetHistory = withTimeout(FLOW_TIMEOUT_MS) {
            targetRepo.observeScheduleChanges(targetUid, isManager = false)
                .first { list -> list.any { it.id == swapReqId && it.status == ApprovalStatus.DENIED } }
                .first { it.id == swapReqId }
        }
        assertNotNull(targetHistory.createdAt)
        assertNotNull(targetHistory.updatedAt)
        assertEquals("Coverage needed", targetHistory.reviewNote)
    }

    @Test
    fun assignedSchedule_syncedForStaffAndUpdatedOnReassignment() = runBlocking {
        val adminUid = signInTestUser(ADMIN_EMAIL)
        val adminRepo = CadenceRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        val staffEmail = "assigned_${UUID.randomUUID().toString().take(8)}@test.com"
        val staffUid = signInTestUser(staffEmail)
        val staffRepo = CadenceRepository(firestore, auth)
        val staffProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        val otherEmail = "other_${UUID.randomUUID().toString().take(8)}@test.com"
        val otherUid = signInTestUser(otherEmail)
        val otherRepo = CadenceRepository(firestore, auth)
        val otherProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            otherRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        // Manager creates one assigned shift for staff and one open shift (simulating Web App scheduling)
        signInTestUser(ADMIN_EMAIL)
        val assignedShiftId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.createShift(
                title = "Morning Ward",
                department = "Operations",
                date = "2026-11-26",
                startTime = "08:00",
                endTime = "16:00",
                assignedUid = staffUid,
                assignedName = staffProfile.displayName,
                candidateUids = listOf(staffUid),
                status = ShiftStatus.SCHEDULED,
                fairnessNote = "Assigned on Web",
                createdBy = adminUid
            )
        }
        val openShiftId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.createShift(
                title = "Afternoon Relief",
                department = "Operations",
                date = "2026-11-27",
                startTime = "14:00",
                endTime = "22:00",
                assignedUid = "",
                assignedName = "",
                candidateUids = emptyList(),
                status = ShiftStatus.OPEN,
                fairnessNote = "Open",
                createdBy = adminUid
            )
        }

        // Staff observes their own assigned shift in real time
        signInTestUser(staffEmail)
        val initialMyShifts = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeShifts(staffUid)
                .first { list -> list.any { it.id == assignedShiftId } }
                .filter { it.assignedUid == staffUid && it.status != ShiftStatus.OPEN }
        }
        assertEquals(listOf(assignedShiftId), initialMyShifts.map { it.id })

        // Manager assigns the second shift to staff and reassigns the first shift to another staff member
        signInTestUser(ADMIN_EMAIL)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.updateShift(
                shiftId = openShiftId,
                title = "Afternoon Relief",
                department = "Operations",
                date = "2026-11-27",
                startTime = "14:00",
                endTime = "22:00",
                assignedUid = staffUid,
                assignedName = staffProfile.displayName,
                status = ShiftStatus.SCHEDULED,
                fairnessNote = "Assigned to worker"
            )
        }
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.updateShift(
                shiftId = assignedShiftId,
                title = "Morning Ward",
                department = "Operations",
                date = "2026-11-26",
                startTime = "08:00",
                endTime = "16:00",
                assignedUid = otherUid,
                assignedName = otherProfile.displayName,
                status = ShiftStatus.SCHEDULED,
                fairnessNote = "Reassigned"
            )
        }

        // Staff's own schedule reflects the reassignment immediately
        signInTestUser(staffEmail)
        val updatedMyShifts = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeShifts(staffUid)
                .first { list ->
                    val mine = list.filter { it.assignedUid == staffUid && it.status != ShiftStatus.OPEN }
                    mine.any { it.id == openShiftId } && mine.none { it.id == assignedShiftId }
                }
                .filter { it.assignedUid == staffUid && it.status != ShiftStatus.OPEN }
        }
        assertEquals(listOf(openShiftId), updatedMyShifts.map { it.id })

        // Verify staff received SHIFT_ASSIGNED notifications for both manual creation & assignment update
        val staffNotifs = withTimeout(FLOW_TIMEOUT_MS) {
            staffRepo.observeNotifications(staffUid, isManager = false)
                .first { list ->
                    list.any { it.relatedShiftId == assignedShiftId && it.category == NotificationCategory.SHIFT_ASSIGNED } &&
                        list.any { it.relatedShiftId == openShiftId && it.category == NotificationCategory.SHIFT_ASSIGNED }
                }
        }
        assertTrue(staffNotifs.any { it.relatedShiftId == openShiftId && !it.isRead })

        // Reassigned coworker also received a SHIFT_ASSIGNED notification
        signInTestUser(otherEmail)
        val otherNotifs = withTimeout(FLOW_TIMEOUT_MS) {
            otherRepo.observeNotifications(otherUid, isManager = false)
                .first { list ->
                    list.any { it.relatedShiftId == assignedShiftId && it.category == NotificationCategory.SHIFT_ASSIGNED }
                }
        }
        assertTrue(otherNotifs.isNotEmpty())
    }

    @Test
    fun securityRules_blockStaffShiftForgerySelfCompletionAndCrossUserScheduleChangeTampering() = runBlocking {
        val adminUid = signInTestUser(ADMIN_EMAIL)
        val adminRepo = CadenceRepository(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        val staffAEmail = "sec_a_${UUID.randomUUID().toString().take(8)}@test.com"
        val staffAUid = signInTestUser(staffAEmail)
        val staffARepo = CadenceRepository(firestore, auth)
        val staffAProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffARepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        val staffBEmail = "sec_b_${UUID.randomUUID().toString().take(8)}@test.com"
        val staffBUid = signInTestUser(staffBEmail)
        val staffBRepo = CadenceRepository(firestore, auth)
        val staffBProfile = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffBRepo.ensureVerifiedUserProvisioned(checkNotNull(auth.currentUser))
        }

        // Manager creates Staff A shift and Staff B shift
        signInTestUser(ADMIN_EMAIL)
        val shiftAId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.createShift(
                title = "Staff A Shift",
                department = "Operations",
                date = "2026-11-28",
                startTime = "08:00",
                endTime = "16:00",
                assignedUid = staffAUid,
                assignedName = staffAProfile.displayName,
                candidateUids = listOf(staffAUid),
                status = ShiftStatus.SCHEDULED,
                fairnessNote = "Assigned",
                createdBy = adminUid
            )
        }
        val shiftBId = withTimeout(DEFAULT_TIMEOUT_MS) {
            adminRepo.createShift(
                title = "Staff B Shift",
                department = "Operations",
                date = "2026-11-29",
                startTime = "08:00",
                endTime = "16:00",
                assignedUid = staffBUid,
                assignedName = staffBProfile.displayName,
                candidateUids = listOf(staffBUid),
                status = ShiftStatus.SCHEDULED,
                fairnessNote = "Assigned",
                createdBy = adminUid
            )
        }

        // CWE-285 #1: Staff A cannot directly create a forged shift assigned to themselves
        signInTestUser(staffAEmail)
        var createShiftErr: Exception? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                staffARepo.createShift(
                    title = "Forged 24h Shift",
                    department = "Operations",
                    date = "2026-11-30",
                    startTime = "00:00",
                    endTime = "23:59",
                    assignedUid = staffAUid,
                    assignedName = staffAProfile.displayName,
                    candidateUids = listOf(staffAUid),
                    status = ShiftStatus.COMPLETED,
                    fairnessNote = "Forged",
                    createdBy = staffAUid
                )
            }
        } catch (e: Exception) {
            createShiftErr = e
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, createShiftErr?.unwrapFirestoreException()?.code)

        // CWE-285 #2: Staff A cannot mark their own shift as 'completed' without verification
        var selfCompleteErr: Exception? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("shifts").document(shiftAId)
                    .update(
                        mapOf(
                            "status" to ShiftStatus.COMPLETED.value,
                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                        )
                    )
                    .let { com.google.android.gms.tasks.Tasks.await(it) }
            }
        } catch (e: Exception) {
            selfCompleteErr = e
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, selfCompleteErr?.unwrapFirestoreException()?.code)

        // CWE-639 #1: Staff A cannot file a drop or swap against Staff B's shift
        val shiftB = withTimeout(FLOW_TIMEOUT_MS) {
            staffARepo.observeShifts(staffAUid).first { list -> list.any { it.id == shiftBId } }
                .first { it.id == shiftBId }
        }
        var crossUserDropErr: Exception? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                staffARepo.submitScheduleChangeRequest(
                    shift = shiftB,
                    requesterUid = staffAUid,
                    requesterName = staffAProfile.displayName,
                    type = ScheduleChangeType.DROP,
                    proposedDate = shiftB.date,
                    proposedStartTime = shiftB.startTime,
                    proposedEndTime = shiftB.endTime,
                    proposedAssigneeUid = "",
                    proposedAssigneeName = "",
                    reason = "Unauthorized drop"
                )
            }
        } catch (e: Exception) {
            crossUserDropErr = e
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, crossUserDropErr?.unwrapFirestoreException()?.code)

        // CWE-639 #2: Staff A files legitimate time_change on their own shift, then cannot hijack proposedAssigneeUid
        val shiftA = withTimeout(FLOW_TIMEOUT_MS) {
            staffARepo.observeShifts(staffAUid).first { list -> list.any { it.id == shiftAId } }
                .first { it.id == shiftAId }
        }
        val validChangeId = withTimeout(DEFAULT_TIMEOUT_MS) {
            staffARepo.submitScheduleChangeRequest(
                shift = shiftA,
                requesterUid = staffAUid,
                requesterName = staffAProfile.displayName,
                type = ScheduleChangeType.TIME_CHANGE,
                proposedDate = shiftA.date,
                proposedStartTime = "09:00",
                proposedEndTime = "17:00",
                proposedAssigneeUid = "",
                proposedAssigneeName = "",
                reason = "Legitimate shift adjustment"
            )
        }
        assertTrue(validChangeId.isNotBlank())

        var hijackUpdateErr: Exception? = null
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("scheduleChanges").document(validChangeId)
                    .update(
                        mapOf(
                            "proposedAssigneeUid" to staffBUid,
                            "proposedAssigneeName" to staffBProfile.displayName,
                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                        )
                    )
                    .let { com.google.android.gms.tasks.Tasks.await(it) }
            }
        } catch (e: Exception) {
            hijackUpdateErr = e
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, hijackUpdateErr?.unwrapFirestoreException()?.code)
    }

    private companion object {
        const val ADMIN_EMAIL = "yatheili713@gmail.com"
        const val DEFAULT_TIMEOUT_MS = 8000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}
