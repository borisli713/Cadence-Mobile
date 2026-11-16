import React, { useMemo, useState } from 'react';
import { Check, CheckCheck, ShieldAlert, X } from 'lucide-react';
import {
  ScheduleChangeRequest,
  Shift,
  StaffDirectoryEntry,
  UserProfile,
} from '../types';
import {
  acceptAllPendingChanges,
  reviewSingleScheduleChange,
} from '../services/firestoreService';
import { buildStaffFairnessMetrics } from '../utils/fairness';

interface ManagerApprovalsViewProps {
  profile: UserProfile;
  scheduleChanges: ScheduleChangeRequest[];
  shifts: Shift[];
  staffDirectory: StaffDirectoryEntry[];
  onNotifyBanner: (msg: string) => void;
}

export const ManagerApprovalsView: React.FC<ManagerApprovalsViewProps> = ({
  profile,
  scheduleChanges,
  shifts,
  staffDirectory,
  onNotifyBanner,
}) => {
  const isManager = profile.role === 'manager';
  const [filter, setFilter] = useState<'pending' | 'approved' | 'denied' | 'all'>('pending');
  const [reviewNotes, setReviewNotes] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
  const [batchBusy, setBatchBusy] = useState(false);

  const pendingRequests = useMemo(
    () => scheduleChanges.filter((c) => c.status === 'pending'),
    [scheduleChanges]
  );

  const metricsMap = useMemo(() => {
    const m = buildStaffFairnessMetrics(staffDirectory, shifts);
    return new Map(m.map((item) => [item.uid, item]));
  }, [staffDirectory, shifts]);

  const filteredRequests = useMemo(() => {
    return scheduleChanges.filter((c) => {
      if (filter !== 'all' && c.status !== filter) return false;
      return true;
    });
  }, [scheduleChanges, filter]);

  const handleReviewOne = async (
    change: ScheduleChangeRequest,
    decision: 'approved' | 'denied'
  ) => {
    if (!isManager) return;
    setBusyId(change.id);
    try {
      await reviewSingleScheduleChange(
        change,
        decision,
        profile,
        reviewNotes[change.id] || '',
        shifts
      );
      onNotifyBanner(
        decision === 'approved'
          ? `Approved ${change.requesterName}'s ${change.type.replace('_', ' ')} and pushed notification.`
          : `Denied ${change.requesterName}'s ${change.type.replace('_', ' ')} and pushed notification.`
      );
    } finally {
      setBusyId(null);
    }
  };

  const handleAcceptAll = async () => {
    if (!isManager || pendingRequests.length === 0) return;
    setBatchBusy(true);
    try {
      const approvedCount = await acceptAllPendingChanges(
        pendingRequests,
        shifts,
        profile
      );
      onNotifyBanner(
        `Accepted all ${approvedCount} schedule change request(s) in one go and pushed status alerts to staff.`
      );
    } finally {
      setBatchBusy(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header with One-Click Accept All */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900 tracking-tight">
            Schedule Change Approvals
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            Approve or deny individual shift swaps, time adjustments, and claims—or accept all pending changes in one go.
          </p>
        </div>

        {isManager && (
          <button
            type="button"
            disabled={batchBusy || pendingRequests.length === 0}
            onClick={handleAcceptAll}
            className="inline-flex items-center gap-2 px-4 py-2.5 text-sm font-medium text-white bg-emerald-700 rounded-lg hover:bg-emerald-800 disabled:opacity-40 disabled:cursor-not-allowed transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <CheckCheck className="w-4 h-4" />
            {batchBusy
              ? 'Accepting All...'
              : `Accept All (${pendingRequests.length})`}
          </button>
        )}
      </div>

      {!isManager && (
        <div className="bg-slate-100 border border-slate-200 rounded-xl p-4 flex items-start gap-3 text-xs text-slate-700">
          <ShieldAlert className="w-4 h-4 text-slate-600 shrink-0 mt-0.5" />
          <span>
            You are viewing your submitted schedule change requests with Staff access. Manager approval controls (Approve, Deny, Accept All) are restricted to Manager accounts.
          </span>
        </div>
      )}

      {/* Filter Tabs */}
      <div className="inline-flex items-center gap-1 p-1 bg-slate-100 rounded-lg">
        {(
          [
            { key: 'pending', label: `Pending (${pendingRequests.length})` },
            {
              key: 'approved',
              label: `Approved (${scheduleChanges.filter((c) => c.status === 'approved').length})`,
            },
            {
              key: 'denied',
              label: `Denied (${scheduleChanges.filter((c) => c.status === 'denied').length})`,
            },
            { key: 'all', label: `All (${scheduleChanges.length})` },
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

      {/* Approval Queue */}
      <div className="bg-white border border-slate-200 rounded-xl divide-y divide-slate-200 overflow-hidden">
        {filteredRequests.length === 0 ? (
          <div className="p-10 text-center space-y-2">
            <p className="text-sm font-medium text-slate-700">
              No {filter === 'all' ? '' : filter} schedule change requests.
            </p>
            <p className="text-xs text-slate-500">
              Requests submitted from the Schedule tab will appear here for manager review.
            </p>
          </div>
        ) : (
          filteredRequests.map((req) => {
            const requesterMetric = metricsMap.get(req.requesterUid);
            const targetMetric = req.proposedAssigneeUid
              ? metricsMap.get(req.proposedAssigneeUid)
              : undefined;
            const isRowBusy = busyId === req.id || batchBusy;

            return (
              <div key={req.id} className="p-5 flex flex-col lg:flex-row lg:items-center justify-between gap-5">
                <div className="space-y-2 min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500">
                    <span className="font-semibold text-sm text-slate-900">
                      {req.shiftTitle}
                    </span>
                    <span aria-hidden="true">·</span>
                    <span className="capitalize">{req.type.replace('_', ' ')}</span>
                    <span aria-hidden="true">·</span>
                    <span>Requested by {req.requesterName}</span>
                    {requesterMetric && (
                      <>
                        <span aria-hidden="true">·</span>
                        <span className="font-mono tabular-nums">
                          {requesterMetric.effectiveTotalHours.toFixed(1)}h/{requesterMetric.weeklyHoursTarget}h
                        </span>
                      </>
                    )}
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                    <div className="p-2.5 bg-slate-50 border border-slate-200 rounded-lg">
                      <div className="text-[11px] font-medium text-slate-500">Current Slot</div>
                      <div className="font-mono tabular-nums text-slate-800 mt-0.5">
                        {req.currentDate} · {req.currentStartTime}–{req.currentEndTime}
                      </div>
                    </div>
                    <div className="p-2.5 bg-blue-50/50 border border-blue-200 rounded-lg">
                      <div className="text-[11px] font-medium text-blue-800">Proposed Change</div>
                      <div className="font-mono tabular-nums text-slate-900 mt-0.5">
                        {req.proposedDate} · {req.proposedStartTime}–{req.proposedEndTime}
                        {req.proposedAssigneeName ? (
                          <span className="font-sans">
                            {' '}
                            · {req.proposedAssigneeName}
                            {targetMetric
                              ? ` (${targetMetric.effectiveTotalHours.toFixed(1)}h load)`
                              : ''}
                          </span>
                        ) : null}
                      </div>
                    </div>
                  </div>

                  <p className="text-xs text-slate-700 leading-relaxed">
                    <span className="font-medium text-slate-900">Reason:</span> {req.reason}
                  </p>

                  {req.status !== 'pending' && (
                    <div className="text-xs text-slate-500">
                      <span
                        className={`font-semibold ${
                          req.status === 'approved' ? 'text-emerald-700' : 'text-red-700'
                        }`}
                      >
                        {req.status === 'approved' ? 'Approved' : 'Denied'}
                      </span>
                      {req.reviewedByName ? ` by ${req.reviewedByName}` : ''}
                      {req.reviewNote ? ` — ${req.reviewNote}` : ''}
                    </div>
                  )}
                </div>

                {/* Manager Action Controls */}
                {isManager && req.status === 'pending' && (
                  <div className="flex flex-col sm:flex-row lg:flex-col items-stretch sm:items-center lg:items-end gap-2.5 shrink-0 lg:w-64">
                    <input
                      type="text"
                      maxLength={300}
                      value={reviewNotes[req.id] || ''}
                      onChange={(e) =>
                        setReviewNotes((prev) => ({ ...prev, [req.id]: e.target.value }))
                      }
                      placeholder="Optional manager note..."
                      className="w-full px-3 py-1.5 text-xs bg-white border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                    />
                    <div className="flex items-center gap-2 w-full sm:w-auto lg:w-full justify-end">
                      <button
                        type="button"
                        disabled={isRowBusy}
                        onClick={() => handleReviewOne(req, 'denied')}
                        className="flex-1 lg:flex-none inline-flex items-center justify-center gap-1.5 px-3 py-2 text-xs font-medium text-red-700 bg-white border border-red-200 rounded-lg hover:bg-red-50 disabled:opacity-50 transition-colors cursor-pointer"
                      >
                        <X className="w-3.5 h-3.5" />
                        Deny
                      </button>
                      <button
                        type="button"
                        disabled={isRowBusy}
                        onClick={() => handleReviewOne(req, 'approved')}
                        className="flex-1 lg:flex-none inline-flex items-center justify-center gap-1.5 px-3.5 py-2 text-xs font-medium text-white bg-emerald-700 rounded-lg hover:bg-emerald-800 disabled:opacity-50 transition-colors cursor-pointer"
                      >
                        <Check className="w-3.5 h-3.5" />
                        Approve
                      </button>
                    </div>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
};
