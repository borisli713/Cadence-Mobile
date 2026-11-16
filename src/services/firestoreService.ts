import { User } from 'firebase/auth';
import {
  deleteDoc,
  doc,
  getDoc,
  serverTimestamp,
  setDoc,
  updateDoc,
  writeBatch,
} from 'firebase/firestore';
import {
  BOOTSTRAPPED_ADMIN_EMAIL,
  db,
  handleFirestoreError,
  OperationType,
} from '../firebase';
import {
  FairnessResolutionItem,
  NotificationCategory,
  ScheduleChangeRequest,
  ScheduleChangeType,
  Shift,
  ShiftReport,
  ShiftStatus,
  StaffDirectoryEntry,
  TimeOffRequest,
  UserProfile,
  UserRole,
} from '../types';
import {
  computeDurationHours,
  GeneratedWeeklyShiftDraft,
} from '../utils/fairness';

export function makeSafeId(prefix: string): string {
  const rand = Math.random().toString(36).replace(/[^a-z0-9]/g, '').slice(0, 8);
  return `${prefix}_${Date.now()}_${rand || '001'}`;
}

export async function ensureVerifiedUserProvisioned(
  firebaseUser: User
): Promise<UserProfile> {
  const uid = firebaseUser.uid;
  const userRef = doc(db, 'users', uid);
  const staffRef = doc(db, 'staffDirectory', uid);
  const isBootstrappedManager =
    (firebaseUser.email || '').toLowerCase() === BOOTSTRAPPED_ADMIN_EMAIL;

  let existingUser: UserProfile | null = null;
  try {
    const snap = await getDoc(userRef);
    if (snap.exists()) {
      existingUser = snap.data() as UserProfile;
    }
  } catch (err) {
    handleFirestoreError(err, OperationType.GET, `users/${uid}`);
  }

  let hasManagerDoc = false;
  if (!isBootstrappedManager) {
    try {
      const mSnap = await getDoc(doc(db, 'managers', uid));
      hasManagerDoc = mSnap.exists();
    } catch {
      hasManagerDoc = false;
    }
  }

  const effectiveRole: UserRole =
    isBootstrappedManager || hasManagerDoc
      ? 'manager'
      : existingUser?.role || 'staff';

  const fallbackName =
    firebaseUser.displayName?.trim() ||
    firebaseUser.email?.split('@')[0]?.replace(/[._-]/g, ' ') ||
    'Team Member';

  if (!existingUser) {
    const profilePayload = {
      uid,
      email: (firebaseUser.email || '').slice(0, 200),
      displayName: fallbackName.slice(0, 80),
      role: effectiveRole,
      department: 'Operations',
      weeklyHoursTarget: 40,
      pushNotificationsEnabled: true,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    };
    try {
      await setDoc(userRef, profilePayload);
    } catch (err) {
      handleFirestoreError(err, OperationType.CREATE, `users/${uid}`);
    }

    try {
      const staffSnap = await getDoc(staffRef);
      if (!staffSnap.exists()) {
        await setDoc(staffRef, {
          uid,
          displayName: fallbackName.slice(0, 80),
          role: effectiveRole,
          department: 'Operations',
          totalHoursWorked: 0,
          scheduledHoursThisWeek: 0,
          weeklyHoursTarget: 40,
          lastAssignedAt: '',
          isSampleRoster: false,
          updatedAt: serverTimestamp(),
        });
      }
    } catch (err) {
      handleFirestoreError(err, OperationType.CREATE, `staffDirectory/${uid}`);
    }

    return {
      ...profilePayload,
      createdAt: new Date(),
      updatedAt: new Date(),
    };
  }

  // Ensure staffDirectory exists even if user doc was already created
  try {
    const staffSnap = await getDoc(staffRef);
    if (!staffSnap.exists()) {
      await setDoc(staffRef, {
        uid,
        displayName: existingUser.displayName.slice(0, 80),
        role: effectiveRole,
        department: existingUser.department.slice(0, 60),
        totalHoursWorked: 0,
        scheduledHoursThisWeek: 0,
        weeklyHoursTarget: existingUser.weeklyHoursTarget || 40,
        lastAssignedAt: '',
        isSampleRoster: false,
        updatedAt: serverTimestamp(),
      });
    }
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, `staffDirectory/${uid}`);
  }

  return {
    ...existingUser,
    role: effectiveRole,
  };
}

