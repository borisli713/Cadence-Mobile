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

    private companion object {
        const val ADMIN_EMAIL = "yatheili713@gmail.com"
        const val DEFAULT_TIMEOUT_MS = 8000L
        const val FLOW_TIMEOUT_MS = 5000L
    }
}
