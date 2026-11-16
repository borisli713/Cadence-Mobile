import {
  FairnessResolutionItem,
  Shift,
  ShiftSubmission,
  StaffDirectoryEntry,
  StaffFairnessMetric,
  TimeOffRequest,
} from '../types';

export function parseTimeToMinutes(timeStr: string): number {
  const [hStr, mStr] = timeStr.split(':');
  const h = Number(hStr);
  const m = Number(mStr);
  if (Number.isNaN(h) || Number.isNaN(m)) return 0;
  return h * 60 + m;
}

export function minutesToTimeStr(totalMinutes: number): string {
  const normalized = ((totalMinutes % 1440) + 1440) % 1440;
  const h = Math.floor(normalized / 60);
  const m = normalized % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

export function computeDurationHours(startTime: string, endTime: string, breakMinutes = 0): number {
  let start = parseTimeToMinutes(startTime);
  let end = parseTimeToMinutes(endTime);
  if (end <= start) {
    end += 24 * 60;
  }
  const netMinutes = Math.max(15, end - start - Math.max(0, breakMinutes));
  return Math.round((netMinutes / 60) * 100) / 100;
}

export function timesOverlap(startA: string, endA: string, startB: string, endB: string): boolean {
  const a1 = parseTimeToMinutes(startA);
  let a2 = parseTimeToMinutes(endA);
  if (a2 <= a1) a2 += 1440;

  const b1 = parseTimeToMinutes(startB);
  let b2 = parseTimeToMinutes(endB);
  if (b2 <= b1) b2 += 1440;

  return a1 < b2 && b1 < a2;
}

export function buildStaffFairnessMetrics(
  staff: StaffDirectoryEntry[],
  shifts: Shift[]
): StaffFairnessMetric[] {
  const scheduledMap = new Map<string, number>();
  for (const s of shifts) {
    if (
      s.assignedUid &&
      (s.status === 'scheduled' || s.status === 'pending_change' || s.status === 'conflict')
    ) {
      scheduledMap.set(s.assignedUid, (scheduledMap.get(s.assignedUid) || 0) + s.durationHours);
    }
  }

  return staff
    .map((member) => {
      const workedHours = Math.round(member.totalHoursWorked * 100) / 100;
      const scheduledHours = Math.round((scheduledMap.get(member.uid) || 0) * 100) / 100;
      const effectiveTotalHours = Math.round((workedHours + scheduledHours) * 100) / 100;
      const target = Math.max(1, member.weeklyHoursTarget || 40);
      const utilizationRatio = Math.round((effectiveTotalHours / target) * 1000) / 1000;
      return {
        uid: member.uid,
        displayName: member.displayName,
        department: member.department,
        workedHours,
        scheduledHours,
        effectiveTotalHours,
        weeklyHoursTarget: target,
        utilizationRatio,
        lastAssignedAt: member.lastAssignedAt || '',
      };
    })
    .sort((a, b) => a.utilizationRatio - b.utilizationRatio || a.effectiveTotalHours - b.effectiveTotalHours);
}

export function isStaffOffOnDate(
  uid: string,
  date: string,
  timeOffRequests: TimeOffRequest[] = []
): boolean {
  if (!uid || !date || timeOffRequests.length === 0) return false;
  return timeOffRequests.some(
    (req) =>
      req.staffUid === uid &&
      req.status === 'approved' &&
      date >= req.startDate &&
      date <= req.endDate
  );
}

/**
 * Picks the fairest staff member from a list of candidate UIDs based on
 * lowest working-time utilization ratio, then lowest total effective hours,
 * then oldest lastAssignedAt timestamp, excluding anyone with approved time off.
 */
export function selectFairestCandidate(
  candidateUids: string[],
  metricsMap: Map<string, StaffFairnessMetric>,
  offCheck?: { date: string; timeOffRequests?: TimeOffRequest[] }
): StaffFairnessMetric | null {
  const validCandidates = candidateUids
    .map((uid) => metricsMap.get(uid))
    .filter((m): m is StaffFairnessMetric => {
      if (!m) return false;
      if (offCheck?.date && isStaffOffOnDate(m.uid, offCheck.date, offCheck.timeOffRequests)) {
        return false;
      }
      return true;
    });

  if (validCandidates.length === 0) return null;

  validCandidates.sort((a, b) => {
    if (Math.abs(a.utilizationRatio - b.utilizationRatio) > 0.005) {
      return a.utilizationRatio - b.utilizationRatio;
    }
    if (Math.abs(a.effectiveTotalHours - b.effectiveTotalHours) > 0.05) {
      return a.effectiveTotalHours - b.effectiveTotalHours;
    }
    return a.lastAssignedAt.localeCompare(b.lastAssignedAt);
  });

  return validCandidates[0];
}

export interface DetectedOverlapSummary {
  multiCandidateShifts: Shift[];
  concurrentShiftGroups: Shift[][];
  totalOverlappingShifts: number;
}

export function detectOverlaps(shifts: Shift[]): DetectedOverlapSummary {
  const activeShifts = shifts.filter((s) => s.status !== 'completed' && s.status !== 'open' && Boolean(s.assignedUid));
  const multiCandidateShifts = activeShifts.filter((s) => s.candidateUids.length > 1 || s.status === 'conflict');

  const visited = new Set<string>();
  const concurrentShiftGroups: Shift[][] = [];

  for (let i = 0; i < activeShifts.length; i++) {
    const a = activeShifts[i];
    if (visited.has(a.id)) continue;

    const group: Shift[] = [a];
    for (let j = i + 1; j < activeShifts.length; j++) {
      const b = activeShifts[j];
      if (
        a.date === b.date &&
        a.department === b.department &&
        timesOverlap(a.startTime, a.endTime, b.startTime, b.endTime)
      ) {
        group.push(b);
      }
    }

    if (group.length > 1) {
      group.forEach((s) => visited.add(s.id));
      concurrentShiftGroups.push(group);
    }
  }

  const uniqueOverlapIds = new Set<string>();
  multiCandidateShifts.forEach((s) => uniqueOverlapIds.add(s.id));
  concurrentShiftGroups.forEach((g) => g.forEach((s) => uniqueOverlapIds.add(s.id)));

  return {
    multiCandidateShifts,
    concurrentShiftGroups,
    totalOverlappingShifts: uniqueOverlapIds.size,
  };
}

/**
 * Generates an actionable Fairness Arrangement Plan for all shifts that have
 * multiple people scheduled for the same time (either via candidateUids > 1
 * or concurrent overlapping shifts in the same date + department).
 */
export function buildFairnessArrangementPlan(
  shifts: Shift[],
  staff: StaffDirectoryEntry[],
  timeOffRequests: TimeOffRequest[] = []
): FairnessResolutionItem[] {
  const metrics = buildStaffFairnessMetrics(staff, shifts);
  const metricsMap = new Map<string, StaffFairnessMetric>(metrics.map((m) => [m.uid, m]));
  const resolutions: FairnessResolutionItem[] = [];
  const handledShiftIds = new Set<string>();

  const activeShifts = shifts
    .filter((s) => s.status !== 'completed' && s.status !== 'open' && Boolean(s.assignedUid))
    .sort((a, b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime));

  // 1. Resolve concurrent overlapping shifts in the same date + department
  for (let i = 0; i < activeShifts.length; i++) {
    const anchor = activeShifts[i];
    if (handledShiftIds.has(anchor.id)) continue;

    const overlapping = activeShifts.filter(
      (s) =>
        !handledShiftIds.has(s.id) &&
        s.date === anchor.date &&
        s.department === anchor.department &&
        timesOverlap(anchor.startTime, anchor.endTime, s.startTime, s.endTime)
    );

    if (overlapping.length <= 1) continue;

    // Collect all competing UIDs across these overlapping shifts
    const competingUidSet = new Set<string>();
    overlapping.forEach((s) => {
      if (s.assignedUid) competingUidSet.add(s.assignedUid);
      s.candidateUids.forEach((u) => {
        if (u) competingUidSet.add(u);
      });
    });

    const primaryShift = overlapping[0];
    const allCompeting = Array.from(competingUidSet);
    const fairest = selectFairestCandidate(allCompeting, metricsMap, {
      date: primaryShift.date,
      timeOffRequests,
    });
    if (!fairest) continue;

    const competingSummary = allCompeting
      .map((uid) => metricsMap.get(uid))
      .filter((m): m is StaffFairnessMetric => Boolean(m))
      .map((m) => `${m.displayName} (${m.effectiveTotalHours.toFixed(1)}h/${m.weeklyHoursTarget}h)`);

    // Assign primary shift to the fairest candidate
    resolutions.push({
      shiftId: primaryShift.id,
      shiftTitle: primaryShift.title,
      date: primaryShift.date,
      timeWindow: `${primaryShift.startTime}-${primaryShift.endTime}`,
      department: primaryShift.department,
      overlapType: 'concurrent_overlap',
      previousAssigneeUid: primaryShift.assignedUid,
      previousAssigneeName: primaryShift.assignedName,
      selectedAssigneeUid: fairest.uid,
      selectedAssigneeName: fairest.displayName,
      competingNames: competingSummary,
      action: 'assign_fairest',
      reason: `Fairness selected ${fairest.displayName} (${fairest.effectiveTotalHours.toFixed(1)}h / ${fairest.weeklyHoursTarget}h = ${Math.round(fairest.utilizationRatio * 100)}%) over higher-hour overlap candidates.`,
    });

    // Update running hours for the winner so subsequent slots stay balanced
    fairest.effectiveTotalHours = Math.round((fairest.effectiveTotalHours + primaryShift.durationHours) * 100) / 100;
    fairest.utilizationRatio = Math.round((fairest.effectiveTotalHours / fairest.weeklyHoursTarget) * 1000) / 1000;
    handledShiftIds.add(primaryShift.id);

    // Rebalance secondary overlapping shifts to a non-overlapping later slot or next day + fairest remaining person
    for (let k = 1; k < overlapping.length; k++) {
      const sec = overlapping[k];
      handledShiftIds.add(sec.id);

      // Move secondary shift sequentially right after primary shift (or next non-overlapping block)
      const primaryEndMin = parseTimeToMinutes(primaryShift.endTime);
      const secDurationMin = Math.round(sec.durationHours * 60);
      let newDate = sec.date;
      let newStartMin = primaryEndMin;
      let newEndMin = primaryEndMin + secDurationMin;

      if (newEndMin > 22 * 60) {
        // Shift to next day morning 08:00 to avoid overnight conflict
        const d = new Date(`${sec.date}T12:00:00Z`);
        d.setUTCDate(d.getUTCDate() + 1);
        newDate = d.toISOString().slice(0, 10);
        newStartMin = 8 * 60;
        newEndMin = 8 * 60 + secDurationMin;
      }

      const remainingCandidates = allCompeting.filter((uid) => uid !== fairest.uid);
      const nextPerson =
        selectFairestCandidate(
          remainingCandidates.length > 0 ? remainingCandidates : allCompeting,
          metricsMap,
          { date: newDate, timeOffRequests }
        ) ||
        selectFairestCandidate(allCompeting, metricsMap, { date: newDate, timeOffRequests });

      if (!nextPerson) continue;

      const newStartTime = minutesToTimeStr(newStartMin);
      const newEndTime = minutesToTimeStr(newEndMin);

      resolutions.push({
        shiftId: sec.id,
        shiftTitle: sec.title,
        date: sec.date,
        timeWindow: `${sec.startTime}-${sec.endTime}`,
        department: sec.department,
        overlapType: 'concurrent_overlap',
        previousAssigneeUid: sec.assignedUid,
        previousAssigneeName: sec.assignedName,
        selectedAssigneeUid: nextPerson.uid,
        selectedAssigneeName: nextPerson.displayName,
        competingNames: competingSummary,
        action: 'rebalance_secondary',
        newDate,
        newStartTime,
        newEndTime,
        reason: `Overlap cleared: moved to ${newDate} ${newStartTime}-${newEndTime} and assigned to ${nextPerson.displayName} (${nextPerson.effectiveTotalHours.toFixed(1)}h/${nextPerson.weeklyHoursTarget}h).`,
      });

      nextPerson.effectiveTotalHours = Math.round((nextPerson.effectiveTotalHours + sec.durationHours) * 100) / 100;
      nextPerson.utilizationRatio = Math.round((nextPerson.effectiveTotalHours / nextPerson.weeklyHoursTarget) * 1000) / 1000;
    }
  }

  // 2. Resolve single shifts that have multiple candidateUids scheduled on them
  for (const shift of activeShifts) {
    if (handledShiftIds.has(shift.id)) continue;
    if (shift.candidateUids.length <= 1 && shift.status !== 'conflict') continue;

    const fairest = selectFairestCandidate(shift.candidateUids, metricsMap, {
      date: shift.date,
      timeOffRequests,
    });
    if (!fairest) continue;

    const competingSummary = shift.candidateUids
      .map((uid) => metricsMap.get(uid))
      .filter((m): m is StaffFairnessMetric => Boolean(m))
      .map((m) => `${m.displayName} (${m.effectiveTotalHours.toFixed(1)}h/${m.weeklyHoursTarget}h)`);

    resolutions.push({
      shiftId: shift.id,
      shiftTitle: shift.title,
      date: shift.date,
      timeWindow: `${shift.startTime}-${shift.endTime}`,
      department: shift.department,
      overlapType: 'multi_candidate',
      previousAssigneeUid: shift.assignedUid,
      previousAssigneeName: shift.assignedName,
      selectedAssigneeUid: fairest.uid,
      selectedAssigneeName: fairest.displayName,
      competingNames: competingSummary,
      action: 'assign_fairest',
      reason: `Fairness arranged among ${shift.candidateUids.length} candidates: ${fairest.displayName} (${fairest.effectiveTotalHours.toFixed(1)}h / ${fairest.weeklyHoursTarget}h = ${Math.round(fairest.utilizationRatio * 100)}%) had the lowest working-time load.`,
    });

    fairest.effectiveTotalHours = Math.round((fairest.effectiveTotalHours + shift.durationHours) * 100) / 100;
    fairest.utilizationRatio = Math.round((fairest.effectiveTotalHours / fairest.weeklyHoursTarget) * 1000) / 1000;
    handledShiftIds.add(shift.id);
  }

  return resolutions;
}

export interface WeeklySlotTemplate {
  title: string;
  department: string;
  startTime: string;
  endTime: string;
  includeWeekends: boolean;
}

export interface GeneratedWeeklyShiftDraft {
  title: string;
  department: string;
  date: string;
  startTime: string;
  endTime: string;
  durationHours: number;
  assignedUid: string;
  assignedName: string;
  status: 'scheduled' | 'open';
  fairnessNote: string;
}

export const DEFAULT_WEEKLY_TEMPLATES: WeeklySlotTemplate[] = [
  {
    title: 'Morning Operations Shift',
    department: 'Operations',
    startTime: '08:00',
    endTime: '16:00',
    includeWeekends: true,
  },
  {
    title: 'Afternoon Support Desk',
    department: 'Support Desk',
    startTime: '12:00',
    endTime: '20:00',
    includeWeekends: false,
  },
  {
    title: 'Evening Operations Coverage',
    department: 'Operations',
    startTime: '16:00',
    endTime: '22:00',
    includeWeekends: true,
  },
];

export function doesSubmissionMatchSlot(
  sub: ShiftSubmission,
  slot: { date: string; startTime: string; endTime: string; department: string }
): boolean {
  if (sub.status !== 'submitted') return false;
  if (sub.date !== slot.date) return false;

  const subDept = sub.department.trim().toLowerCase();
  const slotDept = slot.department.trim().toLowerCase();
  if (subDept !== 'any' && subDept !== slotDept) {
    return false;
  }

  const subStart = parseTimeToMinutes(sub.startTime);
  let subEnd = parseTimeToMinutes(sub.endTime);
  if (subEnd <= subStart) subEnd += 1440;

  const slotStart = parseTimeToMinutes(slot.startTime);
  let slotEnd = parseTimeToMinutes(slot.endTime);
  if (slotEnd <= slotStart) slotEnd += 1440;

  return subStart <= slotStart && subEnd >= slotEnd;
}

/**
 * Matches open/blank shifts against active ShiftSubmissions and produces
 * fairness-based assignment plans ONLY for open shifts that have >= 1 matching submission.
 * Open shifts with zero submissions remain blank.
 */
export function buildOpenShiftAssignmentPlan(params: {
  shifts: Shift[];
  submissions: ShiftSubmission[];
  staff: StaffDirectoryEntry[];
  timeOffRequests?: TimeOffRequest[];
}): FairnessResolutionItem[] {
  const { shifts, submissions, staff, timeOffRequests = [] } = params;
  const openShifts = shifts
    .filter((s) => s.status === 'open' || !s.assignedUid)
    .sort((a, b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime));

  if (openShifts.length === 0) return [];

  const metrics = buildStaffFairnessMetrics(staff, shifts);
  const metricsMap = new Map<string, StaffFairnessMetric>(metrics.map((m) => [m.uid, { ...m }]));

  const staffIntervalsByDate = new Map<string, { startTime: string; endTime: string }[]>();
  const addOccupiedInterval = (uid: string, date: string, startTime: string, endTime: string) => {
    const key = `${uid}__${date}`;
    const list = staffIntervalsByDate.get(key) || [];
    list.push({ startTime, endTime });
    staffIntervalsByDate.set(key, list);
  };

  for (const s of shifts) {
    if (s.assignedUid && s.status !== 'completed' && s.status !== 'open') {
      addOccupiedInterval(s.assignedUid, s.date, s.startTime, s.endTime);
    }
  }

  const resolutions: FairnessResolutionItem[] = [];

  for (const shift of openShifts) {
    const matchingUids = new Set(
      submissions
        .filter((sub) =>
          doesSubmissionMatchSlot(sub, {
            date: shift.date,
            startTime: shift.startTime,
            endTime: shift.endTime,
            department: shift.department,
          })
        )
        .map((sub) => sub.staffUid)
    );

    if (matchingUids.size === 0) continue;

    const eligible = Array.from(matchingUids)
      .map((uid) => metricsMap.get(uid))
      .filter((m): m is StaffFairnessMetric => {
        if (!m) return false;
        if (isStaffOffOnDate(m.uid, shift.date, timeOffRequests)) {
          return false;
        }
        const occupied = staffIntervalsByDate.get(`${m.uid}__${shift.date}`) || [];
        return !occupied.some((iv) =>
          timesOverlap(iv.startTime, iv.endTime, shift.startTime, shift.endTime)
        );
      });

    if (eligible.length === 0) continue;

    eligible.sort((a, b) => {
      if (Math.abs(a.utilizationRatio - b.utilizationRatio) > 0.005) {
        return a.utilizationRatio - b.utilizationRatio;
      }
      if (Math.abs(a.effectiveTotalHours - b.effectiveTotalHours) > 0.05) {
        return a.effectiveTotalHours - b.effectiveTotalHours;
      }
      const aDeptMatch = a.department.toLowerCase() === shift.department.toLowerCase() ? 0 : 1;
      const bDeptMatch = b.department.toLowerCase() === shift.department.toLowerCase() ? 0 : 1;
      if (aDeptMatch !== bDeptMatch) return aDeptMatch - bDeptMatch;
      return a.lastAssignedAt.localeCompare(b.lastAssignedAt);
    });

    const chosen = eligible[0];
    const beforeHours = chosen.effectiveTotalHours;
    const beforePct = Math.round(chosen.utilizationRatio * 100);
    const competingSummary = eligible.map(
      (m) => `${m.displayName} (${m.effectiveTotalHours.toFixed(1)}h/${m.weeklyHoursTarget}h)`
    );

    resolutions.push({
      shiftId: shift.id,
      shiftTitle: shift.title,
      date: shift.date,
      timeWindow: `${shift.startTime}-${shift.endTime}`,
      department: shift.department,
      overlapType: 'multi_candidate',
      previousAssigneeUid: '',
      previousAssigneeName: 'Unassigned',
      selectedAssigneeUid: chosen.uid,
      selectedAssigneeName: chosen.displayName,
      competingNames: competingSummary,
      action: 'assign_fairest',
      reason:
        eligible.length === 1
          ? `Assigned to ${chosen.displayName} (submitted for this open shift).`
          : `Fairness selected ${chosen.displayName} (${beforeHours.toFixed(1)}h/${chosen.weeklyHoursTarget}h, ${beforePct}% load) from ${eligible.length} submissions.`,
    });

    chosen.effectiveTotalHours = Math.round((chosen.effectiveTotalHours + shift.durationHours) * 100) / 100;
    chosen.utilizationRatio = Math.round((chosen.effectiveTotalHours / chosen.weeklyHoursTarget) * 1000) / 1000;
    chosen.lastAssignedAt = `${shift.date}T${shift.startTime}`;
    addOccupiedInterval(chosen.uid, shift.date, shift.startTime, shift.endTime);
  }

  return resolutions;
}

/**
 * Generates a full 7-day weekly schedule starting on weekDates[0] (Monday).
 * - Skips slots already covered on the same date, department, and overlapping window.
 * - Strictly considers ONLY staff who submitted for that date/time/department AND do NOT have approved time off.
 * - If nobody submitted (or all submitters are on approved time off), leaves it blank (status: 'open', assignedUid: '').
 * - If multiple staff submitted, picks the staff member with the lowest working-time utilization ratio
 *   and prevents double-booking overlapping shifts on the same day.
 */
export function generateWeeklyFairnessSchedule(params: {
  weekDates: string[];
  templates: WeeklySlotTemplate[];
  existingShifts: Shift[];
  staff: StaffDirectoryEntry[];
  submissions: ShiftSubmission[];
  timeOffRequests?: TimeOffRequest[];
}): GeneratedWeeklyShiftDraft[] {
  const { weekDates, templates, existingShifts, staff, submissions, timeOffRequests = [] } = params;
  if (weekDates.length === 0 || templates.length === 0) {
    return [];
  }

  const metrics = buildStaffFairnessMetrics(staff, existingShifts);
  const metricsMap = new Map<string, StaffFairnessMetric>(metrics.map((m) => [m.uid, { ...m }]));

  // Track all occupied intervals per staff member per day to avoid double-booking
  const staffIntervalsByDate = new Map<string, { startTime: string; endTime: string }[]>();
  const addOccupiedInterval = (uid: string, date: string, startTime: string, endTime: string) => {
    if (!uid) return;
    const key = `${uid}__${date}`;
    const list = staffIntervalsByDate.get(key) || [];
    list.push({ startTime, endTime });
    staffIntervalsByDate.set(key, list);
  };

  for (const s of existingShifts) {
    if (s.assignedUid && s.status !== 'completed' && s.status !== 'open') {
      addOccupiedInterval(s.assignedUid, s.date, s.startTime, s.endTime);
    }
  }

  const drafts: GeneratedWeeklyShiftDraft[] = [];

  for (let dayIdx = 0; dayIdx < weekDates.length; dayIdx++) {
    const date = weekDates[dayIdx];
    const isWeekend = dayIdx >= 5; // Sat, Sun when week starts on Monday

    for (const tpl of templates) {
      if (isWeekend && !tpl.includeWeekends) continue;

      // Skip if an existing shift already covers this date + department + overlapping window
      const alreadyCovered = existingShifts.some(
        (s) =>
          s.date === date &&
          s.department.toLowerCase() === tpl.department.toLowerCase() &&
          timesOverlap(s.startTime, s.endTime, tpl.startTime, tpl.endTime)
      );
      if (alreadyCovered) continue;

      // Also skip if a draft in this batch already covers this department + overlapping window
      const draftCovered = drafts.some(
        (d) =>
          d.date === date &&
          d.department.toLowerCase() === tpl.department.toLowerCase() &&
          timesOverlap(d.startTime, d.endTime, tpl.startTime, tpl.endTime)
      );
      if (draftCovered) continue;

      const durationHours = computeDurationHours(tpl.startTime, tpl.endTime, 0);

      // Strictly filter to staff who explicitly submitted for this slot
      const matchingSubUids = new Set(
        submissions
          .filter((sub) =>
            doesSubmissionMatchSlot(sub, {
              date,
              startTime: tpl.startTime,
              endTime: tpl.endTime,
              department: tpl.department,
            })
          )
          .map((sub) => sub.staffUid)
      );

      let blockedByTimeOff = false;
      const eligibleMetrics = Array.from(matchingSubUids)
        .map((uid) => metricsMap.get(uid))
        .filter((m): m is StaffFairnessMetric => {
          if (!m) return false;
          if (isStaffOffOnDate(m.uid, date, timeOffRequests)) {
            blockedByTimeOff = true;
            return false;
          }
          const occupied = staffIntervalsByDate.get(`${m.uid}__${date}`) || [];
          return !occupied.some((iv) =>
            timesOverlap(iv.startTime, iv.endTime, tpl.startTime, tpl.endTime)
          );
        });

      if (eligibleMetrics.length === 0) {
        // Leave blank — never assign someone who didn't submit or is on approved time off
        drafts.push({
          title: tpl.title,
          department: tpl.department,
          date,
          startTime: tpl.startTime,
          endTime: tpl.endTime,
          durationHours,
          assignedUid: '',
          assignedName: '',
          status: 'open',
          fairnessNote: blockedByTimeOff
            ? 'Left blank — submitted staff have approved time off on this date.'
            : 'Left blank — no staff submitted for this shift.',
        });
        continue;
      }

      eligibleMetrics.sort((a, b) => {
        if (Math.abs(a.utilizationRatio - b.utilizationRatio) > 0.005) {
          return a.utilizationRatio - b.utilizationRatio;
        }
        if (Math.abs(a.effectiveTotalHours - b.effectiveTotalHours) > 0.05) {
          return a.effectiveTotalHours - b.effectiveTotalHours;
        }
        const aDeptMatch = a.department.toLowerCase() === tpl.department.toLowerCase() ? 0 : 1;
        const bDeptMatch = b.department.toLowerCase() === tpl.department.toLowerCase() ? 0 : 1;
        if (aDeptMatch !== bDeptMatch) return aDeptMatch - bDeptMatch;
        return a.lastAssignedAt.localeCompare(b.lastAssignedAt);
      });

      const chosen = eligibleMetrics[0];
      const beforeHours = chosen.effectiveTotalHours;
      const beforePct = Math.round(chosen.utilizationRatio * 100);

      chosen.effectiveTotalHours = Math.round((chosen.effectiveTotalHours + durationHours) * 100) / 100;
      chosen.utilizationRatio = Math.round((chosen.effectiveTotalHours / chosen.weeklyHoursTarget) * 1000) / 1000;
      chosen.lastAssignedAt = `${date}T${tpl.startTime}`;
      addOccupiedInterval(chosen.uid, date, tpl.startTime, tpl.endTime);

      drafts.push({
        title: tpl.title,
        department: tpl.department,
        date,
        startTime: tpl.startTime,
        endTime: tpl.endTime,
        durationHours,
        assignedUid: chosen.uid,
        assignedName: chosen.displayName,
        status: 'scheduled',
        fairnessNote:
          eligibleMetrics.length === 1
            ? `Assigned to ${chosen.displayName} (submitted for this shift).`
            : `Fairness selected ${chosen.displayName} (${beforeHours.toFixed(1)}h/${chosen.weeklyHoursTarget}h, ${beforePct}% load) from ${eligibleMetrics.length} submissions.`,
      });
    }
  }

  return drafts;
}
