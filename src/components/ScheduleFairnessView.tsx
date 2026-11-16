import React, { useMemo, useState } from 'react';
import {
  AlertTriangle,
  ArrowRightLeft,
  Calendar,
  CalendarPlus,
  CheckCircle2,
  Download,
  Hand,
  List,
  Pencil,
  Plus,
  Scale,
  SlidersHorizontal,
  Trash2,
  X,
} from 'lucide-react';
import {
  ScheduleChangeType,
  Shift,
  ShiftStatus,
  ShiftSubmission,
  StaffDirectoryEntry,
  TimeOffRequest,
  UserProfile,
} from '../types';
import {
  buildFairnessArrangementPlan,
  buildOpenShiftAssignmentPlan,
  buildStaffFairnessMetrics,
  DEFAULT_WEEKLY_TEMPLATES,
  detectOverlaps,
  doesSubmissionMatchSlot,
  generateWeeklyFairnessSchedule,
  isStaffOffOnDate,
  selectFairestCandidate,
  WeeklySlotTemplate,
} from '../utils/fairness';
import {
  commitFairnessArrangementPlan,
  commitWeeklyScheduleBatch,
  createScheduleChangeRequest,
  createShiftRecord,
  deleteShiftRecord,
  submitShiftAvailability,
  updateShiftManually,
  withdrawShiftSubmission,
} from '../services/firestoreService';
import {
  downloadIcsFile,
  formatIsoDateLocal,
  getMondayOfWeek,
  getWeekDates,
  parseIsoDateLocal,
} from '../utils/calendarExport';
import { ShiftCalendarView } from './ShiftCalendarView';
import { StaffHoursBalanceChart } from './StaffHoursBalanceChart';

interface ScheduleFairnessViewProps {
  profile: UserProfile;
  shifts: Shift[];
  staffDirectory: StaffDirectoryEntry[];
  shiftSubmissions: ShiftSubmission[];
  timeOffRequests: TimeOffRequest[];
  onNotifyBanner: (msg: string) => void;
}

