import React, { useMemo, useState } from 'react';
import { CalendarOff, Check, Plus, Trash2, X } from 'lucide-react';
import { TimeOffRequest, TimeOffStatus, UserProfile } from '../types';
import {
  deleteTimeOffRequest,
  reviewTimeOffRequest,
  submitTimeOffRequest,
} from '../services/firestoreService';
import { formatIsoDateLocal, parseIsoDateLocal } from '../utils/calendarExport';

interface TimeOffRequestsViewProps {
  profile: UserProfile;
  timeOffRequests: TimeOffRequest[];
  onNotifyBanner: (msg: string) => void;
}

function countInclusiveDays(startDate: string, endDate: string): number {
  const start = parseIsoDateLocal(startDate);
  const end = parseIsoDateLocal(endDate >= startDate ? endDate : startDate);
  const diffMs = end.getTime() - start.getTime();
  return Math.max(1, Math.round(diffMs / 86400000) + 1);
}

export const TimeOffRequestsView: React.FC<TimeOffRequestsViewProps> = ({
  profile,
  timeOffRequests,
  onNotifyBanner,
}) => {
  const isManager = profile.role === 'manager';
  const [filter, setFilter] = useState<'all' | TimeOffStatus>('pending');
  const [notesById, setNotesById] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);

  // Request Time Off Modal State
  const [showModal, setShowModal] = useState(false);
  const [startDate, setStartDate] = useState(() => formatIsoDateLocal(new Date()));
  const [endDate, setEndDate] = useState(() => formatIsoDateLocal(new Date()));
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const visibleRequests = useMemo(() => {
    const scoped = isManager
      ? timeOffRequests
      : timeOffRequests.filter((r) => r.staffUid === profile.uid);

    if (filter === 'all') return scoped;
    return scoped.filter((r) => r.status === filter);
  }, [timeOffRequests, isManager, profile.uid, filter]);

  const counts = useMemo(() => {
    const scoped = isManager
      ? timeOffRequests
      : timeOffRequests.filter((r) => r.staffUid === profile.uid);
    return {
      pending: scoped.filter((r) => r.status === 'pending').length,
      approved: scoped.filter((r) => r.status === 'approved').length,
      denied: scoped.filter((r) => r.status === 'denied').length,
      all: scoped.length,
    };
  }, [timeOffRequests, isManager, profile.uid]);

  const handleCreateRequest = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!startDate || !reason.trim()) return;
    const effectiveEnd = endDate >= startDate ? endDate : startDate;

    setSubmitting(true);
    try {
      await submitTimeOffRequest({
        staffUid: profile.uid,
        staffName: profile.displayName,
        department: profile.department,
        startDate,
        endDate: effectiveEnd,
        reason,
      });
      setShowModal(false);
      setReason('');
      onNotifyBanner(
        effectiveEnd === startDate
          ? `Time off request submitted for ${startDate}.`
          : `Time off request submitted for ${startDate} to ${effectiveEnd}.`
      );
    } finally {
      setSubmitting(false);
    }
  };

  const handleReview = async (
    request: TimeOffRequest,
    decision: 'approved' | 'denied'
  ) => {
    if (!isManager) return;
    setBusyId(request.id);
    try {
      const note = notesById[request.id] || '';
      await reviewTimeOffRequest(request, decision, profile, note);
      onNotifyBanner(
        decision === 'approved'
          ? `Approved time off for ${request.staffName} (${request.startDate}–${request.endDate}). Auto-scheduler will keep these dates off.`
          : `Denied time off request for ${request.staffName}.`
      );
    } finally {
      setBusyId(null);
    }
  };

  const handleCancelPending = async (request: TimeOffRequest) => {
    setBusyId(request.id);
    try {
      await deleteTimeOffRequest(request.id);
      onNotifyBanner('Pending time off request withdrawn.');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900 tracking-tight">
            Time Off Requests
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            Staff submit unavailable dates; approved requests automatically exclude them from fairness scheduling on those off days.
          </p>
        </div>

        <button
          type="button"
          onClick={() => {
            const today = formatIsoDateLocal(new Date());
            setStartDate(today);
            setEndDate(today);
            setShowModal(true);
          }}
          className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
        >
          <Plus className="w-4 h-4" />
          Request Time Off
        </button>
      </div>

      {/* Filter Tabs */}
      <div className="inline-flex items-center gap-1 p-1 bg-slate-100 rounded-lg">
        {(
          [
            { key: 'pending', label: `Pending (${counts.pending})` },
            { key: 'approved', label: `Approved (${counts.approved})` },
            { key: 'denied', label: `Denied (${counts.denied})` },
            { key: 'all', label: `All (${counts.all})` },
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

      {!isManager && (
        <div className="bg-slate-100 border border-slate-200 rounded-xl px-4 py-3 text-xs text-slate-700">
          Viewing your personal time off requests. Once approved by a manager, auto-scheduling will never assign you on those dates.
        </div>
      )}

      {/* Requests List */}
      <div className="bg-white border border-slate-200 rounded-xl divide-y divide-slate-100 overflow-hidden">
        {visibleRequests.length === 0 ? (
          <div className="p-10 text-center space-y-2">
            <CalendarOff className="w-6 h-6 text-slate-300 mx-auto" />
            <p className="text-sm text-slate-500">
              No {filter === 'all' ? '' : `${filter} `}time off requests found.
            </p>
          </div>
        ) : (
          visibleRequests.map((item) => {
            const days = countInclusiveDays(item.startDate, item.endDate);
            const dateLabel =
              item.startDate === item.endDate
                ? item.startDate
                : `${item.startDate} to ${item.endDate}`;

            return (
              <div
                key={item.id}
                className="p-5 flex flex-col lg:flex-row lg:items-center justify-between gap-4"
              >
                <div className="space-y-1.5 min-w-0">
                  <div className="flex flex-wrap items-center gap-2 text-xs">
                    <span className="text-sm font-semibold text-slate-900">
                      {item.staffName}
                    </span>
                    <span aria-hidden="true" className="text-slate-300">·</span>
                    <span className="text-slate-500">{item.department}</span>
                    <span aria-hidden="true" className="text-slate-300">·</span>
                    <span className="font-mono tabular-nums font-medium text-slate-800">
                      {dateLabel} ({days} {days === 1 ? 'day' : 'days'})
                    </span>
                    <span aria-hidden="true" className="text-slate-300">·</span>
                    <span
                      className={`font-medium ${
                        item.status === 'approved'
                          ? 'text-emerald-700'
                          : item.status === 'denied'
                          ? 'text-red-700'
                          : 'text-amber-700'
                      }`}
                    >
                      {item.status === 'approved'
                        ? 'Approved (Off Day)'
                        : item.status === 'denied'
                        ? 'Denied'
                        : 'Pending Review'}
                    </span>
                  </div>

                  <p className="text-xs text-slate-700 leading-relaxed">
                    Reason: {item.reason}
                  </p>

                  {item.status !== 'pending' && (
                    <div className="text-xs text-slate-500">
                      Reviewed by {item.reviewedByName || 'Manager'}
                      {item.reviewNote ? ` · "${item.reviewNote}"` : ''}
                    </div>
                  )}
                </div>

                {item.status === 'pending' && isManager ? (
                  <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-2 shrink-0">
                    <input
                      type="text"
                      value={notesById[item.id] || ''}
                      onChange={(e) =>
                        setNotesById((prev) => ({
                          ...prev,
                          [item.id]: e.target.value,
                        }))
                      }
                      placeholder="Optional note..."
                      maxLength={300}
                      className="px-3 py-1.5 text-xs border border-slate-200 rounded-lg w-full sm:w-44 focus:outline-none focus:ring-2 focus:ring-blue-600"
                    />
                    <button
                      type="button"
                      disabled={busyId === item.id}
                      onClick={() => handleReview(item, 'denied')}
                      className="inline-flex items-center justify-center gap-1 px-3 py-1.5 text-xs font-medium text-red-700 bg-white border border-red-200 rounded-lg hover:bg-red-50 disabled:opacity-50 cursor-pointer"
                    >
                      <X className="w-3.5 h-3.5" />
                      Deny
                    </button>
                    <button
                      type="button"
                      disabled={busyId === item.id}
                      onClick={() => handleReview(item, 'approved')}
                      className="inline-flex items-center justify-center gap-1 px-3.5 py-1.5 text-xs font-medium text-white bg-emerald-700 rounded-lg hover:bg-emerald-800 disabled:opacity-50 cursor-pointer"
                    >
                      <Check className="w-3.5 h-3.5" />
                      Approve
                    </button>
                  </div>
                ) : (
                  item.status === 'pending' &&
                  item.staffUid === profile.uid && (
                    <button
                      type="button"
                      disabled={busyId === item.id}
                      onClick={() => handleCancelPending(item)}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-600 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 disabled:opacity-50 shrink-0 cursor-pointer"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                      Withdraw
                    </button>
                  )
                )}
              </div>
            );
          })
        )}
      </div>

      {/* Modal: Request Time Off */}
      {showModal && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-md w-full p-6 space-y-5">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="text-base font-semibold text-slate-900">
                  Request Time Off
                </h2>
                <p className="text-xs text-slate-500 mt-0.5">
                  Select dates you cannot work. Approved dates are excluded from auto-scheduling.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowModal(false)}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
                aria-label="Close modal"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleCreateRequest} className="space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">
                    Start Date
                  </label>
                  <input
                    type="date"
                    required
                    value={startDate}
                    onChange={(e) => {
                      const next = e.target.value;
                      setStartDate(next);
                      if (endDate < next) setEndDate(next);
                    }}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">
                    End Date
                  </label>
                  <input
                    type="date"
                    required
                    min={startDate}
                    value={endDate}
                    onChange={(e) => setEndDate(e.target.value)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                </div>
              </div>

              <div className="text-xs text-slate-500 font-mono tabular-nums">
                Duration: {countInclusiveDays(startDate, endDate)}{' '}
                {countInclusiveDays(startDate, endDate) === 1 ? 'day' : 'days'} off
              </div>

              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">
                  Reason / Notes
                </label>
                <textarea
                  required
                  rows={3}
                  maxLength={400}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  placeholder="Personal appointment, travel, or unavoidable conflict..."
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-600"
                />
              </div>

              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setShowModal(false)}
                  className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={submitting || !reason.trim()}
                  className="px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
                >
                  {submitting ? 'Submitting...' : 'Submit Time Off Request'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
