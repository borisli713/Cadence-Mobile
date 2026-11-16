import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { onAuthStateChanged, signOut, User } from 'firebase/auth';
import { collection, onSnapshot, query, where } from 'firebase/firestore';
import {
  Bell,
  CalendarClock,
  CalendarOff,
  CheckCheck,
  ClipboardList,
  LayoutDashboard,
  LogOut,
  Menu,
  Settings,
  Users,
  X,
} from 'lucide-react';
import { auth, db, handleFirestoreError, OperationType, testFirestoreConnection } from './firebase';
import {
  PushNotificationRecord,
  ScheduleChangeRequest,
  Shift,
  ShiftReport,
  ShiftSubmission,
  StaffDirectoryEntry,
  TimeOffRequest,
  UserProfile,
} from './types';
import { ensureVerifiedUserProvisioned, updateUserPreferences } from './services/firestoreService';
import {
  dispatchBrowserPushNotification,
  getBrowserPushState,
  PushSupportState,
  registerNotificationServiceWorker,
  requestBrowserPushPermission,
} from './utils/notifications';
import { AuthGate } from './components/AuthGate';
import { ErrorBoundary } from './components/ErrorBoundary';
import { OverviewView } from './components/OverviewView';
import { ScheduleFairnessView } from './components/ScheduleFairnessView';
import { ShiftReportsView } from './components/ShiftReportsView';
import { ManagerApprovalsView } from './components/ManagerApprovalsView';
import { NotificationsView } from './components/NotificationsView';
import { TeamHoursView } from './components/TeamHoursView';
import { TimeOffRequestsView } from './components/TimeOffRequestsView';

type NavTab =
  | 'overview'
  | 'schedule'
  | 'reports'
  | 'approvals'
  | 'timeoff'
  | 'notifications'
  | 'team';

const TAB_LABELS: Record<NavTab, string> = {
  overview: 'Overview',
  schedule: 'Schedule & Fairness',
  reports: 'Shift Reports',
  approvals: 'Approvals',
  timeoff: 'Time Off Requests',
  notifications: 'Notifications',
  team: 'Team & Hours',
};

