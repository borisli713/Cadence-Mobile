import React from 'react';
import {
  AlertCircle,
  ArrowRight,
  Bell,
  CalendarClock,
  CheckCircle2,
  ClipboardList,
  Scale,
} from 'lucide-react';
import {
  PushNotificationRecord,
  ScheduleChangeRequest,
  Shift,
  ShiftReport,
  StaffDirectoryEntry,
  UserProfile,
} from '../types';
import { detectOverlaps } from '../utils/fairness';

interface OverviewViewProps {
  profile: UserProfile;
  shifts: Shift[];
  scheduleChanges: ScheduleChangeRequest[];
  shiftReports: ShiftReport[];
  notifications: PushNotificationRecord[];
  staffDirectory: StaffDirectoryEntry[];
  onNavigate: (tab: 'overview' | 'schedule' | 'reports' | 'approvals' | 'timeoff' | 'notifications' | 'team') => void;
  onOpenNewReport: () => void;
}

export const OverviewView: React.FC<OverviewViewProps> = ({
  profile,
  shifts,
  scheduleChanges,
  shiftReports,
  notifications,
  staffDirectory,
  onNavigate,
  onOpenNewReport,
}) => {
  const isManager = profile.role === 'manager';
  const myStaffRecord = staffDirectory.find((s) => s.uid === profile.uid);
  const myShifts = shifts.filter((s) => s.assignedUid === profile.uid && s.status !== 'completed');
  const pendingChanges = scheduleChanges.filter((c) => c.status === 'pending');
  const overlapSummary = detectOverlaps(shifts);
  const unreadNotifs = notifications.filter((n) => n.recipientUid === profile.uid && !n.isRead);
  const myScheduledHours = myShifts.reduce((acc, s) => acc + s.durationHours, 0);
  const myWorkedHours = myStaffRecord?.totalHoursWorked ?? 0;

  return (
    <div className="space-y-8">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900 tracking-tight">
            Operations Overview
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            {profile.displayName} · {profile.department} · {isManager ? 'Manager Access' : 'Staff Member'}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={onOpenNewReport}
            className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <ClipboardList className="w-4 h-4" />
            Log Shift Report
          </button>
          {isManager && pendingChanges.length > 0 && (
            <button
              type="button"
              onClick={() => onNavigate('approvals')}
              className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-900 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
            >
              Review Approvals ({pendingChanges.length})
              <ArrowRight className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>

      {/* 4-Column Operational KPI Strip */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-2">
          <div className="text-xs font-medium text-slate-500">My Verified + Scheduled Hours</div>
          <div className="text-2xl font-semibold font-mono tabular-nums text-slate-900">
            {(myWorkedHours + myScheduledHours).toFixed(1)}h
          </div>
          <div className="text-xs text-slate-500 tabular-nums">
            {myWorkedHours.toFixed(1)}h worked · {myScheduledHours.toFixed(1)}h upcoming · Target {profile.weeklyHoursTarget}h
          </div>
        </div>

        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-2">
          <div className="text-xs font-medium text-slate-500">Pending Schedule Changes</div>
          <div className="text-2xl font-semibold font-mono tabular-nums text-slate-900">
            {pendingChanges.length}
          </div>
          <button
            type="button"
            onClick={() => onNavigate('approvals')}
            className="text-xs font-medium text-blue-700 hover:underline inline-flex items-center gap-1 cursor-pointer"
          >
            {isManager ? 'Open approval queue' : 'View change requests'}
            <ArrowRight className="w-3 h-3" />
          </button>
        </div>

        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-2">
          <div className="text-xs font-medium text-slate-500">Multi-Person Overlaps</div>
          <div className="text-2xl font-semibold font-mono tabular-nums text-slate-900">
            {overlapSummary.totalOverlappingShifts}
          </div>
          <button
            type="button"
            onClick={() => onNavigate('schedule')}
            className="text-xs font-medium text-blue-700 hover:underline inline-flex items-center gap-1 cursor-pointer"
          >
            {overlapSummary.totalOverlappingShifts > 0
              ? 'Arrange by working-time fairness'
              : 'All time slots balanced'}
            <ArrowRight className="w-3 h-3" />
          </button>
        </div>

        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-2">
          <div className="text-xs font-medium text-slate-500">Unread Shift Alerts</div>
          <div className="text-2xl font-semibold font-mono tabular-nums text-slate-900">
            {unreadNotifs.length}
          </div>
          <button
            type="button"
            onClick={() => onNavigate('notifications')}
            className="text-xs font-medium text-blue-700 hover:underline inline-flex items-center gap-1 cursor-pointer"
          >
            Open push notification center
            <ArrowRight className="w-3 h-3" />
          </button>
        </div>
      </div>

      {/* Main Split Content */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Upcoming Shifts Table */}
        <div className="lg:col-span-7 bg-white border border-slate-200 rounded-xl overflow-hidden">
          <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <CalendarClock className="w-4 h-4 text-slate-500" />
              <h2 className="text-sm font-semibold text-slate-900">Active Team Schedule</h2>
            </div>
            <button
              type="button"
              onClick={() => onNavigate('schedule')}
              className="text-xs font-medium text-blue-700 hover:underline cursor-pointer"
            >
              Manage schedule
            </button>
          </div>

          {shifts.length === 0 ? (
            <div className="p-8 text-center space-y-3">
              <p className="text-sm text-slate-600">No shifts scheduled yet.</p>
              <button
                type="button"
                onClick={() => onNavigate('schedule')}
                className="px-4 py-2 text-xs font-medium text-white bg-slate-900 rounded-lg hover:bg-slate-800 cursor-pointer"
              >
                Create First Shift
              </button>
            </div>
          ) : (
            <div className="divide-y divide-slate-100">
              {shifts.slice(0, 6).map((shift) => (
                <div
                  key={shift.id}
                  className="px-5 py-3.5 flex items-center justify-between gap-4 hover:bg-slate-50/70 transition-colors"
                >
                  <div className="min-w-0 space-y-0.5">
                    <div className="text-sm font-medium text-slate-900 truncate">
                      {shift.title}
                    </div>
                    <div className="text-xs text-slate-500 font-mono tabular-nums">
                      {shift.date} · {shift.startTime}–{shift.endTime} · {shift.durationHours.toFixed(1)}h ·{' '}
                      <span className="font-sans">{shift.assignedName || 'Unassigned'}</span>
                    </div>
                  </div>
                  <div className="shrink-0 text-xs font-medium">
                    {shift.status === 'open' || !shift.assignedUid ? (
                      <span className="text-slate-500">Open (Blank)</span>
                    ) : shift.status === 'conflict' || shift.candidateUids.length > 1 ? (
                      <span className="inline-flex items-center gap-1 text-amber-700">
                        <Scale className="w-3.5 h-3.5" />
                        Overlap ({shift.candidateUids.length})
                      </span>
                    ) : shift.status === 'pending_change' ? (
                      <span className="inline-flex items-center gap-1 text-amber-700">
                        <AlertCircle className="w-3.5 h-3.5" />
                        Change Pending
                      </span>
                    ) : shift.status === 'completed' ? (
                      <span className="inline-flex items-center gap-1 text-emerald-700">
                        <CheckCircle2 className="w-3.5 h-3.5" />
                        Completed
                      </span>
                    ) : (
                      <span className="text-slate-600">Scheduled</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Right Feed: Recent Shift Reports & Push Notifications */}
        <div className="lg:col-span-5 space-y-6">
          <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
            <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Bell className="w-4 h-4 text-slate-500" />
                <h2 className="text-sm font-semibold text-slate-900">Recent Status Updates</h2>
              </div>
              <button
                type="button"
                onClick={() => onNavigate('notifications')}
                className="text-xs font-medium text-blue-700 hover:underline cursor-pointer"
              >
                View all
              </button>
            </div>
            {notifications.filter((n) => n.recipientUid === profile.uid).length === 0 ? (
              <div className="p-6 text-xs text-slate-500 text-center">
                Status updates from shift approvals, denials, and fairness rebalancing will appear here.
              </div>
            ) : (
              <div className="divide-y divide-slate-100">
                {notifications
                  .filter((n) => n.recipientUid === profile.uid)
                  .slice(0, 4)
                  .map((n) => (
                    <div key={n.id} className="px-5 py-3.5 space-y-1">
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-xs font-semibold text-slate-900">{n.title}</span>
                        <span className="text-[11px] text-slate-500">
                          {n.isRead ? 'Read' : 'New'}
                        </span>
                      </div>
                      <p className="text-xs text-slate-600 leading-relaxed">{n.message}</p>
                    </div>
                  ))}
              </div>
            )}
          </div>

          <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
            <div className="px-5 py-4 border-b border-slate-200 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <ClipboardList className="w-4 h-4 text-slate-500" />
                <h2 className="text-sm font-semibold text-slate-900">Latest Shift Reports</h2>
              </div>
              <button
                type="button"
                onClick={() => onNavigate('reports')}
                className="text-xs font-medium text-blue-700 hover:underline cursor-pointer"
              >
                All reports
              </button>
            </div>
            {shiftReports.length === 0 ? (
              <div className="p-6 text-xs text-slate-500 text-center">
                No shift reports logged yet.
              </div>
            ) : (
              <div className="divide-y divide-slate-100">
                {shiftReports.slice(0, 3).map((rep) => (
                  <div key={rep.id} className="px-5 py-3 flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <div className="text-xs font-medium text-slate-900 truncate">
                        {rep.shiftTitle}
                      </div>
                      <div className="text-[11px] text-slate-500 font-mono tabular-nums">
                        {rep.staffName} · {rep.date} · {rep.workedHours.toFixed(2)}h
                      </div>
                    </div>
                    <span
                      className={`text-xs font-medium shrink-0 ${
                        rep.status === 'verified'
                          ? 'text-emerald-700'
                          : rep.status === 'flagged'
                          ? 'text-red-700'
                          : 'text-amber-700'
                      }`}
                    >
                      {rep.status === 'verified'
                        ? 'Verified'
                        : rep.status === 'flagged'
                        ? 'Flagged'
                        : 'Submitted'}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};