export async function updateUserPreferences(
  uid: string,
  updates: {
    displayName: string;
    department: string;
    weeklyHoursTarget: number;
    pushNotificationsEnabled: boolean;
  }
): Promise<void> {
  const cleanName = updates.displayName.trim().slice(0, 80) || 'Team Member';
  const cleanDept = updates.department.trim().slice(0, 60) || 'Operations';
  const cleanTarget = Math.min(80, Math.max(1, Number(updates.weeklyHoursTarget) || 40));

  try {
    await updateDoc(doc(db, 'users', uid), {
      displayName: cleanName,
      department: cleanDept,
      weeklyHoursTarget: cleanTarget,
      pushNotificationsEnabled: updates.pushNotificationsEnabled,
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `users/${uid}`);
  }

  try {
    await updateDoc(doc(db, 'staffDirectory', uid), {
      displayName: cleanName,
      department: cleanDept,
      weeklyHoursTarget: cleanTarget,
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `staffDirectory/${uid}`);
  }
}

export async function createShiftRecord(params: {
  title: string;
  department: string;
  date: string;
  startTime: string;
  endTime: string;
  assignedUid: string;
  assignedName: string;
  candidateUids: string[];
  status: 'scheduled' | 'conflict' | 'open';
  fairnessNote: string;
  actorUid: string;
}): Promise<string> {
  const shiftId = makeSafeId('shift');
  const durationHours = computeDurationHours(params.startTime, params.endTime, 0);
  const isOpen = params.status === 'open' || !params.assignedUid;
  const effectiveUid = isOpen ? '' : params.assignedUid;
  const effectiveName = isOpen ? '' : params.assignedName.trim().slice(0, 80);
  const effectiveStatus: ShiftStatus = isOpen ? 'open' : params.status;
  const candidates = isOpen
    ? []
    : params.candidateUids.length > 0
    ? params.candidateUids.slice(0, 20)
    : [effectiveUid];

  const batch = writeBatch(db);
  batch.set(doc(db, 'shifts', shiftId), {
    id: shiftId,
    title: params.title.trim().slice(0, 120),
    department: params.department.trim().slice(0, 60),
    date: params.date,
    startTime: params.startTime,
    endTime: params.endTime,
    durationHours,
    assignedUid: effectiveUid,
    assignedName: effectiveName,
    candidateUids: candidates,
    status: effectiveStatus,
    visibility: 'team',
    fairnessNote: params.fairnessNote.slice(0, 300),
    createdBy: params.actorUid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  if (!isOpen && effectiveUid) {
    const notifId = makeSafeId('notif');
    batch.set(doc(db, 'notifications', notifId), {
      id: notifId,
      recipientUid: effectiveUid,
      title: 'New Shift Assigned',
      message: `${params.title.trim()} on ${params.date} (${params.startTime}–${params.endTime}) has been scheduled for you.`,
      category: 'shift_assigned' as NotificationCategory,
      relatedShiftId: shiftId,
      isRead: false,
      actorUid: params.actorUid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  }

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, `shifts/${shiftId}`);
  }
  return shiftId;
}

export async function submitShiftAvailability(params: {
  staffUid: string;
  staffName: string;
  department: string;
  dates: string[];
  startTime: string;
  endTime: string;
  shiftTitle: string;
  notes: string;
}): Promise<number> {
  const cleanDates = Array.from(new Set(params.dates.filter(Boolean)));
  if (cleanDates.length === 0) return 0;

  const batch = writeBatch(db);
  for (let i = 0; i < cleanDates.length; i++) {
    const subId = makeSafeId(`sub${i}`);
    batch.set(doc(db, 'shiftSubmissions', subId), {
      id: subId,
      staffUid: params.staffUid,
      staffName: params.staffName.trim().slice(0, 80),
      department: params.department.trim().slice(0, 60) || 'Operations',
      date: cleanDates[i],
      startTime: params.startTime,
      endTime: params.endTime,
      shiftTitle: params.shiftTitle.trim().slice(0, 120),
      notes: params.notes.trim().slice(0, 300),
      status: 'submitted',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  }

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, 'shiftSubmissions/batch');
  }
  return cleanDates.length;
}

export async function withdrawShiftSubmission(submissionId: string): Promise<void> {
  try {
    await updateDoc(doc(db, 'shiftSubmissions', submissionId), {
      status: 'withdrawn',
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `shiftSubmissions/${submissionId}`);
  }
}

export async function submitTimeOffRequest(params: {
  staffUid: string;
  staffName: string;
  department: string;
  startDate: string;
  endDate: string;
  reason: string;
}): Promise<string> {
  const reqId = makeSafeId('pto');
  const start = params.startDate;
  const end = params.endDate >= params.startDate ? params.endDate : params.startDate;

  try {
    await setDoc(doc(db, 'timeOffRequests', reqId), {
      id: reqId,
      staffUid: params.staffUid,
      staffName: params.staffName.trim().slice(0, 80) || 'Team Member',
      department: params.department.trim().slice(0, 60) || 'Operations',
      startDate: start,
      endDate: end,
      reason: params.reason.trim().slice(0, 400),
      status: 'pending',
      reviewedByUid: '',
      reviewedByName: '',
      reviewNote: '',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, `timeOffRequests/${reqId}`);
  }
  return reqId;
}

export async function reviewTimeOffRequest(
  request: TimeOffRequest,
  decision: 'approved' | 'denied',
  manager: UserProfile,
  reviewNote: string
): Promise<void> {
  const cleanNote = reviewNote.trim().slice(0, 300);
  const batch = writeBatch(db);

  batch.update(doc(db, 'timeOffRequests', request.id), {
    status: decision,
    reviewedByUid: manager.uid,
    reviewedByName: manager.displayName.slice(0, 80),
    reviewNote: cleanNote,
    updatedAt: serverTimestamp(),
  });

  const dateRange =
    request.startDate === request.endDate
      ? request.startDate
      : `${request.startDate} to ${request.endDate}`;
  const notifId = makeSafeId('notif');
  batch.set(doc(db, 'notifications', notifId), {
    id: notifId,
    recipientUid: request.staffUid,
    title: decision === 'approved' ? 'Time Off Approved' : 'Time Off Denied',
    message:
      decision === 'approved'
        ? `Your time off request for ${dateRange} was approved. You will not be auto-scheduled on these dates.`
        : `Your time off request for ${dateRange} was denied.${cleanNote ? ` Note: ${cleanNote}` : ''}`,
    category: decision === 'approved' ? 'time_off_approved' : 'time_off_denied',
    relatedShiftId: '',
    isRead: false,
    actorUid: manager.uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `timeOffRequests/${request.id}`);
  }
}

export async function deleteTimeOffRequest(requestId: string): Promise<void> {
  try {
    await deleteDoc(doc(db, 'timeOffRequests', requestId));
  } catch (err) {
    handleFirestoreError(err, OperationType.DELETE, `timeOffRequests/${requestId}`);
  }
}

export async function submitShiftReportRecord(params: {
  shiftId: string;
  shiftTitle: string;
  staffUid: string;
  staffName: string;
  department: string;
  date: string;
  actualStartTime: string;
  actualEndTime: string;
  breakMinutes: number;
  handoffNotes: string;
}): Promise<string> {
  const reportId = makeSafeId('rep');
  const workedHours = computeDurationHours(
    params.actualStartTime,
    params.actualEndTime,
    params.breakMinutes
  );

  try {
    await setDoc(doc(db, 'shiftReports', reportId), {
      id: reportId,
      shiftId: params.shiftId || '',
      shiftTitle: params.shiftTitle.trim().slice(0, 120),
      staffUid: params.staffUid,
      staffName: params.staffName.trim().slice(0, 80),
      department: params.department.trim().slice(0, 60),
      date: params.date,
      actualStartTime: params.actualStartTime,
      actualEndTime: params.actualEndTime,
      breakMinutes: Math.min(180, Math.max(0, Math.round(params.breakMinutes))),
      workedHours,
      handoffNotes: params.handoffNotes.trim().slice(0, 600),
      status: 'submitted',
      verifiedByUid: '',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, `shiftReports/${reportId}`);
  }

  return reportId;
}

export async function verifyOrFlagShiftReport(
  report: ShiftReport,
  decision: 'verified' | 'flagged',
  manager: UserProfile,
  staffDirectory: StaffDirectoryEntry[]
): Promise<void> {
  const batch = writeBatch(db);
  batch.update(doc(db, 'shiftReports', report.id), {
    status: decision,
    verifiedByUid: manager.uid,
    updatedAt: serverTimestamp(),
  });

  if (decision === 'verified') {
    const staffEntry = staffDirectory.find((s) => s.uid === report.staffUid);
    if (staffEntry) {
      batch.update(doc(db, 'staffDirectory', report.staffUid), {
        totalHoursWorked:
          Math.round((staffEntry.totalHoursWorked + report.workedHours) * 100) / 100,
        updatedAt: serverTimestamp(),
      });
    }
    if (report.shiftId) {
      batch.update(doc(db, 'shifts', report.shiftId), {
        status: 'completed',
        updatedAt: serverTimestamp(),
      });
    }
  }

  const notifId = makeSafeId('notif');
  batch.set(doc(db, 'notifications', notifId), {
    id: notifId,
    recipientUid: report.staffUid,
    title: decision === 'verified' ? 'Shift Report Verified' : 'Shift Report Flagged',
    message:
      decision === 'verified'
        ? `Your ${report.workedHours.toFixed(2)}h report for "${report.shiftTitle}" (${report.date}) was verified and credited.`
        : `Your shift report for "${report.shiftTitle}" (${report.date}) was flagged for manager follow-up.`,
    category: 'report_verified',
    relatedShiftId: report.shiftId || '',
    isRead: false,
    actorUid: manager.uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `shiftReports/${report.id}`);
  }
}

export async function createScheduleChangeRequest(params: {
  shift: Shift;
  requesterUid: string;
  requesterName: string;
  type: ScheduleChangeType;
  proposedDate: string;
  proposedStartTime: string;
  proposedEndTime: string;
  proposedAssigneeUid: string;
  proposedAssigneeName: string;
  reason: string;
}): Promise<string> {
  const changeId = makeSafeId('chg');
  const batch = writeBatch(db);

  batch.set(doc(db, 'scheduleChanges', changeId), {
    id: changeId,
    shiftId: params.shift.id,
    shiftTitle: params.shift.title.slice(0, 120),
    requesterUid: params.requesterUid,
    requesterName: params.requesterName.slice(0, 80),
    type: params.type,
    currentDate: params.shift.date,
    currentStartTime: params.shift.startTime,
    currentEndTime: params.shift.endTime,
    proposedDate: params.proposedDate,
    proposedStartTime: params.proposedStartTime,
    proposedEndTime: params.proposedEndTime,
    proposedAssigneeUid: params.proposedAssigneeUid,
    proposedAssigneeName: params.proposedAssigneeName.slice(0, 80),
    reason: params.reason.trim().slice(0, 400),
    status: 'pending',
    reviewedByUid: '',
    reviewedByName: '',
    reviewNote: '',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  if (params.shift.assignedUid === params.requesterUid) {
    batch.update(doc(db, 'shifts', params.shift.id), {
      status: 'pending_change',
      updatedAt: serverTimestamp(),
    });
  }

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, `scheduleChanges/${changeId}`);
  }
  return changeId;
}

export async function reviewSingleScheduleChange(
  change: ScheduleChangeRequest,
  decision: 'approved' | 'denied',
  manager: UserProfile,
  reviewNote: string,
  allShifts: Shift[]
): Promise<void> {
  const batch = writeBatch(db);
  const cleanNote = reviewNote.trim().slice(0, 300);

  batch.update(doc(db, 'scheduleChanges', change.id), {
    status: decision,
    reviewedByUid: manager.uid,
    reviewedByName: manager.displayName.slice(0, 80),
    reviewNote: cleanNote,
    updatedAt: serverTimestamp(),
  });

  const targetShift = allShifts.find((s) => s.id === change.shiftId);
  if (targetShift) {
    if (decision === 'approved') {
      const nextAssignedUid = change.proposedAssigneeUid || targetShift.assignedUid;
      const nextAssignedName = change.proposedAssigneeName || targetShift.assignedName;
      const nextDuration = computeDurationHours(
        change.proposedStartTime,
        change.proposedEndTime,
        0
      );
      batch.update(doc(db, 'shifts', targetShift.id), {
        date: change.proposedDate,
        startTime: change.proposedStartTime,
        endTime: change.proposedEndTime,
        durationHours: nextDuration,
        assignedUid: nextAssignedUid,
        assignedName: nextAssignedName,
        candidateUids: [nextAssignedUid],
        status: 'scheduled',
        fairnessNote: `Approved ${change.type.replace('_', ' ')} by ${manager.displayName}.`.slice(0, 300),
        updatedAt: serverTimestamp(),
      });
    } else if (targetShift.status === 'pending_change') {
      batch.update(doc(db, 'shifts', targetShift.id), {
        status: 'scheduled',
        updatedAt: serverTimestamp(),
      });
    }
  }

  const notifId = makeSafeId('notif');
  batch.set(doc(db, 'notifications', notifId), {
    id: notifId,
    recipientUid: change.requesterUid,
    title: decision === 'approved' ? 'Schedule Change Approved' : 'Schedule Change Denied',
    message:
      decision === 'approved'
        ? `Your ${change.type.replace('_', ' ')} for "${change.shiftTitle}" (${change.proposedDate} ${change.proposedStartTime}–${change.proposedEndTime}) was approved.`
        : `Your ${change.type.replace('_', ' ')} for "${change.shiftTitle}" was denied.${cleanNote ? ` Note: ${cleanNote}` : ''}`,
    category: decision === 'approved' ? 'shift_approved' : 'shift_denied',
    relatedShiftId: change.shiftId,
    isRead: false,
    actorUid: manager.uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  if (
    decision === 'approved' &&
    change.proposedAssigneeUid &&
    change.proposedAssigneeUid !== change.requesterUid
  ) {
    const swapNotifId = makeSafeId('notif');
    batch.set(doc(db, 'notifications', swapNotifId), {
      id: swapNotifId,
      recipientUid: change.proposedAssigneeUid,
      title: 'Shift Reassigned to You',
      message: `Approved schedule change assigned "${change.shiftTitle}" on ${change.proposedDate} (${change.proposedStartTime}–${change.proposedEndTime}) to you.`,
      category: 'shift_approved',
      relatedShiftId: change.shiftId,
      isRead: false,
      actorUid: manager.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  }

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `scheduleChanges/${change.id}`);
  }
}

export async function acceptAllPendingChanges(
  pendingChanges: ScheduleChangeRequest[],
  allShifts: Shift[],
  manager: UserProfile
): Promise<number> {
  if (pendingChanges.length === 0) return 0;

  // Firestore writeBatch supports up to 500 operations; chunk safely at 120 requests per batch.
  const chunkSize = 120;
  let processed = 0;

  for (let i = 0; i < pendingChanges.length; i += chunkSize) {
    const chunk = pendingChanges.slice(i, i + chunkSize);
    const batch = writeBatch(db);

    for (const change of chunk) {
      batch.update(doc(db, 'scheduleChanges', change.id), {
        status: 'approved',
        reviewedByUid: manager.uid,
        reviewedByName: manager.displayName.slice(0, 80),
        reviewNote: 'Accepted in batch approval.',
        updatedAt: serverTimestamp(),
      });

      const targetShift = allShifts.find((s) => s.id === change.shiftId);
      if (targetShift) {
        const nextAssignedUid = change.proposedAssigneeUid || targetShift.assignedUid;
        const nextAssignedName = change.proposedAssigneeName || targetShift.assignedName;
        const nextDuration = computeDurationHours(
          change.proposedStartTime,
          change.proposedEndTime,
          0
        );
        batch.update(doc(db, 'shifts', targetShift.id), {
          date: change.proposedDate,
          startTime: change.proposedStartTime,
          endTime: change.proposedEndTime,
          durationHours: nextDuration,
          assignedUid: nextAssignedUid,
          assignedName: nextAssignedName,
          candidateUids: [nextAssignedUid],
          status: 'scheduled',
          fairnessNote: `Batch approved by ${manager.displayName}.`.slice(0, 300),
          updatedAt: serverTimestamp(),
        });
      }

      const notifId = makeSafeId('notif');
      batch.set(doc(db, 'notifications', notifId), {
        id: notifId,
        recipientUid: change.requesterUid,
        title: 'Schedule Change Approved',
        message: `Your ${change.type.replace('_', ' ')} for "${change.shiftTitle}" (${change.proposedDate} ${change.proposedStartTime}–${change.proposedEndTime}) was approved.`,
        category: 'shift_approved',
        relatedShiftId: change.shiftId,
        isRead: false,
        actorUid: manager.uid,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });

      if (
        change.proposedAssigneeUid &&
        change.proposedAssigneeUid !== change.requesterUid
      ) {
        const swapNotifId = makeSafeId('notif');
        batch.set(doc(db, 'notifications', swapNotifId), {
          id: swapNotifId,
          recipientUid: change.proposedAssigneeUid,
          title: 'Shift Reassigned to You',
          message: `Approved schedule change assigned "${change.shiftTitle}" on ${change.proposedDate} (${change.proposedStartTime}–${change.proposedEndTime}) to you.`,
          category: 'shift_approved',
          relatedShiftId: change.shiftId,
          isRead: false,
          actorUid: manager.uid,
          createdAt: serverTimestamp(),
          updatedAt: serverTimestamp(),
        });
      }
      processed += 1;
    }

    try {
      await batch.commit();
    } catch (err) {
      handleFirestoreError(err, OperationType.WRITE, 'scheduleChanges/acceptAll');
    }
  }

  return processed;
}

export async function commitFairnessArrangementPlan(
  resolutions: FairnessResolutionItem[],
  allShifts: Shift[],
  staffDirectory: StaffDirectoryEntry[],
  manager: UserProfile
): Promise<number> {
  if (resolutions.length === 0) return 0;

  const batch = writeBatch(db);
  const nowIso = new Date().toISOString().slice(0, 19);
  const touchedStaff = new Set<string>();

  for (const res of resolutions) {
    const shift = allShifts.find((s) => s.id === res.shiftId);
    if (!shift) continue;

    const nextDate = res.newDate || shift.date;
    const nextStart = res.newStartTime || shift.startTime;
    const nextEnd = res.newEndTime || shift.endTime;
    const nextDuration = computeDurationHours(nextStart, nextEnd, 0);

    batch.update(doc(db, 'shifts', shift.id), {
      date: nextDate,
      startTime: nextStart,
      endTime: nextEnd,
      durationHours: nextDuration,
      assignedUid: res.selectedAssigneeUid,
      assignedName: res.selectedAssigneeName.slice(0, 80),
      candidateUids: [res.selectedAssigneeUid],
      status: 'scheduled',
      fairnessNote: res.reason.slice(0, 300),
      updatedAt: serverTimestamp(),
    });

    touchedStaff.add(res.selectedAssigneeUid);

    // Notify selected staff member
    const notifWinner = makeSafeId('notif');
    batch.set(doc(db, 'notifications', notifWinner), {
      id: notifWinner,
      recipientUid: res.selectedAssigneeUid,
      title: 'Fairness Schedule Assignment',
      message: `"${shift.title}" on ${nextDate} (${nextStart}–${nextEnd}) was assigned to you based on fair working-time balance.`,
      category: 'fairness_rebalanced',
      relatedShiftId: shift.id,
      isRead: false,
      actorUid: manager.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });

    // Notify previous assignee if reassigned from an actual person
    if (res.previousAssigneeUid && res.previousAssigneeUid !== res.selectedAssigneeUid) {
      const notifPrev = makeSafeId('notif');
      batch.set(doc(db, 'notifications', notifPrev), {
        id: notifPrev,
        recipientUid: res.previousAssigneeUid,
        title: 'Overlapping Shift Rebalanced',
        message: `"${shift.title}" (${shift.date} ${shift.startTime}–${shift.endTime}) was reassigned to ${res.selectedAssigneeName} for working-time fairness.`,
        category: 'fairness_rebalanced',
        relatedShiftId: shift.id,
        isRead: false,
        actorUid: manager.uid,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      });
    }
  }

  for (const uid of touchedStaff) {
    const member = staffDirectory.find((s) => s.uid === uid);
    if (member) {
      batch.update(doc(db, 'staffDirectory', uid), {
        lastAssignedAt: nowIso,
        updatedAt: serverTimestamp(),
      });
    }
  }

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, 'shifts/fairnessArrange');
  }

  return resolutions.length;
}

export async function markNotificationReadState(
  notificationId: string,
  isRead: boolean
): Promise<void> {
  try {
    await updateDoc(doc(db, 'notifications', notificationId), {
      isRead,
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `notifications/${notificationId}`);
  }
}

export async function markAllNotificationsRead(
  notificationIds: string[]
): Promise<void> {
  if (notificationIds.length === 0) return;
  const batch = writeBatch(db);
  for (const id of notificationIds.slice(0, 400)) {
    batch.update(doc(db, 'notifications', id), {
      isRead: true,
      updatedAt: serverTimestamp(),
    });
  }
  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, 'notifications/markAllRead');
  }
}

export async function sendTestPushNotificationRecord(
  user: UserProfile
): Promise<void> {
  const notifId = makeSafeId('notif');
  try {
    await setDoc(doc(db, 'notifications', notifId), {
      id: notifId,
      recipientUid: user.uid,
      title: 'Shift Status Alert Test',
      message: `Live push notifications are active for ${user.displayName}. Shift approvals and fairness updates will appear here.`,
      category: 'shift_assigned',
      relatedShiftId: '',
      isRead: false,
      actorUid: user.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, `notifications/${notifId}`);
  }
}

export async function addRosterMember(
  params: {
    displayName: string;
    department: string;
    weeklyHoursTarget: number;
    totalHoursWorked: number;
  }
): Promise<string> {
  const uid = makeSafeId('staff');
  try {
    await setDoc(doc(db, 'staffDirectory', uid), {
      uid,
      displayName: params.displayName.trim().slice(0, 80),
      role: 'staff',
      department: params.department.trim().slice(0, 60),
      totalHoursWorked: Math.max(0, Number(params.totalHoursWorked) || 0),
      scheduledHoursThisWeek: 0,
      weeklyHoursTarget: Math.min(80, Math.max(1, Number(params.weeklyHoursTarget) || 40)),
      lastAssignedAt: '',
      isSampleRoster: true,
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.CREATE, `staffDirectory/${uid}`);
  }
  return uid;
}

export async function toggleManagerRole(
  member: StaffDirectoryEntry,
  makeManager: boolean,
  actorUid: string
): Promise<void> {
  const batch = writeBatch(db);
  const nextRole: UserRole = makeManager ? 'manager' : 'staff';
  batch.update(doc(db, 'staffDirectory', member.uid), {
    role: nextRole,
    updatedAt: serverTimestamp(),
  });

  if (!member.isSampleRoster) {
    if (makeManager) {
      batch.set(doc(db, 'managers', member.uid), {
        uid: member.uid,
        grantedBy: actorUid,
        createdAt: serverTimestamp(),
      });
    } else {
      batch.delete(doc(db, 'managers', member.uid));
    }
    batch.update(doc(db, 'users', member.uid), {
      role: nextRole,
      updatedAt: serverTimestamp(),
    });
  }

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, `managers/${member.uid}`);
  }
}

export async function seedSampleWorkspaceData(
  manager: UserProfile
): Promise<void> {
  const today = new Date();
  const fmtDate = (offsetDays: number) => {
    const d = new Date(today);
    d.setDate(d.getDate() + offsetDays);
    return d.toISOString().slice(0, 10);
  };

  const sampleStaff = [
    {
      uid: 'sample_elena_rostova',
      displayName: 'Elena Rostova',
      department: 'Operations',
      totalHoursWorked: 14.0,
      weeklyHoursTarget: 40,
      lastAssignedAt: fmtDate(-3),
    },
    {
      uid: 'sample_marcus_vance',
      displayName: 'Marcus Vance',
      department: 'Operations',
      totalHoursWorked: 36.5,
      weeklyHoursTarget: 40,
      lastAssignedAt: fmtDate(-1),
    },
    {
      uid: 'sample_priya_nair',
      displayName: 'Priya Nair',
      department: 'Support Desk',
      totalHoursWorked: 21.0,
      weeklyHoursTarget: 40,
      lastAssignedAt: fmtDate(-2),
    },
    {
      uid: 'sample_david_kim',
      displayName: 'David Kim',
      department: 'Support Desk',
      totalHoursWorked: 34.0,
      weeklyHoursTarget: 40,
      lastAssignedAt: fmtDate(-1),
    },
  ];

  // Batch 1: Staff roster + Shifts (must commit before scheduleChanges so exists() rule passes!)
  const batch1 = writeBatch(db);
  for (const s of sampleStaff) {
    batch1.set(doc(db, 'staffDirectory', s.uid), {
      uid: s.uid,
      displayName: s.displayName,
      role: 'staff',
      department: s.department,
      totalHoursWorked: s.totalHoursWorked,
      scheduledHoursThisWeek: 8,
      weeklyHoursTarget: s.weeklyHoursTarget,
      lastAssignedAt: s.lastAssignedAt,
      isSampleRoster: true,
      updatedAt: serverTimestamp(),
    });
  }

  const s1Id = makeSafeId('shift');
  const s2Id = makeSafeId('shift');
  const s3Id = makeSafeId('shift');
  const s4Id = makeSafeId('shift');

  // Overlapping concurrent shifts on same date & department
  batch1.set(doc(db, 'shifts', s1Id), {
    id: s1Id,
    title: 'Morning Dispatch Desk (Slot A)',
    department: 'Operations',
    date: fmtDate(1),
    startTime: '08:00',
    endTime: '16:00',
    durationHours: 8,
    assignedUid: 'sample_marcus_vance',
    assignedName: 'Marcus Vance',
    candidateUids: ['sample_marcus_vance', 'sample_elena_rostova'],
    status: 'conflict',
    visibility: 'team',
    fairnessNote: 'Overlap detected: 2 staff scheduled for 08:00–16:00 Operations.',
    createdBy: manager.uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  batch1.set(doc(db, 'shifts', s2Id), {
    id: s2Id,
    title: 'Morning Dispatch Desk (Slot B)',
    department: 'Operations',
    date: fmtDate(1),
    startTime: '09:00',
    endTime: '15:00',
    durationHours: 6,
    assignedUid: 'sample_elena_rostova',
    assignedName: 'Elena Rostova',
    candidateUids: ['sample_elena_rostova'],
    status: 'conflict',
    visibility: 'team',
    fairnessNote: 'Concurrent overlap with Slot A in Operations.',
    createdBy: manager.uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  // Multi-candidate single slot
  batch1.set(doc(db, 'shifts', s3Id), {
    id: s3Id,
    title: 'Tier-2 Support Coverage',
    department: 'Support Desk',
    date: fmtDate(2),
    startTime: '10:00',
    endTime: '18:00',
    durationHours: 8,
    assignedUid: 'sample_david_kim',
    assignedName: 'David Kim',
    candidateUids: ['sample_david_kim', 'sample_priya_nair'],
    status: 'conflict',
    visibility: 'team',
    fairnessNote: '2 candidates queued — awaiting fairness arrangement.',
    createdBy: manager.uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  // Clean scheduled shift for manager/user
  batch1.set(doc(db, 'shifts', s4Id), {
    id: s4Id,
    title: 'Evening Handoff Lead',
    department: 'Operations',
    date: fmtDate(0),
    startTime: '14:00',
    endTime: '20:00',
    durationHours: 6,
    assignedUid: manager.uid,
    assignedName: manager.displayName,
    candidateUids: [manager.uid],
    status: 'scheduled',
    visibility: 'team',
    fairnessNote: '',
    createdBy: manager.uid,
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  try {
    await batch1.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, 'shifts/seedBatch1');
  }

  // Batch 2: Pending schedule change requests + sample shift submissions
  const batch2 = writeBatch(db);
  const c1Id = makeSafeId('chg');
  const c2Id = makeSafeId('chg');

  batch2.set(doc(db, 'scheduleChanges', c1Id), {
    id: c1Id,
    shiftId: s1Id,
    shiftTitle: 'Morning Dispatch Desk (Slot A)',
    requesterUid: 'sample_marcus_vance',
    requesterName: 'Marcus Vance',
    type: 'swap',
    currentDate: fmtDate(1),
    currentStartTime: '08:00',
    currentEndTime: '16:00',
    proposedDate: fmtDate(1),
    proposedStartTime: '08:00',
    proposedEndTime: '16:00',
    proposedAssigneeUid: 'sample_elena_rostova',
    proposedAssigneeName: 'Elena Rostova',
    reason: 'Offering shift to Elena to balance weekly hours before weekend rotation.',
    status: 'pending',
    reviewedByUid: '',
    reviewedByName: '',
    reviewNote: '',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  batch2.set(doc(db, 'scheduleChanges', c2Id), {
    id: c2Id,
    shiftId: s3Id,
    shiftTitle: 'Tier-2 Support Coverage',
    requesterUid: 'sample_david_kim',
    requesterName: 'David Kim',
    type: 'time_change',
    currentDate: fmtDate(2),
    currentStartTime: '10:00',
    currentEndTime: '18:00',
    proposedDate: fmtDate(2),
    proposedStartTime: '12:00',
    proposedEndTime: '20:00',
    proposedAssigneeUid: 'sample_david_kim',
    proposedAssigneeName: 'David Kim',
    reason: 'Need morning medical appointment; shifting coverage window 2 hours later.',
    status: 'pending',
    reviewedByUid: '',
    reviewedByName: '',
    reviewNote: '',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  // Seed sample shift submissions for some days (leaving other days unsubmitted so they stay blank!)
  const sampleSubs = [
    {
      uid: 'sample_elena_rostova',
      name: 'Elena Rostova',
      dept: 'Operations',
      date: fmtDate(2),
      startTime: '08:00',
      endTime: '16:00',
      title: 'Morning Operations Shift',
    },
    {
      uid: 'sample_marcus_vance',
      name: 'Marcus Vance',
      dept: 'Operations',
      date: fmtDate(2),
      startTime: '08:00',
      endTime: '16:00',
      title: 'Morning Operations Shift',
    },
    {
      uid: 'sample_elena_rostova',
      name: 'Elena Rostova',
      dept: 'Operations',
      date: fmtDate(3),
      startTime: '16:00',
      endTime: '22:00',
      title: 'Evening Operations Coverage',
    },
    {
      uid: 'sample_priya_nair',
      name: 'Priya Nair',
      dept: 'Support Desk',
      date: fmtDate(3),
      startTime: '12:00',
      endTime: '20:00',
      title: 'Afternoon Support Desk',
    },
    {
      uid: 'sample_david_kim',
      name: 'David Kim',
      dept: 'Support Desk',
      date: fmtDate(3),
      startTime: '12:00',
      endTime: '20:00',
      title: 'Afternoon Support Desk',
    },
  ];

  sampleSubs.forEach((sub, idx) => {
    const subId = makeSafeId(`sub${idx}`);
    batch2.set(doc(db, 'shiftSubmissions', subId), {
      id: subId,
      staffUid: sub.uid,
      staffName: sub.name,
      department: sub.dept,
      date: sub.date,
      startTime: sub.startTime,
      endTime: sub.endTime,
      shiftTitle: sub.title,
      notes: 'Submitted availability for weekly scheduling.',
      status: 'submitted',
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  });

  const pto1Id = makeSafeId('pto1');
  const pto2Id = makeSafeId('pto2');

  batch2.set(doc(db, 'timeOffRequests', pto1Id), {
    id: pto1Id,
    staffUid: 'sample_priya_nair',
    staffName: 'Priya Nair',
    department: 'Support Desk',
    startDate: fmtDate(5),
    endDate: fmtDate(6),
    reason: 'Family travel out of town — unavailable for weekend coverage.',
    status: 'pending',
    reviewedByUid: '',
    reviewedByName: '',
    reviewNote: '',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  batch2.set(doc(db, 'timeOffRequests', pto2Id), {
    id: pto2Id,
    staffUid: 'sample_elena_rostova',
    staffName: 'Elena Rostova',
    department: 'Operations',
    startDate: fmtDate(4),
    endDate: fmtDate(4),
    reason: 'Scheduled dental appointment — unavailable all day.',
    status: 'pending',
    reviewedByUid: '',
    reviewedByName: '',
    reviewNote: '',
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
  });

  try {
    await batch2.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, 'scheduleChanges/seedBatch2');
  }

  // Approve Elena's sample off day so fairness exclusion can be observed right away
  try {
    await updateDoc(doc(db, 'timeOffRequests', pto2Id), {
      status: 'approved',
      reviewedByUid: manager.uid,
      reviewedByName: manager.displayName.slice(0, 80),
      reviewNote: 'Approved — excluded from auto-scheduling.',
      updatedAt: serverTimestamp(),
    });
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `timeOffRequests/${pto2Id}`);
  }
}

export async function commitWeeklyScheduleBatch(
  drafts: GeneratedWeeklyShiftDraft[],
  weekStartDate: string,
  staffDirectory: StaffDirectoryEntry[],
  manager: UserProfile
): Promise<{ totalCreated: number; assignedCount: number; openCount: number }> {
  if (drafts.length === 0) return { totalCreated: 0, assignedCount: 0, openCount: 0 };

  const batch = writeBatch(db);
  const hoursByUid = new Map<string, { count: number; hours: number; firstShiftId: string }>();
  const nowIso = new Date().toISOString().slice(0, 19);
  let assignedCount = 0;
  let openCount = 0;

  for (let i = 0; i < drafts.length; i++) {
    const d = drafts[i];
    const shiftId = makeSafeId(`wk${i}`);
    const isOpen = d.status === 'open' || !d.assignedUid;

    batch.set(doc(db, 'shifts', shiftId), {
      id: shiftId,
      title: d.title.trim().slice(0, 120),
      department: d.department.trim().slice(0, 60),
      date: d.date,
      startTime: d.startTime,
      endTime: d.endTime,
      durationHours: d.durationHours,
      assignedUid: isOpen ? '' : d.assignedUid,
      assignedName: isOpen ? '' : d.assignedName.trim().slice(0, 80),
      candidateUids: isOpen ? [] : [d.assignedUid],
      status: isOpen ? 'open' : 'scheduled',
      visibility: 'team',
      fairnessNote: d.fairnessNote.slice(0, 300),
      createdBy: manager.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });

    if (isOpen) {
      openCount += 1;
    } else {
      assignedCount += 1;
      const prev = hoursByUid.get(d.assignedUid) || {
        count: 0,
        hours: 0,
        firstShiftId: shiftId,
      };
      prev.count += 1;
      prev.hours = Math.round((prev.hours + d.durationHours) * 100) / 100;
      hoursByUid.set(d.assignedUid, prev);
    }
  }

  for (const [uid, summary] of hoursByUid.entries()) {
    const member = staffDirectory.find((s) => s.uid === uid);
    if (member) {
      batch.update(doc(db, 'staffDirectory', uid), {
        lastAssignedAt: nowIso,
        updatedAt: serverTimestamp(),
      });
    }

    const notifId = makeSafeId('notif');
    batch.set(doc(db, 'notifications', notifId), {
      id: notifId,
      recipientUid: uid,
      title: 'Weekly Schedule Published',
      message: `You have ${summary.count} shift(s) (${summary.hours.toFixed(1)}h total) scheduled for the week of ${weekStartDate}.`,
      category: 'shift_assigned' as NotificationCategory,
      relatedShiftId: summary.firstShiftId,
      isRead: false,
      actorUid: manager.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  }

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.WRITE, 'shifts/weeklyGenerate');
  }

  return {
    totalCreated: drafts.length,
    assignedCount,
    openCount,
  };
}

export async function updateShiftManually(
  existingShift: Shift,
  updates: {
    title: string;
    department: string;
    date: string;
    startTime: string;
    endTime: string;
    assignedUid: string;
    assignedName: string;
    status: ShiftStatus;
    fairnessNote: string;
  },
  manager: UserProfile
): Promise<void> {
  const durationHours = computeDurationHours(updates.startTime, updates.endTime, 0);
  const batch = writeBatch(db);
  const isBlank = updates.status === 'open' || !updates.assignedUid;
  const effectiveUid = isBlank ? '' : updates.assignedUid;
  const effectiveName = isBlank ? '' : updates.assignedName.trim().slice(0, 80);
  const effectiveStatus: ShiftStatus = isBlank ? 'open' : updates.status;

  batch.update(doc(db, 'shifts', existingShift.id), {
    title: updates.title.trim().slice(0, 120),
    department: updates.department.trim().slice(0, 60),
    date: updates.date,
    startTime: updates.startTime,
    endTime: updates.endTime,
    durationHours,
    assignedUid: effectiveUid,
    assignedName: effectiveName,
    candidateUids: isBlank ? [] : [effectiveUid],
    status: effectiveStatus,
    fairnessNote: (
      updates.fairnessNote.trim() ||
      (isBlank
        ? `Left blank / open by ${manager.displayName}.`
        : `Manually edited by ${manager.displayName}.`)
    ).slice(0, 300),
    updatedAt: serverTimestamp(),
  });

  if (!isBlank && effectiveUid) {
    const notifNew = makeSafeId('notif');
    batch.set(doc(db, 'notifications', notifNew), {
      id: notifNew,
      recipientUid: effectiveUid,
      title: 'Shift Schedule Updated',
      message: `"${updates.title.trim()}" on ${updates.date} (${updates.startTime}–${updates.endTime}) was updated by ${manager.displayName}.`,
      category: 'shift_assigned' as NotificationCategory,
      relatedShiftId: existingShift.id,
      isRead: false,
      actorUid: manager.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  }

  if (existingShift.assignedUid && existingShift.assignedUid !== effectiveUid) {
    const notifOld = makeSafeId('notif');
    batch.set(doc(db, 'notifications', notifOld), {
      id: notifOld,
      recipientUid: existingShift.assignedUid,
      title: isBlank ? 'Shift Cleared' : 'Shift Reassigned',
      message: isBlank
        ? `"${existingShift.title}" on ${existingShift.date} (${existingShift.startTime}–${existingShift.endTime}) was left open by ${manager.displayName}.`
        : `"${existingShift.title}" on ${existingShift.date} (${existingShift.startTime}–${existingShift.endTime}) was reassigned to ${effectiveName}.`,
      category: 'shift_assigned' as NotificationCategory,
      relatedShiftId: existingShift.id,
      isRead: false,
      actorUid: manager.uid,
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp(),
    });
  }

  try {
    await batch.commit();
  } catch (err) {
    handleFirestoreError(err, OperationType.UPDATE, `shifts/${existingShift.id}`);
  }
}

export async function deleteShiftRecord(shiftId: string): Promise<void> {
  try {
    await deleteDoc(doc(db, 'shifts', shiftId));
  } catch (err) {
    handleFirestoreError(err, OperationType.DELETE, `shifts/${shiftId}`);
  }
}