export const ScheduleFairnessView: React.FC<ScheduleFairnessViewProps> = ({
  profile,
  shifts,
  staffDirectory,
  shiftSubmissions,
  timeOffRequests,
  onNotifyBanner,
}) => {
  const isManager = profile.role === 'manager';
  const [viewMode, setViewMode] = useState<'calendar' | 'table' | 'submissions'>('calendar');
  const [selectedWeekMonday, setSelectedWeekMonday] = useState(() =>
    getMondayOfWeek(formatIsoDateLocal(new Date()))
  );

  const [filter, setFilter] = useState<'all' | 'mine' | 'open' | 'overlaps' | 'completed'>('all');
  const [search, setSearch] = useState('');
  const [busy, setBusy] = useState(false);

  // Weekly Auto-Schedule Config Modal State
  const [showWeekConfig, setShowWeekConfig] = useState(false);
  const [templates, setTemplates] = useState<WeeklySlotTemplate[]>(DEFAULT_WEEKLY_TEMPLATES);

  // Submit Shift Availability Modal State
  const [showSubmitShift, setShowSubmitShift] = useState(false);
  const [subDates, setSubDates] = useState<string[]>(() => [formatIsoDateLocal(new Date())]);
  const [subStart, setSubStart] = useState('08:00');
  const [subEnd, setSubEnd] = useState('16:00');
  const [subDept, setSubDept] = useState(profile.department || 'Operations');
  const [subTitle, setSubTitle] = useState('Morning Operations Shift');
  const [subNotes, setSubNotes] = useState('');

  // Create Shift Modal State
  const [showNewShift, setShowNewShift] = useState(false);
  const [newTitle, setNewTitle] = useState('');
  const [newDept, setNewDept] = useState(profile.department || 'Operations');
  const [newDate, setNewDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [newStart, setNewStart] = useState('09:00');
  const [newEnd, setNewEnd] = useState('17:00');
  const [selectedCandidateUids, setSelectedCandidateUids] = useState<string[]>([profile.uid]);
  const [autoArrangeOnCreate, setAutoArrangeOnCreate] = useState(true);

  // Manual Manager Edit Shift Modal State
  const [editingShift, setEditingShift] = useState<Shift | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editDept, setEditDept] = useState('');
  const [editDate, setEditDate] = useState('');
  const [editStart, setEditStart] = useState('');
  const [editEnd, setEditEnd] = useState('');
  const [editAssignedUid, setEditAssignedUid] = useState('');
  const [editStatus, setEditStatus] = useState<ShiftStatus>('scheduled');
  const [editNote, setEditNote] = useState('');

  // Request Change Modal State
  const [changeTargetShift, setChangeTargetShift] = useState<Shift | null>(null);
  const [changeType, setChangeType] = useState<ScheduleChangeType>('time_change');
  const [proposedDate, setProposedDate] = useState('');
  const [proposedStart, setProposedStart] = useState('');
  const [proposedEnd, setProposedEnd] = useState('');
  const [proposedAssigneeUid, setProposedAssigneeUid] = useState('');
  const [changeReason, setChangeReason] = useState('');

  const fairnessMetrics = useMemo(
    () => buildStaffFairnessMetrics(staffDirectory, shifts),
    [staffDirectory, shifts]
  );
  const metricsMap = useMemo(
    () => new Map(fairnessMetrics.map((m) => [m.uid, m])),
    [fairnessMetrics]
  );

  const activeSubmissions = useMemo(
    () => shiftSubmissions.filter((s) => s.status === 'submitted'),
    [shiftSubmissions]
  );

  const overlapSummary = useMemo(() => detectOverlaps(shifts), [shifts]);
  const fairnessPlan = useMemo(
    () => buildFairnessArrangementPlan(shifts, staffDirectory, timeOffRequests),
    [shifts, staffDirectory, timeOffRequests]
  );
  const openAssignmentPlan = useMemo(
    () =>
      buildOpenShiftAssignmentPlan({
        shifts,
        submissions: activeSubmissions,
        staff: staffDirectory,
        timeOffRequests,
      }),
    [shifts, activeSubmissions, staffDirectory, timeOffRequests]
  );

  const approvedTimeOffInWeek = useMemo(() => {
    const weekDates = getWeekDates(selectedWeekMonday);
    const weekStart = weekDates[0];
    const weekEnd = weekDates[weekDates.length - 1];
    return timeOffRequests.filter(
      (req) =>
        req.status === 'approved' &&
        req.startDate <= weekEnd &&
        req.endDate >= weekStart
    );
  }, [timeOffRequests, selectedWeekMonday]);

  const openShiftsCount = useMemo(
    () => shifts.filter((s) => s.status === 'open' || !s.assignedUid).length,
    [shifts]
  );

  const filteredShifts = useMemo(() => {
    return shifts.filter((s) => {
      if (filter === 'mine' && s.assignedUid !== profile.uid) return false;
      if (filter === 'open' && s.status !== 'open' && Boolean(s.assignedUid)) return false;
      if (
        filter === 'overlaps' &&
        (s.status === 'open' || !s.assignedUid || (s.candidateUids.length <= 1 && s.status !== 'conflict'))
      ) {
        return false;
      }
      if (filter === 'completed' && s.status !== 'completed') return false;
      if (search.trim()) {
        const q = search.toLowerCase();
        const hit =
          s.title.toLowerCase().includes(q) ||
          (s.assignedName || 'unassigned').toLowerCase().includes(q) ||
          s.department.toLowerCase().includes(q) ||
          s.date.includes(q);
        if (!hit) return false;
      }
      return true;
    });
  }, [shifts, filter, search, profile.uid]);

  const toggleCandidate = (uid: string) => {
    setSelectedCandidateUids((prev) =>
      prev.includes(uid) ? prev.filter((id) => id !== uid) : [...prev, uid]
    );
  };

  const toggleSubDate = (dateIso: string) => {
    setSubDates((prev) =>
      prev.includes(dateIso) ? prev.filter((d) => d !== dateIso) : [...prev, dateIso]
    );
  };

  const handleSubmitAvailability = async (e: React.FormEvent) => {
    e.preventDefault();
    if (subDates.length === 0) {
      onNotifyBanner('Select at least one date to submit shift availability.');
      return;
    }
    setBusy(true);
    try {
      const count = await submitShiftAvailability({
        staffUid: profile.uid,
        staffName: profile.displayName,
        department: subDept,
        dates: subDates,
        startTime: subStart,
        endTime: subEnd,
        shiftTitle: subTitle,
        notes: subNotes,
      });
      setShowSubmitShift(false);
      setSubNotes('');
      onNotifyBanner(`Submitted shift availability for ${count} day(s).`);
    } finally {
      setBusy(false);
    }
  };

  const handleSubmitForOpenShift = async (shift: Shift) => {
    await submitShiftAvailability({
      staffUid: profile.uid,
      staffName: profile.displayName,
      department: shift.department,
      dates: [shift.date],
      startTime: shift.startTime,
      endTime: shift.endTime,
      shiftTitle: shift.title,
      notes: 'Submitted directly for open shift slot.',
    });
    onNotifyBanner(`Submitted availability for "${shift.title}" (${shift.date}).`);
  };

  const handleWithdrawSubmission = async (submissionId: string) => {
    await withdrawShiftSubmission(submissionId);
    onNotifyBanner('Shift submission withdrawn.');
  };

  const handleAssignOpenFromSubmissions = async () => {
    if (!isManager || openAssignmentPlan.length === 0) return;
    setBusy(true);
    try {
      const count = await commitFairnessArrangementPlan(
        openAssignmentPlan,
        shifts,
        staffDirectory,
        profile
      );
      onNotifyBanner(
        `Assigned ${count} open shift(s) to submitted staff based on working-time fairness.`
      );
    } finally {
      setBusy(false);
    }
  };

  /**
   * One-Click Full Week Schedule Generator + Overlap Fairness Resolution
   * Only assigns users who explicitly submitted for that shift; unsubmitted slots remain blank (open).
   */
  const handleOneClickGenerateWeek = async (activeTemplates = templates) => {
    if (!isManager) return;

    setBusy(true);
    try {
      const weekDates = getWeekDates(selectedWeekMonday);
      let resolvedOverlaps = 0;

      // First resolve any existing overlaps in the database by working-time fairness
      if (fairnessPlan.length > 0) {
        resolvedOverlaps = await commitFairnessArrangementPlan(
          fairnessPlan,
          shifts,
          staffDirectory,
          profile
        );
      }

      const drafts = generateWeeklyFairnessSchedule({
        weekDates,
        templates: activeTemplates,
        existingShifts: shifts,
        staff: staffDirectory,
        submissions: activeSubmissions,
        timeOffRequests,
      });

      if (drafts.length === 0) {
        onNotifyBanner(
          resolvedOverlaps > 0
            ? `Resolved ${resolvedOverlaps} overlap(s) by fairness; all slots for the week of ${selectedWeekMonday} are already on the calendar.`
            : `All shift slots for the week of ${selectedWeekMonday} are already on the calendar.`
        );
        setShowWeekConfig(false);
        return;
      }

      const result = await commitWeeklyScheduleBatch(
        drafts,
        selectedWeekMonday,
        staffDirectory,
        profile
      );

      setShowWeekConfig(false);
      setViewMode('calendar');
      onNotifyBanner(
        `Generated week of ${selectedWeekMonday}: ${result.assignedCount} assigned from submissions, ${result.openCount} left blank (no submission)${
          resolvedOverlaps > 0 ? `, and ${resolvedOverlaps} overlap(s) resolved` : ''
        }.`
      );
    } finally {
      setBusy(false);
    }
  };

  const handleCreateShift = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newTitle.trim()) return;
    if (!isManager && selectedCandidateUids.length === 0) return;

    setBusy(true);
    try {
      if (selectedCandidateUids.length === 0) {
        await createShiftRecord({
          title: newTitle,
          department: newDept,
          date: newDate,
          startTime: newStart,
          endTime: newEnd,
          assignedUid: '',
          assignedName: '',
          candidateUids: [],
          status: 'open',
          fairnessNote: 'Created as an open/blank slot — waiting for staff submissions.',
          actorUid: profile.uid,
        });
        setShowNewShift(false);
        setNewTitle('');
        onNotifyBanner('Created open shift slot (left blank until staff submit).');
        return;
      }

      let chosenUid = selectedCandidateUids[0];
      let chosenName =
        staffDirectory.find((s) => s.uid === chosenUid)?.displayName || profile.displayName;
      let status: 'scheduled' | 'conflict' = 'scheduled';
      let fairnessNote = '';
      let candidatesToSave = [...selectedCandidateUids];

      if (selectedCandidateUids.length > 1) {
        if (autoArrangeOnCreate) {
          const fairest = selectFairestCandidate(selectedCandidateUids, metricsMap, {
            date: newDate,
            timeOffRequests,
          });
          if (fairest) {
            chosenUid = fairest.uid;
            chosenName = fairest.displayName;
            candidatesToSave = [fairest.uid];
            fairnessNote = `Fairness selected ${fairest.displayName} (${fairest.effectiveTotalHours.toFixed(1)}h / ${fairest.weeklyHoursTarget}h) from available candidates.`;
          } else {
            onNotifyBanner(`All selected candidates have approved time off on ${newDate}.`);
            setBusy(false);
            return;
          }
        } else {
          status = 'conflict';
          fairnessNote = `${selectedCandidateUids.length} people scheduled at this time — awaiting fairness arrangement.`;
        }
      } else if (isStaffOffOnDate(chosenUid, newDate, timeOffRequests)) {
        onNotifyBanner(`${chosenName} has approved time off on ${newDate} and cannot be auto-scheduled.`);
        setBusy(false);
        return;
      }

      await createShiftRecord({
        title: newTitle,
        department: newDept,
        date: newDate,
        startTime: newStart,
        endTime: newEnd,
        assignedUid: chosenUid,
        assignedName: chosenName,
        candidateUids: candidatesToSave,
        status,
        fairnessNote,
        actorUid: profile.uid,
      });

      setShowNewShift(false);
      setNewTitle('');
      onNotifyBanner(
        selectedCandidateUids.length > 1 && autoArrangeOnCreate
          ? `Shift assigned to ${chosenName} based on working-time fairness.`
          : 'Shift created and notification dispatched.'
      );
    } finally {
      setBusy(false);
    }
  };

  const openEditModal = (shift: Shift) => {
    if (!isManager) return;
    setEditingShift(shift);
    setEditTitle(shift.title);
    setEditDept(shift.department);
    setEditDate(shift.date);
    setEditStart(shift.startTime);
    setEditEnd(shift.endTime);
    setEditAssignedUid(shift.assignedUid);
    setEditStatus(shift.status);
    setEditNote(shift.fairnessNote || '');
  };

  const handleSaveManualEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingShift || !isManager || !editTitle.trim()) return;
    setBusy(true);
    try {
      const isBlank = !editAssignedUid || editStatus === 'open';
      const staffMember = isBlank
        ? undefined
        : staffDirectory.find((s) => s.uid === editAssignedUid);
      const assignedName = isBlank
        ? ''
        : staffMember?.displayName || editingShift.assignedName;

      await updateShiftManually(
        editingShift,
        {
          title: editTitle,
          department: editDept,
          date: editDate,
          startTime: editStart,
          endTime: editEnd,
          assignedUid: isBlank ? '' : editAssignedUid,
          assignedName,
          status: isBlank ? 'open' : editStatus,
          fairnessNote: editNote,
        },
        profile
      );

      setEditingShift(null);
      onNotifyBanner(
        isBlank
          ? `Left "${editTitle}" (${editDate} ${editStart}–${editEnd}) blank / open.`
          : `Updated "${editTitle}" (${editDate} ${editStart}–${editEnd}) for ${assignedName}.`
      );
    } finally {
      setBusy(false);
    }
  };

  const handleDeleteShift = async (shift: Shift) => {
    if (!isManager) return;
    setBusy(true);
    try {
      await deleteShiftRecord(shift.id);
      setEditingShift(null);
      onNotifyBanner(`Removed shift "${shift.title}" (${shift.date}).`);
    } finally {
      setBusy(false);
    }
  };

  const handleCommitFairness = async () => {
    if (!isManager || fairnessPlan.length === 0) return;
    setBusy(true);
    try {
      const count = await commitFairnessArrangementPlan(
        fairnessPlan,
        shifts,
        staffDirectory,
        profile
      );
      onNotifyBanner(
        `Fairness arrangement applied across ${count} overlapping shift(s) and staff notified.`
      );
    } finally {
      setBusy(false);
    }
  };

  const openChangeModal = (shift: Shift) => {
    setChangeTargetShift(shift);
    setChangeType('time_change');
    setProposedDate(shift.date);
    setProposedStart(shift.startTime);
    setProposedEnd(shift.endTime);
    setProposedAssigneeUid(shift.assignedUid);
    setChangeReason('');
  };

  const handleSubmitChange = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!changeTargetShift || !changeReason.trim()) return;
    setBusy(true);
    try {
      const assignee = staffDirectory.find((s) => s.uid === proposedAssigneeUid);
      await createScheduleChangeRequest({
        shift: changeTargetShift,
        requesterUid: profile.uid,
        requesterName: profile.displayName,
        type: changeType,
        proposedDate,
        proposedStartTime: proposedStart,
        proposedEndTime: proposedEnd,
        proposedAssigneeUid:
          changeType === 'swap' || changeType === 'claim'
            ? proposedAssigneeUid || profile.uid
            : changeTargetShift.assignedUid,
        proposedAssigneeName:
          changeType === 'swap' || changeType === 'claim'
            ? assignee?.displayName || profile.displayName
            : changeTargetShift.assignedName,
        reason: changeReason,
      });
      setChangeTargetShift(null);
      onNotifyBanner('Schedule change request submitted to managers for approval.');
    } finally {
      setBusy(false);
    }
  };

  const updateTemplateField = (
    idx: number,
    patch: Partial<WeeklySlotTemplate>
  ) => {
    setTemplates((prev) =>
      prev.map((item, i) => (i === idx ? { ...item, ...patch } : item))
    );
  };

  const weekDatesForSubmit = useMemo(
    () => getWeekDates(selectedWeekMonday),
    [selectedWeekMonday]
  );

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900 tracking-tight">
            Schedule &amp; Fairness Arrangement
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            Staff submit available shifts; the system arranges only submitted staff by working-time fairness and leaves unsubmitted slots blank.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => {
              setSubDates([formatIsoDateLocal(new Date())]);
              setShowSubmitShift(true);
            }}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-sm font-medium text-blue-700 bg-blue-50/70 border border-blue-200 rounded-lg hover:bg-blue-100/70 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Hand className="w-4 h-4" />
            Submit Shift
          </button>

          {isManager && (
            <>
              <button
                type="button"
                disabled={busy}
                onClick={() => handleOneClickGenerateWeek(templates)}
                className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
              >
                <CalendarPlus className="w-4 h-4" />
                {busy ? 'Generating...' : 'Generate Week Schedule'}
              </button>
              <button
                type="button"
                onClick={() => setShowWeekConfig(true)}
                className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 whitespace-nowrap shrink-0 cursor-pointer"
                title="Customize weekly shift templates"
              >
                <SlidersHorizontal className="w-4 h-4" />
                Week Slots
              </button>
            </>
          )}
          <button
            type="button"
            onClick={() => {
              setSelectedCandidateUids(isManager ? [] : [profile.uid]);
              setShowNewShift(true);
            }}
            className="inline-flex items-center gap-1.5 px-3.5 py-2 text-sm font-medium text-slate-800 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            Add Shift
          </button>
        </div>
      </div>

      {/* Open Shifts with Matching Submissions Banner (Manager Action) */}
      {isManager && openAssignmentPlan.length > 0 && (
        <div className="bg-blue-50/60 border border-blue-200 rounded-xl p-5 space-y-3">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
            <div className="space-y-1">
              <div className="inline-flex items-center gap-2 text-xs font-semibold text-blue-900">
                <CheckCircle2 className="w-4 h-4 text-blue-700" />
                <span>
                  {openAssignmentPlan.length} Open Shift(s) Have Matching Staff Submissions
                </span>
              </div>
              <p className="text-xs text-blue-900/80 leading-relaxed">
                Assign blank shifts strictly to staff who submitted for those slots, selecting the fairest working-time balance.
              </p>
            </div>
            <button
              type="button"
              disabled={busy}
              onClick={handleAssignOpenFromSubmissions}
              className="inline-flex items-center gap-2 px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
            >
              <Scale className="w-4 h-4" />
              {busy ? 'Assigning...' : 'Assign Open Shifts by Fairness'}
            </button>
          </div>
        </div>
      )}

      {/* Fairness Arrangement Alert & Preview Panel */}
      {(fairnessPlan.length > 0 || overlapSummary.totalOverlappingShifts > 0) && (
        <div className="bg-amber-50/70 border border-amber-200 rounded-xl p-5 space-y-4">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
            <div className="space-y-1">
              <div className="inline-flex items-center gap-2 text-xs font-semibold text-amber-800">
                <AlertTriangle className="w-4 h-4" />
                <span>
                  {fairnessPlan.length} Multi-Person Overlap(s) Detected
                </span>
              </div>
              <p className="text-xs text-amber-900/90 leading-relaxed">
                Multiple staff are scheduled for overlapping time slots. Review the working-time fairness plan below or arrange automatically.
              </p>
            </div>
            {isManager ? (
              <button
                type="button"
                disabled={busy}
                onClick={handleCommitFairness}
                className="inline-flex items-center gap-2 px-4 py-2 text-xs font-medium text-white bg-slate-900 rounded-lg hover:bg-slate-800 disabled:opacity-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
              >
                <Scale className="w-4 h-4" />
                {busy ? 'Arranging...' : 'Arrange All Overlaps by Fairness'}
              </button>
            ) : (
              <span className="text-xs text-amber-800 font-medium">
                Manager role required to apply fairness rebalancing
              </span>
            )}
          </div>

          <div className="bg-white border border-amber-200 rounded-lg divide-y divide-slate-100">
            {fairnessPlan.map((item) => (
              <div key={item.shiftId} className="px-4 py-3 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 text-xs">
                <div className="space-y-0.5">
                  <div className="font-semibold text-slate-900">
                    {item.shiftTitle} · <span className="font-mono tabular-nums">{item.date} {item.timeWindow}</span>
                  </div>
                  <div className="text-slate-600">{item.reason}</div>
                </div>
                <div className="shrink-0 font-medium text-emerald-700 inline-flex items-center gap-1">
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  <span>{item.selectedAssigneeName}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Approved Time Off Notice for Selected Week */}
      {approvedTimeOffInWeek.length > 0 && (
        <div className="bg-slate-100/80 border border-slate-200 rounded-xl px-4 py-3 text-xs text-slate-700 flex flex-wrap items-center gap-x-3 gap-y-1">
          <span className="font-semibold text-slate-900">
            Approved Time Off (Excluded from Auto-Scheduling):
          </span>
          {approvedTimeOffInWeek.map((req) => (
            <span key={req.id} className="font-mono tabular-nums">
              <span className="font-sans font-medium text-slate-800">{req.staffName}</span>{' '}
              ({req.startDate === req.endDate ? req.startDate : `${req.startDate}–${req.endDate}`})
            </span>
          ))}
        </div>
      )}

      {/* Recharts: Staff Hours Balance vs Weekly Target */}
      <StaffHoursBalanceChart metrics={fairnessMetrics} />

      {/* View Mode & Search Toolbar */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex items-center gap-1 p-1 bg-slate-100 rounded-lg">
            <button
              type="button"
              onClick={() => setViewMode('calendar')}
              className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                viewMode === 'calendar'
                  ? 'bg-white text-slate-900 shadow-sm'
                  : 'text-slate-600 hover:text-slate-900'
              }`}
            >
              <Calendar className="w-3.5 h-3.5" />
              Calendar View
            </button>
            <button
              type="button"
              onClick={() => setViewMode('table')}
              className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                viewMode === 'table'
                  ? 'bg-white text-slate-900 shadow-sm'
                  : 'text-slate-600 hover:text-slate-900'
              }`}
            >
              <List className="w-3.5 h-3.5" />
              Table View
            </button>
            <button
              type="button"
              onClick={() => setViewMode('submissions')}
              className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                viewMode === 'submissions'
                  ? 'bg-white text-slate-900 shadow-sm'
                  : 'text-slate-600 hover:text-slate-900'
              }`}
            >
              <Hand className="w-3.5 h-3.5" />
              Submissions ({activeSubmissions.length})
            </button>
          </div>

          {viewMode !== 'submissions' && (
            <div className="inline-flex items-center gap-1 p-1 bg-slate-100 rounded-lg">
              {(
                [
                  { key: 'all', label: 'All Shifts' },
                  { key: 'mine', label: 'My Shifts' },
                  { key: 'open', label: `Open / Blank (${openShiftsCount})` },
                  { key: 'overlaps', label: `Overlaps (${overlapSummary.totalOverlappingShifts})` },
                  { key: 'completed', label: 'Completed' },
                ] as const
              ).map((tab) => (
                <button
                  key={tab.key}
                  type="button"
                  onClick={() => setFilter(tab.key)}
                  className={`px-3 py-1.5 text-xs font-medium rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                    filter === tab.key
                      ? 'bg-white text-slate-900 shadow-sm'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  {tab.label}
                </button>
              ))}
            </div>
          )}
        </div>

        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search shift, staff, or department..."
          className="w-full md:w-64 px-3 py-1.5 text-sm bg-white border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
        />
      </div>

      {/* Main View: Calendar, Table, or Submissions */}
      {viewMode === 'calendar' ? (
        <ShiftCalendarView
          profile={profile}
          shifts={filteredShifts}
          shiftSubmissions={activeSubmissions}
          selectedWeekMonday={selectedWeekMonday}
          onChangeWeekMonday={setSelectedWeekMonday}
          onEditShift={openEditModal}
          onDeleteShift={handleDeleteShift}
          onRequestChange={openChangeModal}
          onSubmitForShift={handleSubmitForOpenShift}
          onWithdrawSubmission={handleWithdrawSubmission}
          onNotifyBanner={onNotifyBanner}
        />
      ) : viewMode === 'submissions' ? (
        <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
          <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between gap-4">
            <div>
              <h2 className="text-sm font-semibold text-slate-900">
                {isManager ? 'Team Shift Submissions' : 'My Submitted Shift Availability'}
              </h2>
              <p className="text-xs text-slate-500 mt-0.5">
                Only staff with active submissions for a slot are eligible when generating schedules; unsubmitted slots stay blank.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setShowSubmitShift(true)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 whitespace-nowrap cursor-pointer"
            >
              <Hand className="w-3.5 h-3.5" />
              New Submission
            </button>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="border-b border-slate-200 bg-slate-50/60 text-xs font-medium text-slate-500">
                  <th className="py-3 px-4">Staff Member</th>
                  <th className="py-3 px-4">Date &amp; Window</th>
                  <th className="py-3 px-4">Department &amp; Shift</th>
                  <th className="py-3 px-4">Notes</th>
                  <th className="py-3 px-4 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-sm">
                {activeSubmissions.filter((sub) => {
                  if (!search.trim()) return true;
                  const q = search.toLowerCase();
                  return (
                    sub.staffName.toLowerCase().includes(q) ||
                    sub.department.toLowerCase().includes(q) ||
                    sub.shiftTitle.toLowerCase().includes(q) ||
                    sub.date.includes(q)
                  );
                }).length === 0 ? (
                  <tr>
                    <td colSpan={5} className="py-10 text-center text-sm text-slate-500">
                      No active shift submissions yet. Click &ldquo;Submit Shift&rdquo; to submit your availability.
                    </td>
                  </tr>
                ) : (
                  activeSubmissions
                    .filter((sub) => {
                      if (!search.trim()) return true;
                      const q = search.toLowerCase();
                      return (
                        sub.staffName.toLowerCase().includes(q) ||
                        sub.department.toLowerCase().includes(q) ||
                        sub.shiftTitle.toLowerCase().includes(q) ||
                        sub.date.includes(q)
                      );
                    })
                    .map((sub) => (
                      <tr key={sub.id} className="hover:bg-slate-50/70 transition-colors">
                        <td className="py-3 px-4 font-medium text-slate-900">{sub.staffName}</td>
                        <td className="py-3 px-4 font-mono tabular-nums text-xs text-slate-700 whitespace-nowrap">
                          {sub.date} · {sub.startTime}–{sub.endTime}
                        </td>
                        <td className="py-3 px-4">
                          <div className="text-sm text-slate-900">{sub.shiftTitle || 'General Availability'}</div>
                          <div className="text-xs text-slate-500">{sub.department}</div>
                        </td>
                        <td className="py-3 px-4 text-xs text-slate-600 max-w-xs truncate">
                          {sub.notes || '—'}
                        </td>
                        <td className="py-3 px-4 text-right">
                          {(sub.staffUid === profile.uid || isManager) && (
                            <button
                              type="button"
                              onClick={() => handleWithdrawSubmission(sub.id)}
                              className="px-2.5 py-1.5 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-md hover:bg-slate-50 cursor-pointer"
                            >
                              Withdraw
                            </button>
                          )}
                        </td>
                      </tr>
                    ))
                )}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex justify-end">
            <button
              type="button"
              onClick={() => {
                if (filteredShifts.length === 0) {
                  onNotifyBanner('No shifts to export.');
                  return;
                }
                downloadIcsFile(
                  filteredShifts,
                  `cadence-schedule-${selectedWeekMonday}.ics`,
                  'Cadence Shift Schedule'
                );
                onNotifyBanner(`Exported ${filteredShifts.length} shift(s) to .ics calendar file.`);
              }}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
            >
              <Download className="w-3.5 h-3.5" />
              Export Filtered Shifts (.ics)
            </button>
          </div>

          <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="border-b border-slate-200 bg-slate-50/60 text-xs font-medium text-slate-500">
                    <th className="py-3 px-4">Shift &amp; Department</th>
                    <th className="py-3 px-4">Date &amp; Window</th>
                    <th className="py-3 px-4 text-right">Hours</th>
                    <th className="py-3 px-4">Assigned Staff</th>
                    <th className="py-3 px-4">Status &amp; Fairness Note</th>
                    <th className="py-3 px-4 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 text-sm">
                  {filteredShifts.length === 0 ? (
                    <tr>
                      <td colSpan={6} className="py-10 text-center text-sm text-slate-500">
                        No shifts match the selected filter.
                      </td>
                    </tr>
                  ) : (
                    filteredShifts.map((shift) => {
                      const isOpen = shift.status === 'open' || !shift.assignedUid;
                      const metric = shift.assignedUid ? metricsMap.get(shift.assignedUid) : undefined;
                      const mySub = isOpen
                        ? activeSubmissions.find(
                            (sub) =>
                              sub.staffUid === profile.uid &&
                              doesSubmissionMatchSlot(sub, {
                                date: shift.date,
                                startTime: shift.startTime,
                                endTime: shift.endTime,
                                department: shift.department,
                              })
                          )
                        : undefined;
                      const matchingCount = isOpen
                        ? activeSubmissions.filter((sub) =>
                            doesSubmissionMatchSlot(sub, {
                              date: shift.date,
                              startTime: shift.startTime,
                              endTime: shift.endTime,
                              department: shift.department,
                            })
                          ).length
                        : 0;

                      return (
                        <tr key={shift.id} className="hover:bg-slate-50/70 transition-colors">
                          <td className="py-3 px-4">
                            <div className="font-medium text-slate-900">{shift.title}</div>
                            <div className="text-xs text-slate-500">{shift.department}</div>
                          </td>
                          <td className="py-3 px-4 font-mono tabular-nums text-xs text-slate-700 whitespace-nowrap">
                            {shift.date} · {shift.startTime}–{shift.endTime}
                          </td>
                          <td className="py-3 px-4 font-mono tabular-nums text-xs text-right text-slate-900">
                            {shift.durationHours.toFixed(1)}h
                          </td>
                          <td className="py-3 px-4">
                            <div className={`text-sm font-medium ${isOpen ? 'text-slate-400 italic' : 'text-slate-900'}`}>
                              {shift.assignedName || 'Unassigned (Blank)'}
                            </div>
                            {metric && (
                              <div className="text-[11px] text-slate-500 font-mono tabular-nums">
                                {metric.effectiveTotalHours.toFixed(1)}h / {metric.weeklyHoursTarget}h load
                              </div>
                            )}
                          </td>
                          <td className="py-3 px-4 max-w-xs">
                            <div className="text-xs font-medium">
                              {isOpen ? (
                                mySub ? (
                                  <span className="text-blue-700">Open · You Submitted</span>
                                ) : isManager && matchingCount > 0 ? (
                                  <span className="text-blue-700">Open · {matchingCount} Submitted</span>
                                ) : (
                                  <span className="text-slate-500">Open (Blank)</span>
                                )
                              ) : shift.status === 'conflict' || shift.candidateUids.length > 1 ? (
                                <span className="text-amber-700">
                                  Multi-person overlap ({shift.candidateUids.length})
                                </span>
                              ) : shift.status === 'pending_change' ? (
                                <span className="text-amber-700">Change requested</span>
                              ) : shift.status === 'completed' ? (
                                <span className="text-emerald-700">Completed</span>
                              ) : (
                                <span className="text-slate-600">Scheduled</span>
                              )}
                            </div>
                            {shift.fairnessNote && (
                              <div className="text-[11px] text-slate-500 truncate" title={shift.fairnessNote}>
                                {shift.fairnessNote}
                              </div>
                            )}
                          </td>
                          <td className="py-3 px-4 text-right whitespace-nowrap">
                            <div className="inline-flex items-center gap-1.5">
                              {isOpen && (
                                mySub ? (
                                  <button
                                    type="button"
                                    onClick={() => handleWithdrawSubmission(mySub.id)}
                                    className="px-2.5 py-1.5 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-md hover:bg-slate-50 cursor-pointer"
                                  >
                                    Withdraw
                                  </button>
                                ) : (
                                  <button
                                    type="button"
                                    onClick={() => handleSubmitForOpenShift(shift)}
                                    className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-white bg-blue-700 rounded-md hover:bg-blue-800 cursor-pointer"
                                  >
                                    <Hand className="w-3.5 h-3.5" />
                                    Submit
                                  </button>
                                )
                              )}
                              {isManager && (
                                <button
                                  type="button"
                                  onClick={() => openEditModal(shift)}
                                  className="inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-md hover:bg-slate-50 cursor-pointer"
                                >
                                  <Pencil className="w-3.5 h-3.5" />
                                  Edit
                                </button>
                              )}
                              {shift.status !== 'completed' && !isOpen && (
                                <button
                                  type="button"
                                  onClick={() => openChangeModal(shift)}
                                  className="inline-flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-md hover:bg-slate-50 cursor-pointer"
                                >
                                  <ArrowRightLeft className="w-3.5 h-3.5" />
                                  Change
                                </button>
                              )}
                            </div>
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {/* Modal: Submit Shift Availability */}
      {showSubmitShift && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-lg w-full p-6 space-y-5 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-base font-semibold text-slate-900">
                  Submit Shift Availability
                </h2>
                <p className="text-xs text-slate-500 mt-0.5">
                  You will only be scheduled for shifts that match your submitted days and hours.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowSubmitShift(false)}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSubmitAvailability} className="space-y-4">
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <label className="block text-xs font-medium text-slate-700">
                    Quick Pick Days (Week of {selectedWeekMonday})
                  </label>
                  <button
                    type="button"
                    onClick={() => setSubDates(weekDatesForSubmit.slice(0, 5))}
                    className="text-[11px] font-medium text-blue-700 hover:underline cursor-pointer"
                  >
                    Select Mon–Fri
                  </button>
                </div>
                <div className="grid grid-cols-7 gap-1.5">
                  {weekDatesForSubmit.map((dateIso) => {
                    const d = parseIsoDateLocal(dateIso);
                    const label = d.toLocaleDateString('en-US', { weekday: 'short' });
                    const day = d.getDate();
                    const active = subDates.includes(dateIso);
                    return (
                      <button
                        key={dateIso}
                        type="button"
                        onClick={() => toggleSubDate(dateIso)}
                        className={`py-2 px-1 rounded-lg border text-center transition-colors cursor-pointer ${
                          active
                            ? 'bg-blue-700 border-blue-700 text-white'
                            : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50'
                        }`}
                      >
                        <div className="text-[10px] opacity-80">{label}</div>
                        <div className="text-xs font-mono font-semibold">{day}</div>
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="space-y-2">
                <label className="block text-xs font-medium text-slate-700">
                  Shift Preset
                </label>
                <div className="flex flex-wrap gap-1.5">
                  {templates.map((tpl, i) => (
                    <button
                      key={i}
                      type="button"
                      onClick={() => {
                        setSubTitle(tpl.title);
                        setSubDept(tpl.department);
                        setSubStart(tpl.startTime);
                        setSubEnd(tpl.endTime);
                      }}
                      className="px-2.5 py-1.5 text-xs bg-slate-100 hover:bg-slate-200 text-slate-800 rounded-md cursor-pointer"
                    >
                      {tpl.title} ({tpl.startTime}–{tpl.endTime})
                    </button>
                  ))}
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Shift Label</label>
                  <input
                    type="text"
                    maxLength={120}
                    value={subTitle}
                    onChange={(e) => setSubTitle(e.target.value)}
                    placeholder="Morning Operations Shift"
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Department</label>
                  <input
                    type="text"
                    required
                    maxLength={60}
                    value={subDept}
                    onChange={(e) => setSubDept(e.target.value)}
                    placeholder="Operations or Any"
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Available Start</label>
                  <input
                    type="time"
                    required
                    value={subStart}
                    onChange={(e) => setSubStart(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Available End</label>
                  <input
                    type="time"
                    required
                    value={subEnd}
                    onChange={(e) => setSubEnd(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
              </div>

              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">Notes (Optional)</label>
                <input
                  type="text"
                  maxLength={300}
                  value={subNotes}
                  onChange={(e) => setSubNotes(e.target.value)}
                  placeholder="Preferred desk or availability note..."
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                />
              </div>

              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setShowSubmitShift(false)}
                  className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={busy || subDates.length === 0}
                  className="px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
                >
                  {busy ? 'Submitting...' : `Submit ${subDates.length} Day(s)`}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal: Configure & Generate Weekly Schedule */}
      {showWeekConfig && isManager && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-2xl w-full p-6 space-y-5 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-base font-semibold text-slate-900">
                  Weekly Auto-Schedule Templates
                </h2>
                <p className="text-xs text-slate-500 mt-0.5">
                  Configure the target week and daily shift blocks. The system balances assignments across staff by lowest working-time ratio.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowWeekConfig(false)}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="space-y-1">
              <label className="block text-xs font-medium text-slate-700">
                Week Starting (Monday)
              </label>
              <input
                type="date"
                value={selectedWeekMonday}
                onChange={(e) => {
                  if (e.target.value) {
                    setSelectedWeekMonday(getMondayOfWeek(e.target.value));
                  }
                }}
                className="px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
              />
            </div>

            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-slate-700">Daily Shift Slots</span>
                <button
                  type="button"
                  onClick={() =>
                    setTemplates((prev) => [
                      ...prev,
                      {
                        title: 'Support Coverage',
                        department: 'Operations',
                        startTime: '09:00',
                        endTime: '17:00',
                        includeWeekends: false,
                      },
                    ])
                  }
                  className="text-xs font-medium text-blue-700 hover:underline cursor-pointer"
                >
                  + Add Slot Template
                </button>
              </div>

              <div className="space-y-2">
                {templates.map((tpl, idx) => (
                  <div
                    key={idx}
                    className="p-3 border border-slate-200 rounded-lg grid grid-cols-1 sm:grid-cols-12 gap-2.5 items-center"
                  >
                    <input
                      type="text"
                      value={tpl.title}
                      maxLength={120}
                      onChange={(e) => updateTemplateField(idx, { title: e.target.value })}
                      className="sm:col-span-4 px-2.5 py-1.5 text-xs border border-slate-200 rounded-md"
                      placeholder="Shift title"
                    />
                    <input
                      type="text"
                      value={tpl.department}
                      maxLength={60}
                      onChange={(e) => updateTemplateField(idx, { department: e.target.value })}
                      className="sm:col-span-2 px-2.5 py-1.5 text-xs border border-slate-200 rounded-md"
                      placeholder="Dept"
                    />
                    <input
                      type="time"
                      value={tpl.startTime}
                      onChange={(e) => updateTemplateField(idx, { startTime: e.target.value })}
                      className="sm:col-span-2 px-2 py-1.5 text-xs font-mono border border-slate-200 rounded-md"
                    />
                    <input
                      type="time"
                      value={tpl.endTime}
                      onChange={(e) => updateTemplateField(idx, { endTime: e.target.value })}
                      className="sm:col-span-2 px-2 py-1.5 text-xs font-mono border border-slate-200 rounded-md"
                    />
                    <div className="sm:col-span-2 flex items-center justify-between gap-2">
                      <label className="inline-flex items-center gap-1 text-[11px] text-slate-600 cursor-pointer">
                        <input
                          type="checkbox"
                          checked={tpl.includeWeekends}
                          onChange={(e) =>
                            updateTemplateField(idx, { includeWeekends: e.target.checked })
                          }
                          className="rounded border-slate-300"
                        />
                        Sat/Sun
                      </label>
                      {templates.length > 1 && (
                        <button
                          type="button"
                          onClick={() =>
                            setTemplates((prev) => prev.filter((_, i) => i !== idx))
                          }
                          className="p-1 text-slate-400 hover:text-red-600 cursor-pointer"
                          aria-label="Remove slot"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <div className="pt-2 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setShowWeekConfig(false)}
                className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => handleOneClickGenerateWeek(templates)}
                className="inline-flex items-center gap-2 px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
              >
                <CalendarPlus className="w-3.5 h-3.5" />
                {busy ? 'Generating Week...' : 'Generate Fair Week Schedule'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal: Manual Manager Edit Shift */}
      {editingShift && isManager && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-lg w-full p-6 space-y-5 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between">
              <h2 className="text-base font-semibold text-slate-900">
                Edit Shift Manually
              </h2>
              <button
                type="button"
                onClick={() => setEditingShift(null)}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSaveManualEdit} className="space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Shift Title</label>
                  <input
                    type="text"
                    required
                    maxLength={120}
                    value={editTitle}
                    onChange={(e) => setEditTitle(e.target.value)}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Department</label>
                  <input
                    type="text"
                    required
                    maxLength={60}
                    value={editDept}
                    onChange={(e) => setEditDept(e.target.value)}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                  />
                </div>
              </div>

              <div className="grid grid-cols-3 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Date</label>
                  <input
                    type="date"
                    required
                    value={editDate}
                    onChange={(e) => setEditDate(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Start</label>
                  <input
                    type="time"
                    required
                    value={editStart}
                    onChange={(e) => setEditStart(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">End</label>
                  <input
                    type="time"
                    required
                    value={editEnd}
                    onChange={(e) => setEditEnd(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">
                    Assigned Staff Member
                  </label>
                  <select
                    value={editAssignedUid}
                    onChange={(e) => {
                      const val = e.target.value;
                      setEditAssignedUid(val);
                      if (!val) {
                        setEditStatus('open');
                      } else if (editStatus === 'open') {
                        setEditStatus('scheduled');
                      }
                    }}
                    className="w-full px-3 py-2 text-sm bg-white border border-slate-200 rounded-lg"
                  >
                    <option value="">Unassigned — Leave Blank (Open)</option>
                    {fairnessMetrics.map((m) => (
                      <option key={m.uid} value={m.uid}>
                        {m.displayName} ({m.effectiveTotalHours.toFixed(1)}h / {m.weeklyHoursTarget}h)
                        {isStaffOffOnDate(m.uid, editDate, timeOffRequests) ? ' — Approved Off' : ''}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Status</label>
                  <select
                    value={editStatus}
                    onChange={(e) => {
                      const next = e.target.value as ShiftStatus;
                      setEditStatus(next);
                      if (next === 'open') {
                        setEditAssignedUid('');
                      }
                    }}
                    className="w-full px-3 py-2 text-sm bg-white border border-slate-200 rounded-lg"
                  >
                    <option value="scheduled">Scheduled</option>
                    <option value="open">Open (Blank / Unassigned)</option>
                    <option value="completed">Completed</option>
                    <option value="conflict">Overlap / Conflict</option>
                    <option value="pending_change">Pending Change</option>
                  </select>
                </div>
              </div>

              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">
                  Schedule / Fairness Note
                </label>
                <input
                  type="text"
                  maxLength={300}
                  value={editNote}
                  onChange={(e) => setEditNote(e.target.value)}
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                />
              </div>

              <div className="pt-2 flex items-center justify-between gap-2">
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => handleDeleteShift(editingShift)}
                  className="inline-flex items-center gap-1.5 px-3 py-2 text-xs font-medium text-red-700 bg-white border border-red-200 rounded-lg hover:bg-red-50 cursor-pointer"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  Delete Shift
                </button>

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setEditingShift(null)}
                    className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={busy}
                    className="px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
                  >
                    {busy ? 'Saving...' : 'Save Manual Changes'}
                  </button>
                </div>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal: Schedule Shift (supports Multi-Candidate Fairness Selection) */}
      {showNewShift && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-lg w-full p-6 space-y-5 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between">
              <h2 className="text-base font-semibold text-slate-900">Schedule New Shift</h2>
              <button
                type="button"
                onClick={() => setShowNewShift(false)}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
                aria-label="Close modal"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleCreateShift} className="space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Shift Title</label>
                  <input
                    type="text"
                    required
                    maxLength={120}
                    value={newTitle}
                    onChange={(e) => setNewTitle(e.target.value)}
                    placeholder="Morning Support Desk"
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Department</label>
                  <input
                    type="text"
                    required
                    maxLength={60}
                    value={newDept}
                    onChange={(e) => setNewDept(e.target.value)}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                </div>
              </div>

              <div className="grid grid-cols-3 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Date</label>
                  <input
                    type="date"
                    required
                    value={newDate}
                    onChange={(e) => setNewDate(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Start</label>
                  <input
                    type="time"
                    required
                    value={newStart}
                    onChange={(e) => setNewStart(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">End</label>
                  <input
                    type="time"
                    required
                    value={newEnd}
                    onChange={(e) => setNewEnd(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <label className="block text-xs font-medium text-slate-700">
                    {isManager
                      ? 'Scheduled Staff / Candidates (leave empty for Blank / Open)'
                      : 'Scheduled Staff / Candidates'}
                  </label>
                  <span className="text-[11px] text-slate-500">
                    Sorted by lowest working-time load
                  </span>
                </div>
                <div className="border border-slate-200 rounded-lg divide-y divide-slate-100 max-h-44 overflow-y-auto">
                  {(isManager ? fairnessMetrics : fairnessMetrics.filter((m) => m.uid === profile.uid)).map(
                    (member) => {
                      const checked = selectedCandidateUids.includes(member.uid);
                      const isOff = isStaffOffOnDate(member.uid, newDate, timeOffRequests);
                      return (
                        <label
                          key={member.uid}
                          className={`px-3 py-2 flex items-center justify-between gap-3 text-xs hover:bg-slate-50 cursor-pointer ${
                            isOff ? 'opacity-60' : ''
                          }`}
                        >
                          <span className="flex items-center gap-2.5">
                            <input
                              type="checkbox"
                              checked={checked}
                              disabled={isOff}
                              onChange={() => toggleCandidate(member.uid)}
                              className="rounded border-slate-300 text-blue-700 focus:ring-blue-600 disabled:opacity-40"
                            />
                            <span className="font-medium text-slate-900">{member.displayName}</span>
                            <span className="text-slate-500">· {member.department}</span>
                            {isOff && (
                              <span className="text-red-700 font-medium">· Approved Off</span>
                            )}
                          </span>
                          <span className="font-mono tabular-nums text-slate-600">
                            {member.effectiveTotalHours.toFixed(1)}h / {member.weeklyHoursTarget}h
                          </span>
                        </label>
                      );
                    }
                  )}
                </div>
              </div>

              {selectedCandidateUids.length > 1 && (
                <label className="flex items-start gap-2.5 p-3 rounded-lg bg-slate-50 border border-slate-200 text-xs text-slate-700 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={autoArrangeOnCreate}
                    onChange={(e) => setAutoArrangeOnCreate(e.target.checked)}
                    className="mt-0.5 rounded border-slate-300 text-blue-700 focus:ring-blue-600"
                  />
                  <span>
                    <span className="font-medium text-slate-900 block">
                      Automatically select fairest person now
                    </span>
                    Uncheck to save as a multi-person overlap so managers can arrange it from the Fairness queue.
                  </span>
                </label>
              )}

              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setShowNewShift(false)}
                  className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={busy || (!isManager && selectedCandidateUids.length === 0)}
                  className="px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
                >
                  {busy
                    ? 'Saving...'
                    : selectedCandidateUids.length === 0
                    ? 'Save Open Shift (Blank)'
                    : 'Save Shift'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal: Request Schedule Change */}
      {changeTargetShift && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-lg w-full p-6 space-y-5">
            <div className="flex items-center justify-between">
              <h2 className="text-base font-semibold text-slate-900">
                Request Schedule Change
              </h2>
              <button
                type="button"
                onClick={() => setChangeTargetShift(null)}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
                aria-label="Close modal"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="text-xs text-slate-600 bg-slate-50 border border-slate-200 rounded-lg p-3">
              <div className="font-semibold text-slate-900">{changeTargetShift.title}</div>
              <div className="font-mono tabular-nums mt-0.5">
                Current: {changeTargetShift.date} · {changeTargetShift.startTime}–{changeTargetShift.endTime} ·{' '}
                <span className="font-sans">{changeTargetShift.assignedName}</span>
              </div>
            </div>

            <form onSubmit={handleSubmitChange} className="space-y-4">
              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">Change Type</label>
                <select
                  value={changeType}
                  onChange={(e) => setChangeType(e.target.value as ScheduleChangeType)}
                  className="w-full px-3 py-2 text-sm bg-white border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                >
                  <option value="time_change">Time / Date Adjustment</option>
                  <option value="swap">Shift Swap with Teammate</option>
                  <option value="claim">Claim Open / Reassign Shift</option>
                  <option value="drop">Drop Shift</option>
                </select>
              </div>

              {(changeType === 'swap' || changeType === 'claim') && (
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">
                    Proposed Assignee
                  </label>
                  <select
                    value={proposedAssigneeUid}
                    onChange={(e) => setProposedAssigneeUid(e.target.value)}
                    className="w-full px-3 py-2 text-sm bg-white border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                  >
                    {staffDirectory.map((member) => (
                      <option key={member.uid} value={member.uid}>
                        {member.displayName} ({member.department})
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <div className="grid grid-cols-3 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Proposed Date</label>
                  <input
                    type="date"
                    required
                    value={proposedDate}
                    onChange={(e) => setProposedDate(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Start</label>
                  <input
                    type="time"
                    required
                    value={proposedStart}
                    onChange={(e) => setProposedStart(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">End</label>
                  <input
                    type="time"
                    required
                    value={proposedEnd}
                    onChange={(e) => setProposedEnd(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
              </div>

              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">Reason</label>
                <textarea
                  required
                  rows={3}
                  maxLength={400}
                  value={changeReason}
                  onChange={(e) => setChangeReason(e.target.value)}
                  placeholder="Provide context for your manager..."
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                />
              </div>

              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setChangeTargetShift(null)}
                  className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={busy}
                  className="px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
                >
                  {busy ? 'Submitting...' : 'Submit for Manager Approval'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
