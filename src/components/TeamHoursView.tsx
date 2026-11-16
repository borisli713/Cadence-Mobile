import React, { useMemo, useState } from 'react';
import { Database, Plus, Shield, UserCheck, X } from 'lucide-react';
import { Shift, StaffDirectoryEntry, UserProfile } from '../types';
import { buildStaffFairnessMetrics } from '../utils/fairness';
import {
  addRosterMember,
  seedSampleWorkspaceData,
  toggleManagerRole,
} from '../services/firestoreService';

interface TeamHoursViewProps {
  profile: UserProfile;
  staffDirectory: StaffDirectoryEntry[];
  shifts: Shift[];
  onNotifyBanner: (msg: string) => void;
}

export const TeamHoursView: React.FC<TeamHoursViewProps> = ({
  profile,
  staffDirectory,
  shifts,
  onNotifyBanner,
}) => {
  const isManager = profile.role === 'manager';
  const [busy, setBusy] = useState(false);
  const [showAddMember, setShowAddMember] = useState(false);
  const [memberName, setMemberName] = useState('');
  const [memberDept, setMemberDept] = useState('Operations');
  const [memberTarget, setMemberTarget] = useState(40);
  const [memberWorked, setMemberWorked] = useState(0);

  const metrics = useMemo(
    () => buildStaffFairnessMetrics(staffDirectory, shifts),
    [staffDirectory, shifts]
  );

  const handleSeed = async () => {
    if (!isManager) return;
    setBusy(true);
    try {
      await seedSampleWorkspaceData(profile);
      onNotifyBanner(
        'Loaded sample roster, overlapping shifts, and pending schedule change requests for testing.'
      );
    } finally {
      setBusy(false);
    }
  };

  const handleAddMember = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isManager || !memberName.trim()) return;
    setBusy(true);
    try {
      await addRosterMember({
        displayName: memberName,
        department: memberDept,
        weeklyHoursTarget: memberTarget,
        totalHoursWorked: memberWorked,
      });
      setShowAddMember(false);
      setMemberName('');
      setMemberWorked(0);
      onNotifyBanner(`Added ${memberName} to the operational roster.`);
    } finally {
      setBusy(false);
    }
  };

  const handleToggleRole = async (entry: StaffDirectoryEntry) => {
    if (!isManager || entry.uid === profile.uid) return;
    setBusy(true);
    try {
      const nextIsManager = entry.role !== 'manager';
      await toggleManagerRole(entry, nextIsManager, profile.uid);
      onNotifyBanner(
        `${entry.displayName} is now set to ${nextIsManager ? 'Manager' : 'Staff'}.`
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
            Team &amp; Working-Time Fairness Ledger
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            Staff ranked from lowest to highest working-time utilization. Lowest-ratio team members receive priority when overlapping slots are arranged.
          </p>
        </div>

        {isManager && (
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              disabled={busy}
              onClick={handleSeed}
              className="inline-flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 disabled:opacity-50 cursor-pointer"
            >
              <Database className="w-3.5 h-3.5" />
              Load Sample Overlaps &amp; Requests
            </button>
            <button
              type="button"
              onClick={() => setShowAddMember(true)}
              className="inline-flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 cursor-pointer"
            >
              <Plus className="w-3.5 h-3.5" />
              Add Roster Staff
            </button>
          </div>
        )}
      </div>

      {/* Fairness Table */}
      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50/60 text-xs font-medium text-slate-500">
                <th className="py-3 px-4">Priority</th>
                <th className="py-3 px-4">Team Member</th>
                <th className="py-3 px-4">Role</th>
                <th className="py-3 px-4 text-right">Worked</th>
                <th className="py-3 px-4 text-right">Scheduled</th>
                <th className="py-3 px-4 text-right">Target</th>
                <th className="py-3 px-4 w-48">Utilization</th>
                {isManager && <th className="py-3 px-4 text-right">Access</th>}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 text-sm">
              {metrics.map((m, idx) => {
                const rawEntry = staffDirectory.find((s) => s.uid === m.uid);
                const pct = Math.min(100, Math.round(m.utilizationRatio * 100));
                return (
                  <tr key={m.uid} className="hover:bg-slate-50/70 transition-colors">
                    <td className="py-3 px-4 font-mono tabular-nums text-xs text-slate-500">
                      #{idx + 1}
                    </td>
                    <td className="py-3 px-4">
                      <div className="font-medium text-slate-900">{m.displayName}</div>
                      <div className="text-xs text-slate-500">{m.department}</div>
                    </td>
                    <td className="py-3 px-4 text-xs font-medium">
                      {rawEntry?.role === 'manager' ? (
                        <span className="text-blue-700">Manager</span>
                      ) : (
                        <span className="text-slate-600">Staff</span>
                      )}
                    </td>
                    <td className="py-3 px-4 text-right font-mono tabular-nums text-xs text-slate-800">
                      {m.workedHours.toFixed(1)}h
                    </td>
                    <td className="py-3 px-4 text-right font-mono tabular-nums text-xs text-slate-800">
                      {m.scheduledHours.toFixed(1)}h
                    </td>
                    <td className="py-3 px-4 text-right font-mono tabular-nums text-xs text-slate-500">
                      {m.weeklyHoursTarget}h
                    </td>
                    <td className="py-3 px-4">
                      <div className="flex items-center gap-2">
                        <div className="flex-1 h-2 bg-slate-100 rounded-full overflow-hidden">
                          <div
                            className={`h-full rounded-full ${
                              pct >= 95
                                ? 'bg-red-600'
                                : pct >= 75
                                ? 'bg-amber-600'
                                : 'bg-emerald-600'
                            }`}
                            style={{ width: `${pct}%` }}
                          />
                        </div>
                        <span className="font-mono tabular-nums text-xs text-slate-700 w-10 text-right">
                          {Math.round(m.utilizationRatio * 100)}%
                        </span>
                      </div>
                    </td>
                    {isManager && (
                      <td className="py-3 px-4 text-right whitespace-nowrap">
                        {rawEntry && rawEntry.uid !== profile.uid && !rawEntry.isSampleRoster ? (
                          <button
                            type="button"
                            disabled={busy}
                            onClick={() => handleToggleRole(rawEntry)}
                            className="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-md hover:bg-slate-50 cursor-pointer"
                          >
                            {rawEntry.role === 'manager' ? (
                              <>
                                <UserCheck className="w-3.5 h-3.5" />
                                Set Staff
                              </>
                            ) : (
                              <>
                                <Shield className="w-3.5 h-3.5" />
                                Promote Manager
                              </>
                            )}
                          </button>
                        ) : (
                          <span className="text-xs text-slate-400">
                            {rawEntry?.uid === profile.uid ? 'Current User' : 'Roster Entry'}
                          </span>
                        )}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {/* Modal: Add Roster Staff */}
      {showAddMember && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-md w-full p-6 space-y-5">
            <div className="flex items-center justify-between">
              <h2 className="text-base font-semibold text-slate-900">Add Roster Staff Member</h2>
              <button
                type="button"
                onClick={() => setShowAddMember(false)}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <form onSubmit={handleAddMember} className="space-y-4">
              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">Name</label>
                <input
                  type="text"
                  required
                  maxLength={80}
                  value={memberName}
                  onChange={(e) => setMemberName(e.target.value)}
                  placeholder="Samira Patel"
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                />
              </div>
              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">Department</label>
                <input
                  type="text"
                  required
                  maxLength={60}
                  value={memberDept}
                  onChange={(e) => setMemberDept(e.target.value)}
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">
                    Prior Hours Worked
                  </label>
                  <input
                    type="number"
                    min={0}
                    max={500}
                    step="0.5"
                    value={memberWorked}
                    onChange={(e) => setMemberWorked(Number(e.target.value) || 0)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">
                    Weekly Target (h)
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={80}
                    value={memberTarget}
                    onChange={(e) => setMemberTarget(Number(e.target.value) || 40)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
              </div>
              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setShowAddMember(false)}
                  className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={busy}
                  className="px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
                >
                  Add to Roster
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
