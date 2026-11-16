import React, { useMemo, useState } from 'react';
import { Check, Flag, Plus, X } from 'lucide-react';
import { Shift, ShiftReport, StaffDirectoryEntry, UserProfile } from '../types';
import { computeDurationHours } from '../utils/fairness';
import { submitShiftReportRecord, verifyOrFlagShiftReport } from '../services/firestoreService';

interface ShiftReportsViewProps {
  profile: UserProfile;
  shifts: Shift[];
  shiftReports: ShiftReport[];
  staffDirectory: StaffDirectoryEntry[];
  showModalInitially?: boolean;
  onCloseInitialModal?: () => void;
  onNotifyBanner: (msg: string) => void;
}

export const ShiftReportsView: React.FC<ShiftReportsViewProps> = ({
  profile,
  shifts,
  shiftReports,
  staffDirectory,
  showModalInitially = false,
  onCloseInitialModal,
  onNotifyBanner,
}) => {
  const isManager = profile.role === 'manager';
  const [filter, setFilter] = useState<'all' | 'submitted' | 'verified' | 'flagged'>('all');
  const [showCreate, setShowCreate] = useState(showModalInitially);
  const [busy, setBusy] = useState(false);

  const myOpenShifts = useMemo(
    () => shifts.filter((s) => s.assignedUid === profile.uid && s.status !== 'completed'),
    [shifts, profile.uid]
  );

  const [selectedShiftId, setSelectedShiftId] = useState('');
  const [shiftTitle, setShiftTitle] = useState('');
  const [department, setDepartment] = useState(profile.department || 'Operations');
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [actualStart, setActualStart] = useState('09:00');
  const [actualEnd, setActualEnd] = useState('17:00');
  const [breakMinutes, setBreakMinutes] = useState(30);
  const [handoffNotes, setHandoffNotes] = useState('');

  const modalOpen = showCreate || showModalInitially;

  const closeModal = () => {
    setShowCreate(false);
    onCloseInitialModal?.();
  };

  const handleSelectShift = (shiftId: string) => {
    setSelectedShiftId(shiftId);
    const s = myOpenShifts.find((item) => item.id === shiftId);
    if (s) {
      setShiftTitle(s.title);
      setDepartment(s.department);
      setDate(s.date);
      setActualStart(s.startTime);
      setActualEnd(s.endTime);
    }
  };

  const previewWorkedHours = useMemo(
    () => computeDurationHours(actualStart, actualEnd, breakMinutes),
    [actualStart, actualEnd, breakMinutes]
  );

  const filteredReports = useMemo(() => {
    return shiftReports.filter((r) => {
      if (filter !== 'all' && r.status !== filter) return false;
      return true;
    });
  }, [shiftReports, filter]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!shiftTitle.trim()) return;
    setBusy(true);
    try {
      await submitShiftReportRecord({
        shiftId: selectedShiftId,
        shiftTitle,
        staffUid: profile.uid,
        staffName: profile.displayName,
        department,
        date,
        actualStartTime: actualStart,
        actualEndTime: actualEnd,
        breakMinutes,
        handoffNotes,
      });
      setShiftTitle('');
      setHandoffNotes('');
      setSelectedShiftId('');
      closeModal();
      onNotifyBanner('Shift report submitted for verification.');
    } finally {
      setBusy(false);
    }
  };

  const handleDecision = async (report: ShiftReport, decision: 'verified' | 'flagged') => {
    if (!isManager) return;
    setBusy(true);
    try {
      await verifyOrFlagShiftReport(report, decision, profile, staffDirectory);
      onNotifyBanner(
        decision === 'verified'
          ? `Verified ${report.workedHours.toFixed(2)}h for ${report.staffName} and sent push notification.`
          : `Flagged shift report from ${report.staffName} and notified staff.`
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900 tracking-tight">
            Shift Reports
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            Log completed shift hours, break deductions, and operational handoff notes.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setShowCreate(true)}
          className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
        >
          <Plus className="w-4 h-4" />
          Submit Shift Report
        </button>
      </div>

      {/* Filter Bar */}
      <div className="inline-flex items-center gap-1 p-1 bg-slate-100 rounded-lg">
        {(
          [
            { key: 'all', label: `All (${shiftReports.length})` },
            {
              key: 'submitted',
              label: `Pending Verification (${shiftReports.filter((r) => r.status === 'submitted').length})`,
            },
            {
              key: 'verified',
              label: `Verified (${shiftReports.filter((r) => r.status === 'verified').length})`,
            },
            {
              key: 'flagged',
              label: `Flagged (${shiftReports.filter((r) => r.status === 'flagged').length})`,
            },
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

      {/* Table */}
      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50/60 text-xs font-medium text-slate-500">
                <th className="py-3 px-4">Shift &amp; Staff</th>
                <th className="py-3 px-4">Date &amp; Actual Window</th>
                <th className="py-3 px-4 text-right">Break</th>
                <th className="py-3 px-4 text-right">Net Hours</th>
                <th className="py-3 px-4">Handoff Notes</th>
                <th className="py-3 px-4">Status</th>
                {isManager && <th className="py-3 px-4 text-right">Review</th>}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 text-sm">
              {filteredReports.length === 0 ? (
                <tr>
                  <td colSpan={isManager ? 7 : 6} className="py-10 text-center text-sm text-slate-500">
                    No shift reports logged yet. Click &ldquo;Submit Shift Report&rdquo; to record completed hours.
                  </td>
                </tr>
              ) : (
                filteredReports.map((rep) => (
                  <tr key={rep.id} className="hover:bg-slate-50/70 transition-colors">
                    <td className="py-3 px-4">
                      <div className="font-medium text-slate-900">{rep.shiftTitle}</div>
                      <div className="text-xs text-slate-500">
                        {rep.staffName} · {rep.department}
                      </div>
                    </td>
                    <td className="py-3 px-4 font-mono tabular-nums text-xs text-slate-700 whitespace-nowrap">
                      {rep.date} · {rep.actualStartTime}–{rep.actualEndTime}
                    </td>
                    <td className="py-3 px-4 font-mono tabular-nums text-xs text-right text-slate-600">
                      {rep.breakMinutes}m
                    </td>
                    <td className="py-3 px-4 font-mono tabular-nums text-xs text-right font-semibold text-slate-900">
                      {rep.workedHours.toFixed(2)}h
                    </td>
                    <td className="py-3 px-4 text-xs text-slate-600 max-w-xs truncate" title={rep.handoffNotes}>
                      {rep.handoffNotes || 'No handoff notes'}
                    </td>
                    <td className="py-3 px-4 text-xs font-medium whitespace-nowrap">
                      {rep.status === 'verified' ? (
                        <span className="text-emerald-700">Verified</span>
                      ) : rep.status === 'flagged' ? (
                        <span className="text-red-700">Flagged</span>
                      ) : (
                        <span className="text-amber-700">Pending Review</span>
                      )}
                    </td>
                    {isManager && (
                      <td className="py-3 px-4 text-right whitespace-nowrap">
                        {rep.status === 'submitted' ? (
                          <div className="inline-flex items-center gap-1.5">
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => handleDecision(rep, 'verified')}
                              className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-white bg-emerald-700 rounded-md hover:bg-emerald-800 disabled:opacity-50 cursor-pointer"
                            >
                              <Check className="w-3.5 h-3.5" />
                              Verify
                            </button>
                            <button
                              type="button"
                              disabled={busy}
                              onClick={() => handleDecision(rep, 'flagged')}
                              className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-red-700 bg-white border border-red-200 rounded-md hover:bg-red-50 disabled:opacity-50 cursor-pointer"
                            >
                              <Flag className="w-3.5 h-3.5" />
                              Flag
                            </button>
                          </div>
                        ) : (
                          <span className="text-xs text-slate-400">Reviewed</span>
                        )}
                      </td>
                    )}
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Modal: Submit Shift Report */}
      {modalOpen && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-lg w-full p-6 space-y-5 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between">
              <h2 className="text-base font-semibold text-slate-900">Submit Shift Report</h2>
              <button
                type="button"
                onClick={closeModal}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
                aria-label="Close modal"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSubmit} className="space-y-4">
              {myOpenShifts.length > 0 && (
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">
                    Link to Scheduled Shift (optional)
                  </label>
                  <select
                    value={selectedShiftId}
                    onChange={(e) => handleSelectShift(e.target.value)}
                    className="w-full px-3 py-2 text-sm bg-white border border-slate-200 rounded-lg"
                  >
                    <option value="">Ad-hoc / Unlinked Shift</option>
                    {myOpenShifts.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.title} ({s.date} {s.startTime}–{s.endTime})
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Shift Title</label>
                  <input
                    type="text"
                    required
                    maxLength={120}
                    value={shiftTitle}
                    onChange={(e) => setShiftTitle(e.target.value)}
                    placeholder="Evening Floor Coverage"
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Department</label>
                  <input
                    type="text"
                    required
                    maxLength={60}
                    value={department}
                    onChange={(e) => setDepartment(e.target.value)}
                    className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                  />
                </div>
              </div>

              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div className="space-y-1 col-span-2 sm:col-span-1">
                  <label className="block text-xs font-medium text-slate-700">Date</label>
                  <input
                    type="date"
                    required
                    value={date}
                    onChange={(e) => setDate(e.target.value)}
                    className="w-full px-2.5 py-2 text-xs font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Clock In</label>
                  <input
                    type="time"
                    required
                    value={actualStart}
                    onChange={(e) => setActualStart(e.target.value)}
                    className="w-full px-2.5 py-2 text-xs font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Clock Out</label>
                  <input
                    type="time"
                    required
                    value={actualEnd}
                    onChange={(e) => setActualEnd(e.target.value)}
                    className="w-full px-2.5 py-2 text-xs font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">Break (min)</label>
                  <input
                    type="number"
                    min={0}
                    max={180}
                    value={breakMinutes}
                    onChange={(e) => setBreakMinutes(Number(e.target.value) || 0)}
                    className="w-full px-2.5 py-2 text-xs font-mono border border-slate-200 rounded-lg"
                  />
                </div>
              </div>

              <div className="px-3 py-2 bg-slate-50 border border-slate-200 rounded-lg flex items-center justify-between text-xs">
                <span className="text-slate-600">Calculated Net Worked Hours</span>
                <span className="font-mono tabular-nums font-semibold text-slate-900">
                  {previewWorkedHours.toFixed(2)} hours
                </span>
              </div>

              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">
                  Handoff &amp; Incident Notes
                </label>
                <textarea
                  rows={3}
                  maxLength={600}
                  value={handoffNotes}
                  onChange={(e) => setHandoffNotes(e.target.value)}
                  placeholder="Summarize tasks completed, open tickets, or equipment status for the next shift..."
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                />
              </div>

              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={closeModal}
                  className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={busy}
                  className="px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
                >
                  {busy ? 'Submitting...' : 'Submit Report'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
