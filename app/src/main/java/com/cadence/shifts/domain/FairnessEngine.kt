package com.cadence.shifts.domain

import com.cadence.shifts.data.model.ApprovalStatus
import com.cadence.shifts.data.model.FairnessArrangementItem
import com.cadence.shifts.data.model.FairnessScoreBreakdown
import com.cadence.shifts.data.model.OpenShiftAssignmentItem
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftStatus
import com.cadence.shifts.data.model.ShiftSubmission
import com.cadence.shifts.data.model.StaffDirectoryEntry
import com.cadence.shifts.data.model.SubmissionStatus
import com.cadence.shifts.data.model.TimeOffConflict
import com.cadence.shifts.data.model.TimeOffRequest
import com.cadence.shifts.data.model.WeekSlotConfig
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.round

object FairnessEngine {

    val DEFAULT_WEEK_SLOTS: List<WeekSlotConfig> = listOf(
        WeekSlotConfig("morning", "Morning", "Morning Operations", "Operations", "07:00", "15:00"),
        WeekSlotConfig("midday", "Midday", "Midday Floor Support", "Support", "11:00", "19:00"),
        WeekSlotConfig("closing", "Closing", "Closing Operations", "Operations", "15:00", "23:00")
    )

    private val isoDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parseTimeToMinutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        if (parts.size != 2) return 0
        val h = parts[0].toIntOrNull() ?: 0
        val m = parts[1].toIntOrNull() ?: 0
        return h * 60 + m
    }

    fun computeShiftHours(startTime: String, endTime: String, breakMinutes: Int = 0): Double {
        val s = parseTimeToMinutes(startTime)
        var e = parseTimeToMinutes(endTime)
        if (e <= s) {
            e += 24 * 60
        }
        val netMinutes = max(15, (e - s) - breakMinutes)
        return round((netMinutes / 60.0) * 100.0) / 100.0
    }

    fun shiftsTimeOverlap(
        dateA: String,
        startA: String,
        endA: String,
        dateB: String,
        startB: String,
        endB: String
    ): Boolean {
        if (dateA != dateB) return false
        val sA = parseTimeToMinutes(startA)
        var eA = parseTimeToMinutes(endA)
        if (eA <= sA) eA += 24 * 60

        val sB = parseTimeToMinutes(startB)
        var eB = parseTimeToMinutes(endB)
        if (eB <= sB) eB += 24 * 60

        return sA < eB && sB < eA
    }

    fun findStaffConflicts(
        shifts: List<Shift>,
        targetUid: String,
        date: String,
        startTime: String,
        endTime: String,
        excludeShiftId: String? = null
    ): List<Shift> {
        if (targetUid.isBlank()) return emptyList()
        return shifts.filter { s ->
            s.id != excludeShiftId &&
                s.status != ShiftStatus.OPEN &&
                s.assignedUid == targetUid &&
                shiftsTimeOverlap(s.date, s.startTime, s.endTime, date, startTime, endTime)
        }
    }

    fun isStaffOnApprovedTimeOff(
        staffUid: String,
        date: String,
        timeOffRequests: List<TimeOffRequest>
    ): TimeOffConflict {
        if (staffUid.isBlank() || date.isBlank() || timeOffRequests.isEmpty()) {
            return TimeOffConflict(false)
        }
        val match = timeOffRequests.firstOrNull { req ->
            req.staffUid == staffUid &&
                req.status == ApprovalStatus.APPROVED &&
                date >= req.startDate &&
                date <= req.endDate
        }
        return if (match != null) {
            TimeOffConflict(true, match.reason)
        } else {
            TimeOffConflict(false)
        }
    }

    fun computeScheduledHoursByUid(shifts: List<Shift>): Map<String, Double> {
        val map = mutableMapOf<String, Double>()
        for (shift in shifts) {
            if (shift.assignedUid.isNotBlank() && shift.status != ShiftStatus.OPEN) {
                map[shift.assignedUid] = (map[shift.assignedUid] ?: 0.0) + shift.durationHours
            }
        }
        return map
    }

    fun rankCandidatesByFairness(
        candidateUids: List<String>,
        staffDirectory: List<StaffDirectoryEntry>,
        allShifts: List<Shift>,
        targetDate: String,
        startTime: String,
        endTime: String,
        excludeShiftId: String? = null,
        timeOffRequests: List<TimeOffRequest> = emptyList()
    ): List<FairnessScoreBreakdown> {
        val scheduledByUid = computeScheduledHoursByUid(allShifts)
        val countByUid = mutableMapOf<String, Int>()
        for (s in allShifts) {
            if (s.assignedUid.isNotBlank() && s.status != ShiftStatus.OPEN) {
                countByUid[s.assignedUid] = (countByUid[s.assignedUid] ?: 0) + 1
            }
        }

        val pool = if (candidateUids.isNotEmpty()) {
            staffDirectory.filter { it.uid in candidateUids }
        } else {
            staffDirectory
        }

        return pool.map { staff ->
            val scheduled = scheduledByUid[staff.uid] ?: staff.scheduledHoursThisWeek
            val target = max(1.0, staff.weeklyHoursTarget)
            val ratio = (staff.totalHoursWorked + scheduled) / target
            val hasConflict = findStaffConflicts(
                shifts = allShifts,
                targetUid = staff.uid,
                date = targetDate,
                startTime = startTime,
                endTime = endTime,
                excludeShiftId = excludeShiftId
            ).isNotEmpty()
            val timeOff = isStaffOnApprovedTimeOff(staff.uid, targetDate, timeOffRequests)

            FairnessScoreBreakdown(
                uid = staff.uid,
                displayName = staff.displayName,
                department = staff.department,
                totalHoursWorked = staff.totalHoursWorked,
                scheduledHoursThisWeek = round(scheduled * 10.0) / 10.0,
                weeklyHoursTarget = target,
                utilizationRatio = ratio,
                shiftsThisWeekCount = countByUid[staff.uid] ?: 0,
                lastAssignedAt = staff.lastAssignedAt,
                hasConflict = hasConflict,
                hasApprovedTimeOff = timeOff.hasTimeOff,
                timeOffReason = timeOff.reason
            )
        }.sortedWith(
            Comparator { a, b ->
                val aBlocked = a.hasConflict || a.hasApprovedTimeOff
                val bBlocked = b.hasConflict || b.hasApprovedTimeOff
                if (aBlocked != bBlocked) return@Comparator if (aBlocked) 1 else -1

                val ratioCmp = a.utilizationRatio.compareTo(b.utilizationRatio)
                if (kotlin.math.abs(ratioCmp) > 0 && kotlin.math.abs(a.utilizationRatio - b.utilizationRatio) > 0.001) {
                    return@Comparator if (a.utilizationRatio < b.utilizationRatio) -1 else 1
                }

                if (a.shiftsThisWeekCount != b.shiftsThisWeekCount) {
                    return@Comparator a.shiftsThisWeekCount - b.shiftsThisWeekCount
                }

                if (kotlin.math.abs(a.totalHoursWorked - b.totalHoursWorked) > 0.001) {
                    return@Comparator if (a.totalHoursWorked < b.totalHoursWorked) -1 else 1
                }

                val aTime = a.lastAssignedAt.ifBlank { "0000" }
                val bTime = b.lastAssignedAt.ifBlank { "0000" }
                if (aTime != bTime) return@Comparator aTime.compareTo(bTime)

                a.displayName.compareTo(b.displayName)
            }
        )
    }

    fun pickFairestStaffForShift(
        candidateUids: List<String>,
        staffDirectory: List<StaffDirectoryEntry>,
        allShifts: List<Shift>,
        targetDate: String,
        startTime: String,
        endTime: String,
        excludeShiftId: String? = null,
        timeOffRequests: List<TimeOffRequest> = emptyList()
    ): FairnessScoreBreakdown? {
        val ranked = rankCandidatesByFairness(
            candidateUids = candidateUids,
            staffDirectory = staffDirectory,
            allShifts = allShifts,
            targetDate = targetDate,
            startTime = startTime,
            endTime = endTime,
            excludeShiftId = excludeShiftId,
            timeOffRequests = timeOffRequests
        )
        return ranked.firstOrNull { !it.hasConflict && !it.hasApprovedTimeOff } ?: ranked.firstOrNull()
    }

    fun buildFairnessArrangementPlan(
        shifts: List<Shift>,
        staffDirectory: List<StaffDirectoryEntry>,
        timeOffRequests: List<TimeOffRequest> = emptyList()
    ): List<FairnessArrangementItem> {
        val conflictShifts = shifts.filter { s ->
            s.status == ShiftStatus.CONFLICT || s.candidateUids.size > 1
        }
        val simulatedDirectory = staffDirectory.toMutableList()
        val plan = mutableListOf<FairnessArrangementItem>()

        for (shift in conflictShifts) {
            val ranked = rankCandidatesByFairness(
                candidateUids = shift.candidateUids,
                staffDirectory = simulatedDirectory,
                allShifts = shifts,
                targetDate = shift.date,
                startTime = shift.startTime,
                endTime = shift.endTime,
                excludeShiftId = shift.id,
                timeOffRequests = timeOffRequests
            )
            val best = ranked.firstOrNull { !it.hasConflict && !it.hasApprovedTimeOff } ?: ranked.firstOrNull()
            if (best == null) continue

            val idx = simulatedDirectory.indexOfFirst { it.uid == best.uid }
            if (idx >= 0) {
                simulatedDirectory[idx] = simulatedDirectory[idx].copy(
                    totalHoursWorked = simulatedDirectory[idx].totalHoursWorked + shift.durationHours,
                    scheduledHoursThisWeek = simulatedDirectory[idx].scheduledHoursThisWeek + shift.durationHours
                )
            }

            val pct = round(best.utilizationRatio * 100.0).toInt()
            val reason = "Assigned to ${best.displayName} (${formatHours(best.totalHoursWorked)}h logged, ${best.shiftsThisWeekCount} shifts, $pct% target ratio)"

            plan.add(
                FairnessArrangementItem(
                    shift = shift,
                    previousUid = shift.assignedUid,
                    previousName = shift.assignedName,
                    chosenUid = best.uid,
                    chosenName = best.displayName,
                    reason = reason
                )
            )
        }
        return plan
    }

    fun buildOpenShiftAssignmentPlan(
        shifts: List<Shift>,
        staffDirectory: List<StaffDirectoryEntry>,
        submissions: List<ShiftSubmission> = emptyList(),
        timeOffRequests: List<TimeOffRequest> = emptyList(),
        requireExplicitSubmission: Boolean = false
    ): List<OpenShiftAssignmentItem> {
        val openShifts = shifts.filter { it.status == ShiftStatus.OPEN }
        if (openShifts.isEmpty() || staffDirectory.isEmpty()) return emptyList()

        val simulatedDirectory = staffDirectory.toMutableList()
        val simulatedShifts = shifts.toMutableList()
        val plan = mutableListOf<OpenShiftAssignmentItem>()
        val activeSubmissions = submissions.filter { it.status == SubmissionStatus.SUBMITTED }

        for (shift in openShifts) {
            val matchingSubmissions = activeSubmissions.filter { sub ->
                sub.date == shift.date &&
                    shiftsTimeOverlap(sub.date, sub.startTime, sub.endTime, shift.date, shift.startTime, shift.endTime)
            }
            val submittedUids = matchingSubmissions.map { it.staffUid }.distinct()

            if (requireExplicitSubmission && submittedUids.isEmpty()) {
                continue
            }

            val candidatePool = when {
                submittedUids.isNotEmpty() -> submittedUids
                shift.candidateUids.isNotEmpty() -> shift.candidateUids
                else -> simulatedDirectory.map { it.uid }
            }

            val ranked = rankCandidatesByFairness(
                candidateUids = candidatePool,
                staffDirectory = simulatedDirectory,
                allShifts = simulatedShifts,
                targetDate = shift.date,
                startTime = shift.startTime,
                endTime = shift.endTime,
                excludeShiftId = shift.id,
                timeOffRequests = timeOffRequests
            )
            val best = ranked.firstOrNull { !it.hasConflict && !it.hasApprovedTimeOff } ?: continue

            val dirIdx = simulatedDirectory.indexOfFirst { it.uid == best.uid }
            if (dirIdx >= 0) {
                simulatedDirectory[dirIdx] = simulatedDirectory[dirIdx].copy(
                    totalHoursWorked = simulatedDirectory[dirIdx].totalHoursWorked + shift.durationHours,
                    scheduledHoursThisWeek = simulatedDirectory[dirIdx].scheduledHoursThisWeek + shift.durationHours
                )
            }
            val shiftIdx = simulatedShifts.indexOfFirst { it.id == shift.id }
            if (shiftIdx >= 0) {
                simulatedShifts[shiftIdx] = simulatedShifts[shiftIdx].copy(
                    assignedUid = best.uid,
                    assignedName = best.displayName,
                    status = ShiftStatus.SCHEDULED
                )
            }

            val pct = round(best.utilizationRatio * 100.0).toInt()
            val subNote = if (submittedUids.isNotEmpty()) "Matched staff shift submission • " else ""
            val reason = "${subNote}Assigned to ${best.displayName} (${formatHours(best.totalHoursWorked)}h logged, ${best.shiftsThisWeekCount} shifts, $pct% target ratio)"

            plan.add(
                OpenShiftAssignmentItem(
                    shift = shift,
                    chosenUid = best.uid,
                    chosenName = best.displayName,
                    reason = reason
                )
            )
        }
        return plan
    }

    data class GeneratedShiftSpec(
        val title: String,
        val department: String,
        val date: String,
        val startTime: String,
        val endTime: String,
        val durationHours: Double,
        val assignedUid: String,
        val assignedName: String,
        val candidateUids: List<String>,
        val status: ShiftStatus,
        val fairnessNote: String
    )

    fun generateWeeklyFairnessSchedule(
        weekStartDate: String,
        slots: List<WeekSlotConfig>,
        staffDirectory: List<StaffDirectoryEntry>,
        existingShifts: List<Shift>,
        submissions: List<ShiftSubmission> = emptyList(),
        timeOffRequests: List<TimeOffRequest> = emptyList()
    ): List<GeneratedShiftSpec> {
        val baseDate = runCatching { LocalDate.parse(weekStartDate, isoDateFormatter) }
            .getOrElse { LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }

        val activeSubmissions = submissions.filter { it.status == SubmissionStatus.SUBMITTED }
        val simulatedDirectory = staffDirectory.toMutableList()
        val simulatedShifts = existingShifts.toMutableList()
        val planned = mutableListOf<GeneratedShiftSpec>()

        for (dayOffset in 0 until 7) {
            val dateStr = baseDate.plusDays(dayOffset.toLong()).format(isoDateFormatter)

            for (slot in slots) {
                val alreadyExists = simulatedShifts.any { s ->
                    s.date == dateStr &&
                        s.startTime == slot.startTime &&
                        s.endTime == slot.endTime &&
                        s.title.equals(slot.title, ignoreCase = true)
                }
                if (alreadyExists) continue

                val durationHours = computeShiftHours(slot.startTime, slot.endTime)

                val matchingSubmissions = activeSubmissions.filter { sub ->
                    sub.date == dateStr &&
                        shiftsTimeOverlap(sub.date, sub.startTime, sub.endTime, dateStr, slot.startTime, slot.endTime)
                }
                val submittedUids = matchingSubmissions.map { it.staffUid }.distinct()

                val ranked = if (submittedUids.isNotEmpty()) {
                    rankCandidatesByFairness(
                        candidateUids = submittedUids,
                        staffDirectory = simulatedDirectory,
                        allShifts = simulatedShifts,
                        targetDate = dateStr,
                        startTime = slot.startTime,
                        endTime = slot.endTime,
                        excludeShiftId = null,
                        timeOffRequests = timeOffRequests
                    )
                } else {
                    emptyList()
                }

                val best = ranked.firstOrNull { !it.hasConflict && !it.hasApprovedTimeOff }

                if (best == null) {
                    val note = if (submittedUids.isEmpty()) {
                        "No staff shift submissions received for this slot; left open."
                    } else {
                        "All submitting candidates had shift conflicts or approved time off; left open."
                    }
                    val openSpec = GeneratedShiftSpec(
                        title = slot.title,
                        department = slot.department,
                        date = dateStr,
                        startTime = slot.startTime,
                        endTime = slot.endTime,
                        durationHours = durationHours,
                        assignedUid = "",
                        assignedName = "",
                        candidateUids = submittedUids,
                        status = ShiftStatus.OPEN,
                        fairnessNote = note
                    )
                    planned.add(openSpec)
                    simulatedShifts.add(
                        Shift(
                            id = "sim_${dateStr}_${slot.id}",
                            title = slot.title,
                            department = slot.department,
                            date = dateStr,
                            startTime = slot.startTime,
                            endTime = slot.endTime,
                            durationHours = durationHours,
                            assignedUid = "",
                            assignedName = "",
                            candidateUids = submittedUids,
                            status = ShiftStatus.OPEN,
                            visibility = "team",
                            fairnessNote = note,
                            createdBy = "planner"
                        )
                    )
                } else {
                    val pct = round(best.utilizationRatio * 100.0).toInt()
                    val matchedSubmission = matchingSubmissions.firstOrNull { it.staffUid == best.uid }
                    val note = buildString {
                        append("Matched submission • Assigned to ${best.displayName} (${formatHours(best.totalHoursWorked)}h logged, $pct% target ratio)")
                        if (matchedSubmission != null && matchedSubmission.notes.isNotBlank()) {
                            append(" — \"${matchedSubmission.notes.trim()}\"")
                        }
                    }

                    val dirIdx = simulatedDirectory.indexOfFirst { it.uid == best.uid }
                    if (dirIdx >= 0) {
                        simulatedDirectory[dirIdx] = simulatedDirectory[dirIdx].copy(
                            totalHoursWorked = simulatedDirectory[dirIdx].totalHoursWorked + durationHours,
                            scheduledHoursThisWeek = simulatedDirectory[dirIdx].scheduledHoursThisWeek + durationHours
                        )
                    }

                    val spec = GeneratedShiftSpec(
                        title = slot.title,
                        department = slot.department,
                        date = dateStr,
                        startTime = slot.startTime,
                        endTime = slot.endTime,
                        durationHours = durationHours,
                        assignedUid = best.uid,
                        assignedName = best.displayName,
                        candidateUids = submittedUids,
                        status = ShiftStatus.SCHEDULED,
                        fairnessNote = note
                    )
                    planned.add(spec)
                    simulatedShifts.add(
                        Shift(
                            id = "sim_${dateStr}_${slot.id}",
                            title = slot.title,
                            department = slot.department,
                            date = dateStr,
                            startTime = slot.startTime,
                            endTime = slot.endTime,
                            durationHours = durationHours,
                            assignedUid = best.uid,
                            assignedName = best.displayName,
                            candidateUids = submittedUids,
                            status = ShiftStatus.SCHEDULED,
                            visibility = "team",
                            fairnessNote = note,
                            createdBy = "planner"
                        )
                    )
                }
            }
        }
        return planned
    }

    fun getMondayOfWeek(date: LocalDate = LocalDate.now()): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun getMondayIso(date: LocalDate = LocalDate.now()): String =
        getMondayOfWeek(date).format(isoDateFormatter)

    fun formatHours(value: Double): String {
        val rounded = round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format("%.1f", rounded)
    }
}
