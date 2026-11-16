export type UserRole = 'staff' | 'manager';

export interface UserProfile {
  uid: string;
  email: string;
  displayName: string;
  role: UserRole;
  department: string;
  weeklyHoursTarget: number;
  pushNotificationsEnabled: boolean;
  createdAt: unknown;
  updatedAt: unknown;
}

export interface StaffDirectoryEntry {
  uid: string;
  displayName: string;
  role: UserRole;
  department: string;
  totalHoursWorked: number;
  scheduledHoursThisWeek: number;
  weeklyHoursTarget: number;
  lastAssignedAt: string;
  isSampleRoster: boolean;
  updatedAt: unknown;
}

export type ShiftStatus = 'scheduled' | 'open' | 'completed' | 'conflict' | 'pending_change';

export type ShiftSubmissionStatus = 'submitted' | 'withdrawn';

export interface ShiftSubmission {
  id: string;
  staffUid: string;
  staffName: string;
  department: string;
  date: string; // YYYY-MM-DD
  startTime: string; // HH:mm
  endTime: string; // HH:mm
  shiftTitle: string;
  notes: string;
  status: ShiftSubmissionStatus;
  createdAt: unknown;
  updatedAt: unknown;
}

export interface Shift {
  id: string;
  title: string;
  department: string;
  date: string; // YYYY-MM-DD
  startTime: string; // HH:mm
  endTime: string; // HH:mm
  durationHours: number;
  assignedUid: string;
  assignedName: string;
  candidateUids: string[];
  status: ShiftStatus;
  visibility: 'team';
  fairnessNote: string;
  createdBy: string;
  createdAt: unknown;
  updatedAt: unknown;
}

export type ScheduleChangeType = 'time_change' | 'swap' | 'drop' | 'claim';
export type ScheduleChangeStatus = 'pending' | 'approved' | 'denied';

export interface ScheduleChangeRequest {
  id: string;
  shiftId: string;
  shiftTitle: string;
  requesterUid: string;
  requesterName: string;
  type: ScheduleChangeType;
  currentDate: string;
  currentStartTime: string;
  currentEndTime: string;
  proposedDate: string;
  proposedStartTime: string;
  proposedEndTime: string;
  proposedAssigneeUid: string;
  proposedAssigneeName: string;
  reason: string;
  status: ScheduleChangeStatus;
  reviewedByUid: string;
  reviewedByName: string;
  reviewNote: string;
  createdAt: unknown;
  updatedAt: unknown;
}

export type ShiftReportStatus = 'submitted' | 'verified' | 'flagged';

export interface ShiftReport {
  id: string;
  shiftId: string;
  shiftTitle: string;
  staffUid: string;
  staffName: string;
  department: string;
  date: string;
  actualStartTime: string;
  actualEndTime: string;
  breakMinutes: number;
  workedHours: number;
  handoffNotes: string;
  status: ShiftReportStatus;
  verifiedByUid: string;
  createdAt: unknown;
  updatedAt: unknown;
}

export type NotificationCategory =
  | 'shift_approved'
  | 'shift_denied'
  | 'fairness_rebalanced'
  | 'report_verified'
  | 'shift_assigned'
  | 'time_off_approved'
  | 'time_off_denied';

export type TimeOffStatus = 'pending' | 'approved' | 'denied';

export interface TimeOffRequest {
  id: string;
  staffUid: string;
  staffName: string;
  department: string;
  startDate: string; // YYYY-MM-DD
  endDate: string; // YYYY-MM-DD
  reason: string;
  status: TimeOffStatus;
  reviewedByUid: string;
  reviewedByName: string;
  reviewNote: string;
  createdAt: unknown;
  updatedAt: unknown;
}

export interface PushNotificationRecord {
  id: string;
  recipientUid: string;
  title: string;
  message: string;
  category: NotificationCategory;
  relatedShiftId: string;
  isRead: boolean;
  actorUid: string;
  createdAt: unknown;
  updatedAt: unknown;
}

export interface StaffFairnessMetric {
  uid: string;
  displayName: string;
  department: string;
  workedHours: number;
  scheduledHours: number;
  effectiveTotalHours: number;
  weeklyHoursTarget: number;
  utilizationRatio: number;
  lastAssignedAt: string;
}

export interface FairnessResolutionItem {
  shiftId: string;
  shiftTitle: string;
  date: string;
  timeWindow: string;
  department: string;
  overlapType: 'multi_candidate' | 'concurrent_overlap';
  previousAssigneeUid: string;
  previousAssigneeName: string;
  selectedAssigneeUid: string;
  selectedAssigneeName: string;
  competingNames: string[];
  reason: string;
  action: 'assign_fairest' | 'rebalance_secondary';
  newDate?: string;
  newStartTime?: string;
  newEndTime?: string;
}