function WorkspaceApp() {
  const [authReady, setAuthReady] = useState(false);
  const [firebaseUser, setFirebaseUser] = useState<User | null>(null);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loadingProfile, setLoadingProfile] = useState(false);

  const [activeTab, setActiveTab] = useState<NavTab>('overview');
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [openReportModalFromOverview, setOpenReportModalFromOverview] = useState(false);
  const [showProfileModal, setShowProfileModal] = useState(false);

  // Operational collections
  const [staffDirectory, setStaffDirectory] = useState<StaffDirectoryEntry[]>([]);
  const [shifts, setShifts] = useState<Shift[]>([]);
  const [scheduleChanges, setScheduleChanges] = useState<ScheduleChangeRequest[]>([]);
  const [shiftReports, setShiftReports] = useState<ShiftReport[]>([]);
  const [shiftSubmissions, setShiftSubmissions] = useState<ShiftSubmission[]>([]);
  const [timeOffRequests, setTimeOffRequests] = useState<TimeOffRequest[]>([]);
  const [notifications, setNotifications] = useState<PushNotificationRecord[]>([]);

  // Push & Toast state
  const [pushPermission, setPushPermission] = useState<PushSupportState>(() => getBrowserPushState());
  const [bannerMessage, setBannerMessage] = useState<string | null>(null);
  const knownNotifIdsRef = useRef<Set<string> | null>(null);

  // Profile Editor state
  const [editName, setEditName] = useState('');
  const [editDept, setEditDept] = useState('');
  const [editTarget, setEditTarget] = useState(40);
  const [editPushEnabled, setEditPushEnabled] = useState(true);
  const [savingProfile, setSavingProfile] = useState(false);

  const showBanner = useCallback((msg: string) => {
    setBannerMessage(msg);
  }, []);

  useEffect(() => {
    if (!bannerMessage) return;
    const t = setTimeout(() => setBannerMessage(null), 5000);
    return () => clearTimeout(t);
  }, [bannerMessage]);

  // Initial Firestore connectivity & SW setup
  useEffect(() => {
    testFirestoreConnection();
    registerNotificationServiceWorker();
  }, []);

  const hydrateVerifiedUser = useCallback(async (user: User) => {
    setLoadingProfile(true);
    try {
      const p = await ensureVerifiedUserProvisioned(user);
      setProfile(p);
      setEditName(p.displayName);
      setEditDept(p.department);
      setEditTarget(p.weeklyHoursTarget);
      setEditPushEnabled(p.pushNotificationsEnabled);
    } finally {
      setLoadingProfile(false);
    }
  }, []);

  useEffect(() => {
    const unsub = onAuthStateChanged(auth, async (user) => {
      setFirebaseUser(user);
      setAuthReady(true);
      if (user && user.emailVerified) {
        await hydrateVerifiedUser(user);
      } else {
        setProfile(null);
        knownNotifIdsRef.current = null;
      }
    });
    return () => unsub();
  }, [hydrateVerifiedUser]);

  const handleVerificationConfirmed = async () => {
    if (!auth.currentUser) return;
    await auth.currentUser.reload();
    await auth.currentUser.getIdToken(true);
    const refreshed = auth.currentUser;
    setFirebaseUser(refreshed);
    if (refreshed.emailVerified) {
      await hydrateVerifiedUser(refreshed);
    }
  };

  // Real-time Firestore listeners (only when verified & provisioned)
  useEffect(() => {
    if (!authReady || !firebaseUser?.emailVerified || !profile) return;

    const isManager = profile.role === 'manager';

    const unsubStaff = onSnapshot(
      query(collection(db, 'staffDirectory'), where('role', 'in', ['staff', 'manager'])),
      (snap) => {
        const items = snap.docs.map((d) => d.data() as StaffDirectoryEntry);
        items.sort((a, b) => a.displayName.localeCompare(b.displayName));
        setStaffDirectory(items);
      },
      (err) => handleFirestoreError(err, OperationType.LIST, 'staffDirectory')
    );

    const unsubShifts = onSnapshot(
      query(collection(db, 'shifts'), where('visibility', '==', 'team')),
      (snap) => {
        const items = snap.docs.map((d) => d.data() as Shift);
        items.sort((a, b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime));
        setShifts(items);
      },
      (err) => handleFirestoreError(err, OperationType.LIST, 'shifts')
    );

    const changesQuery = isManager
      ? collection(db, 'scheduleChanges')
      : query(collection(db, 'scheduleChanges'), where('requesterUid', '==', profile.uid));

    const unsubChanges = onSnapshot(
      changesQuery,
      (snap) => {
        const items = snap.docs.map((d) => d.data() as ScheduleChangeRequest);
        items.sort((a, b) => {
          if (a.status === 'pending' && b.status !== 'pending') return -1;
          if (a.status !== 'pending' && b.status === 'pending') return 1;
          return b.id.localeCompare(a.id);
        });
        setScheduleChanges(items);
      },
      (err) => handleFirestoreError(err, OperationType.LIST, 'scheduleChanges')
    );

    const reportsQuery = isManager
      ? collection(db, 'shiftReports')
      : query(collection(db, 'shiftReports'), where('staffUid', '==', profile.uid));

    const unsubReports = onSnapshot(
      reportsQuery,
      (snap) => {
        const items = snap.docs.map((d) => d.data() as ShiftReport);
        items.sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id));
        setShiftReports(items);
      },
      (err) => handleFirestoreError(err, OperationType.LIST, 'shiftReports')
    );

    const notifQuery = query(
      collection(db, 'notifications'),
      where('recipientUid', '==', profile.uid)
    );

    const unsubNotifs = onSnapshot(
      notifQuery,
      (snap) => {
        const items = snap.docs.map((d) => d.data() as PushNotificationRecord);
        items.sort((a, b) => b.id.localeCompare(a.id));

        if (knownNotifIdsRef.current === null) {
          knownNotifIdsRef.current = new Set(items.map((n) => n.id));
        } else {
          for (const n of items) {
            if (!knownNotifIdsRef.current.has(n.id)) {
              knownNotifIdsRef.current.add(n.id);
              if (!n.isRead && profile.pushNotificationsEnabled) {
                dispatchBrowserPushNotification(n.title, n.message, n.id);
                setBannerMessage(`${n.title}: ${n.message}`);
              }
            }
          }
        }
        setNotifications(items);
      },
      (err) => handleFirestoreError(err, OperationType.LIST, 'notifications')
    );

    const submissionsQuery = isManager
      ? collection(db, 'shiftSubmissions')
      : query(collection(db, 'shiftSubmissions'), where('staffUid', '==', profile.uid));

    const unsubSubmissions = onSnapshot(
      submissionsQuery,
      (snap) => {
        const items = snap.docs.map((d) => d.data() as ShiftSubmission);
        items.sort((a, b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime));
        setShiftSubmissions(items);
      },
      (err) => handleFirestoreError(err, OperationType.LIST, 'shiftSubmissions')
    );

    const timeOffQuery = isManager
      ? collection(db, 'timeOffRequests')
      : query(collection(db, 'timeOffRequests'), where('staffUid', '==', profile.uid));

    const unsubTimeOff = onSnapshot(
      timeOffQuery,
      (snap) => {
        const items = snap.docs.map((d) => d.data() as TimeOffRequest);
        items.sort((a, b) => {
          if (a.status === 'pending' && b.status !== 'pending') return -1;
          if (a.status !== 'pending' && b.status === 'pending') return 1;
          return a.startDate.localeCompare(b.startDate);
        });
        setTimeOffRequests(items);
      },
      (err) => handleFirestoreError(err, OperationType.LIST, 'timeOffRequests')
    );

    return () => {
      unsubStaff();
      unsubShifts();
      unsubChanges();
      unsubReports();
      unsubNotifs();
      unsubSubmissions();
      unsubTimeOff();
    };
  }, [authReady, firebaseUser?.emailVerified, profile]);

  const handleRequestPush = async () => {
    const status = await requestBrowserPushPermission();
    setPushPermission(status);
    if (status === 'granted') {
      showBanner('Browser push notifications enabled.');
    } else if (status === 'denied') {
      showBanner('Browser notifications are blocked; in-app alerts remain active.');
    }
  };

  const handleSaveProfile = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!profile) return;
    setSavingProfile(true);
    try {
      await updateUserPreferences(profile.uid, {
        displayName: editName,
        department: editDept,
        weeklyHoursTarget: editTarget,
        pushNotificationsEnabled: editPushEnabled,
      });
      setProfile((prev) =>
        prev
          ? {
              ...prev,
              displayName: editName.trim() || prev.displayName,
              department: editDept.trim() || prev.department,
              weeklyHoursTarget: editTarget,
              pushNotificationsEnabled: editPushEnabled,
            }
          : prev
      );
      setShowProfileModal(false);
      showBanner('Profile preferences saved.');
    } finally {
      setSavingProfile(false);
    }
  };

  const pendingApprovalsCount = useMemo(
    () => scheduleChanges.filter((c) => c.status === 'pending').length,
    [scheduleChanges]
  );

  const pendingTimeOffCount = useMemo(
    () => timeOffRequests.filter((t) => t.status === 'pending').length,
    [timeOffRequests]
  );

  const unreadNotifCount = useMemo(
    () => notifications.filter((n) => n.recipientUid === profile?.uid && !n.isRead).length,
    [notifications, profile?.uid]
  );

  if (!authReady || loadingProfile) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center p-6">
        <div className="bg-white border border-slate-200 rounded-xl px-6 py-4 text-sm text-slate-600">
          Loading workspace...
        </div>
      </div>
    );
  }

  if (!firebaseUser || !firebaseUser.emailVerified || !profile) {
    return (
      <AuthGate
        currentUser={firebaseUser}
        onVerificationConfirmed={handleVerificationConfirmed}
      />
    );
  }

  const navItems: { key: NavTab; label: string; icon: React.ReactNode; badge?: number }[] = [
    { key: 'overview', label: 'Overview', icon: <LayoutDashboard className="w-4 h-4" /> },
    { key: 'schedule', label: 'Schedule & Fairness', icon: <CalendarClock className="w-4 h-4" /> },
    { key: 'reports', label: 'Shift Reports', icon: <ClipboardList className="w-4 h-4" /> },
    {
      key: 'approvals',
      label: 'Approvals',
      icon: <CheckCheck className="w-4 h-4" />,
      badge: pendingApprovalsCount > 0 ? pendingApprovalsCount : undefined,
    },
    {
      key: 'timeoff',
      label: 'Time Off Requests',
      icon: <CalendarOff className="w-4 h-4" />,
      badge: pendingTimeOffCount > 0 ? pendingTimeOffCount : undefined,
    },
    {
      key: 'notifications',
      label: 'Notifications',
      icon: <Bell className="w-4 h-4" />,
      badge: unreadNotifCount > 0 ? unreadNotifCount : undefined,
    },
    { key: 'team', label: 'Team & Hours', icon: <Users className="w-4 h-4" /> },
  ];

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col lg:flex-row">
      {/* Sidebar Navigation (256px on desktop) */}
      <aside
        className={`fixed inset-y-0 left-0 z-40 w-64 bg-white border-r border-slate-200 flex flex-col justify-between transition-transform duration-150 lg:static lg:translate-x-0 ${
          mobileMenuOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="p-5 space-y-6">
          <div className="flex items-center justify-between">
            <button
              type="button"
              onClick={() => {
                setActiveTab('overview');
                setMobileMenuOpen(false);
              }}
              className="text-lg font-bold tracking-tight text-slate-900 whitespace-nowrap cursor-pointer"
            >
              Cadence
            </button>
            <button
              type="button"
              onClick={() => setMobileMenuOpen(false)}
              className="lg:hidden p-1.5 text-slate-500 hover:text-slate-900"
              aria-label="Close navigation"
            >
              <X className="w-4 h-4" />
            </button>
          </div>

          <nav className="space-y-1">
            {navItems.map((item) => {
              const active = activeTab === item.key;
              return (
                <button
                  key={item.key}
                  type="button"
                  onClick={() => {
                    setActiveTab(item.key);
                    setMobileMenuOpen(false);
                  }}
                  className={`w-full flex items-center justify-between px-3 py-2 text-sm font-medium rounded-lg transition-colors whitespace-nowrap cursor-pointer ${
                    active
                      ? 'bg-slate-900 text-white'
                      : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
                  }`}
                >
                  <span className="flex items-center gap-2.5 truncate">
                    {item.icon}
                    <span className="truncate">{item.label}</span>
                  </span>
                  {item.badge !== undefined && (
                    <span
                      className={`font-mono tabular-nums text-xs px-1.5 py-0.5 rounded ${
                        active ? 'bg-slate-700 text-white' : 'bg-slate-200 text-slate-800'
                      }`}
                    >
                      {item.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </nav>
        </div>

        {/* Account Footer */}
        <div className="p-4 border-t border-slate-200 space-y-3">
          <div className="flex items-center justify-between gap-2">
            <div className="min-w-0">
              <div className="text-sm font-medium text-slate-900 truncate">
                {profile.displayName}
              </div>
              <div className="text-xs text-slate-500 truncate">
                {profile.role === 'manager' ? 'Manager' : 'Staff'} · Verified
              </div>
            </div>
            <button
              type="button"
              onClick={() => setShowProfileModal(true)}
              className="p-1.5 text-slate-500 hover:text-slate-900 rounded-md hover:bg-slate-100 cursor-pointer"
              aria-label="Profile settings"
            >
              <Settings className="w-4 h-4" />
            </button>
          </div>

          <button
            type="button"
            onClick={() => signOut(auth)}
            className="w-full inline-flex items-center justify-center gap-2 px-3 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors cursor-pointer"
          >
            <LogOut className="w-3.5 h-3.5" />
            Sign Out
          </button>
        </div>
      </aside>

      {/* Mobile backdrop */}
      {mobileMenuOpen && (
        <div
          className="fixed inset-0 z-30 bg-slate-900/30 lg:hidden"
          onClick={() => setMobileMenuOpen(false)}
        />
      )}

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Top Bar Contract */}
        <header className="bg-white border-b border-slate-200 px-6 py-3.5 flex items-center justify-between gap-8">
          <div className="flex items-center gap-3 min-w-0">
            <button
              type="button"
              onClick={() => setMobileMenuOpen(true)}
              className="lg:hidden p-1.5 text-slate-600 hover:text-slate-900"
              aria-label="Open navigation"
            >
              <Menu className="w-5 h-5" />
            </button>
            <div className="text-sm font-medium text-slate-500 truncate">
              Cadence <span aria-hidden="true">/</span>{' '}
              <span className="text-slate-900">{TAB_LABELS[activeTab]}</span>
            </div>
          </div>

          <div className="flex items-center gap-3 shrink-0">
            <button
              type="button"
              onClick={() => setActiveTab('notifications')}
              className="inline-flex items-center gap-2 px-3 py-1.5 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 whitespace-nowrap shrink-0 cursor-pointer"
            >
              <Bell className="w-3.5 h-3.5" />
              <span>Alerts</span>
              {unreadNotifCount > 0 && (
                <span className="font-mono tabular-nums text-blue-700 font-semibold">
                  {unreadNotifCount}
                </span>
              )}
            </button>
          </div>
        </header>

        {/* Live Feedback Banner */}
        {bannerMessage && (
          <div className="bg-slate-900 text-white px-6 py-2.5 text-xs flex items-center justify-between gap-4">
            <span>{bannerMessage}</span>
            <button
              type="button"
              onClick={() => setBannerMessage(null)}
              className="text-slate-300 hover:text-white cursor-pointer"
              aria-label="Dismiss message"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* Viewport Container */}
        <main className="flex-1 p-6 lg:p-8 max-w-7xl w-full mx-auto">
          {activeTab === 'overview' && (
            <OverviewView
              profile={profile}
              shifts={shifts}
              scheduleChanges={scheduleChanges}
              shiftReports={shiftReports}
              notifications={notifications}
              staffDirectory={staffDirectory}
              onNavigate={setActiveTab}
              onOpenNewReport={() => {
                setOpenReportModalFromOverview(true);
                setActiveTab('reports');
              }}
            />
          )}

          {activeTab === 'schedule' && (
            <ScheduleFairnessView
              profile={profile}
              shifts={shifts}
              staffDirectory={staffDirectory}
              shiftSubmissions={shiftSubmissions}
              timeOffRequests={timeOffRequests}
              onNotifyBanner={showBanner}
            />
          )}

          {activeTab === 'reports' && (
            <ShiftReportsView
              profile={profile}
              shifts={shifts}
              shiftReports={shiftReports}
              staffDirectory={staffDirectory}
              showModalInitially={openReportModalFromOverview}
              onCloseInitialModal={() => setOpenReportModalFromOverview(false)}
              onNotifyBanner={showBanner}
            />
          )}

          {activeTab === 'approvals' && (
            <ManagerApprovalsView
              profile={profile}
              scheduleChanges={scheduleChanges}
              shifts={shifts}
              staffDirectory={staffDirectory}
              onNotifyBanner={showBanner}
            />
          )}

          {activeTab === 'timeoff' && (
            <TimeOffRequestsView
              profile={profile}
              timeOffRequests={timeOffRequests}
              onNotifyBanner={showBanner}
            />
          )}

          {activeTab === 'notifications' && (
            <NotificationsView
              profile={profile}
              notifications={notifications}
              pushPermission={pushPermission}
              onRequestPushPermission={handleRequestPush}
              onNotifyBanner={showBanner}
            />
          )}

          {activeTab === 'team' && (
            <TeamHoursView
              profile={profile}
              staffDirectory={staffDirectory}
              shifts={shifts}
              onNotifyBanner={showBanner}
            />
          )}
        </main>
      </div>

      {/* Profile Preferences Modal */}
      {showProfileModal && (
        <div className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4">
          <div className="bg-white border border-slate-200 rounded-xl max-w-md w-full p-6 space-y-5">
            <div className="flex items-center justify-between">
              <h2 className="text-base font-semibold text-slate-900">Account Preferences</h2>
              <button
                type="button"
                onClick={() => setShowProfileModal(false)}
                className="p-1 text-slate-400 hover:text-slate-700 cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSaveProfile} className="space-y-4">
              <div className="space-y-1">
                <label className="block text-xs font-medium text-slate-700">Display Name</label>
                <input
                  type="text"
                  required
                  maxLength={80}
                  value={editName}
                  onChange={(e) => setEditName(e.target.value)}
                  className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
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
                <div className="space-y-1">
                  <label className="block text-xs font-medium text-slate-700">
                    Weekly Target (h)
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={80}
                    value={editTarget}
                    onChange={(e) => setEditTarget(Number(e.target.value) || 40)}
                    className="w-full px-3 py-2 text-sm font-mono border border-slate-200 rounded-lg"
                  />
                </div>
              </div>

              <label className="flex items-center gap-2.5 text-xs text-slate-700 cursor-pointer">
                <input
                  type="checkbox"
                  checked={editPushEnabled}
                  onChange={(e) => setEditPushEnabled(e.target.checked)}
                  className="rounded border-slate-300 text-blue-700"
                />
                <span>Receive real-time shift status push notifications</span>
              </label>

              <div className="pt-2 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setShowProfileModal(false)}
                  className="px-4 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={savingProfile}
                  className="px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 disabled:opacity-50 cursor-pointer"
                >
                  {savingProfile ? 'Saving...' : 'Save Changes'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

export default function App() {
  return (
    <ErrorBoundary>
      <WorkspaceApp />
    </ErrorBoundary>
  );
}
