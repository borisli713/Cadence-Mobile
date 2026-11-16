import React, { useState } from 'react';
import { Bell, BellRing, Check, CheckCheck, Send } from 'lucide-react';
import { PushNotificationRecord, UserProfile } from '../types';
import {
  markAllNotificationsRead,
  markNotificationReadState,
  sendTestPushNotificationRecord,
} from '../services/firestoreService';
import {
  dispatchBrowserPushNotification,
  PushSupportState,
} from '../utils/notifications';

interface NotificationsViewProps {
  profile: UserProfile;
  notifications: PushNotificationRecord[];
  pushPermission: PushSupportState;
  onRequestPushPermission: () => Promise<void>;
  onNotifyBanner: (msg: string) => void;
}

export const NotificationsView: React.FC<NotificationsViewProps> = ({
  profile,
  notifications,
  pushPermission,
  onRequestPushPermission,
  onNotifyBanner,
}) => {
  const [busy, setBusy] = useState(false);
  const myNotifications = notifications.filter((n) => n.recipientUid === profile.uid);
  const unreadIds = myNotifications.filter((n) => !n.isRead).map((n) => n.id);

  const handleMarkAllRead = async () => {
    if (unreadIds.length === 0) return;
    setBusy(true);
    try {
      await markAllNotificationsRead(unreadIds);
      onNotifyBanner('All shift notifications marked as read.');
    } finally {
      setBusy(false);
    }
  };

  const handleSendTest = async () => {
    setBusy(true);
    try {
      await sendTestPushNotificationRecord(profile);
      await dispatchBrowserPushNotification(
        'Shift Status Alert Test',
        `Live push notifications are active for ${profile.displayName}.`,
        `test-${Date.now()}`
      );
      onNotifyBanner('Test shift notification dispatched.');
    } finally {
      setBusy(false);
    }
  };

  const formatCategory = (cat: PushNotificationRecord['category']) => {
    if (cat === 'shift_approved') return 'Approved';
    if (cat === 'shift_denied') return 'Denied';
    if (cat === 'fairness_rebalanced') return 'Fairness Arranged';
    if (cat === 'report_verified') return 'Report Reviewed';
    if (cat === 'time_off_approved') return 'Time Off Approved';
    if (cat === 'time_off_denied') return 'Time Off Denied';
    return 'Shift Assigned';
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900 tracking-tight">
            Push Notifications
          </h1>
          <p className="text-sm text-slate-500 mt-1">
            Real-time browser push alerts and inbox history for shift approvals, denials, and fairness reassignments.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            disabled={busy}
            onClick={handleSendTest}
            className="inline-flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-slate-700 bg-white border border-slate-200 rounded-lg hover:bg-slate-50 cursor-pointer"
          >
            <Send className="w-3.5 h-3.5" />
            Test Alert
          </button>
          <button
            type="button"
            disabled={busy || unreadIds.length === 0}
            onClick={handleMarkAllRead}
            className="inline-flex items-center gap-2 px-3.5 py-2 text-xs font-medium text-white bg-slate-900 rounded-lg hover:bg-slate-800 disabled:opacity-40 cursor-pointer"
          >
            <CheckCheck className="w-3.5 h-3.5" />
            Mark All Read ({unreadIds.length})
          </button>
        </div>
      </div>

      {/* Browser Push Permission Banner */}
      <div className="bg-white border border-slate-200 rounded-xl p-5 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div className="flex items-start gap-3">
          <div className="p-2 rounded-lg bg-slate-100 text-slate-700 shrink-0">
            <BellRing className="w-5 h-5" />
          </div>
          <div className="space-y-1">
            <div className="text-sm font-semibold text-slate-900">
              Browser Desktop &amp; Mobile Push Alerts:{' '}
              <span
                className={
                  pushPermission === 'granted'
                    ? 'text-emerald-700'
                    : pushPermission === 'denied'
                    ? 'text-red-700'
                    : 'text-amber-700'
                }
              >
                {pushPermission === 'granted'
                  ? 'Enabled'
                  : pushPermission === 'denied'
                  ? 'Blocked in Browser Settings'
                  : pushPermission === 'unsupported'
                  ? 'In-App Alerts Active'
                  : 'Permission Needed'}
              </span>
            </div>
            <p className="text-xs text-slate-500">
              Receive instant OS-level notifications whenever a manager approves or denies a schedule change or rebalances an overlap.
            </p>
          </div>
        </div>

        {pushPermission !== 'granted' && pushPermission !== 'unsupported' && (
          <button
            type="button"
            onClick={onRequestPushPermission}
            className="inline-flex items-center gap-2 px-4 py-2 text-xs font-medium text-white bg-blue-700 rounded-lg hover:bg-blue-800 whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Bell className="w-3.5 h-3.5" />
            Enable Push Alerts
          </button>
        )}
      </div>

      {/* Notifications List */}
      <div className="bg-white border border-slate-200 rounded-xl divide-y divide-slate-100 overflow-hidden">
        {myNotifications.length === 0 ? (
          <div className="p-10 text-center text-sm text-slate-500">
            No shift status notifications yet.
          </div>
        ) : (
          myNotifications.map((item) => (
            <div
              key={item.id}
              className={`px-5 py-4 flex items-start justify-between gap-4 transition-colors ${
                item.isRead ? 'bg-white' : 'bg-blue-50/40'
              }`}
            >
              <div className="space-y-1 min-w-0">
                <div className="flex items-center gap-2 text-xs">
                  <span className="font-semibold text-sm text-slate-900">{item.title}</span>
                  <span aria-hidden="true" className="text-slate-300">·</span>
                  <span
                    className={
                      item.category === 'shift_denied' || item.category === 'time_off_denied'
                        ? 'text-red-700 font-medium'
                        : item.category === 'shift_approved' ||
                          item.category === 'report_verified' ||
                          item.category === 'time_off_approved'
                        ? 'text-emerald-700 font-medium'
                        : 'text-blue-700 font-medium'
                    }
                  >
                    {formatCategory(item.category)}
                  </span>
                  {!item.isRead && (
                    <>
                      <span aria-hidden="true" className="text-slate-300">·</span>
                      <span className="text-blue-700 font-semibold">Unread</span>
                    </>
                  )}
                </div>
                <p className="text-sm text-slate-700">{item.message}</p>
              </div>

              <button
                type="button"
                onClick={() => markNotificationReadState(item.id, !item.isRead)}
                className="shrink-0 inline-flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium text-slate-600 bg-white border border-slate-200 rounded-md hover:bg-slate-50 cursor-pointer"
              >
                <Check className="w-3.5 h-3.5" />
                {item.isRead ? 'Mark Unread' : 'Mark Read'}
              </button>
            </div>
          ))
        )}
      </div>
    </div>
  );
};
