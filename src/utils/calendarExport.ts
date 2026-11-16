import { Shift } from '../types';

export function formatIsoDateLocal(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function parseIsoDateLocal(dateStr: string): Date {
  const [y, m, d] = dateStr.split('-').map(Number);
  return new Date(y, (m || 1) - 1, d || 1, 12, 0, 0);
}

export function addDaysIso(dateStr: string, deltaDays: number): string {
  const d = parseIsoDateLocal(dateStr);
  d.setDate(d.getDate() + deltaDays);
  return formatIsoDateLocal(d);
}

export function getMondayOfWeek(dateStr: string): string {
  const d = parseIsoDateLocal(dateStr);
  const day = d.getDay(); // 0 = Sun, 1 = Mon ...
  const diffToMonday = day === 0 ? -6 : 1 - day;
  d.setDate(d.getDate() + diffToMonday);
  return formatIsoDateLocal(d);
}

export function getWeekDates(mondayStr: string): string[] {
  return Array.from({ length: 7 }, (_, i) => addDaysIso(mondayStr, i));
}

export function getMonthGridDates(year: number, monthIndex0: number): string[] {
  const firstDay = new Date(year, monthIndex0, 1, 12, 0, 0);
  const firstMonday = getMondayOfWeek(formatIsoDateLocal(firstDay));
  return Array.from({ length: 42 }, (_, i) => addDaysIso(firstMonday, i));
}

function escapeIcsText(val: string): string {
  return val
    .replace(/\\/g, '\\\\')
    .replace(/\n/g, '\\n')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,');
}

function toIcsLocalDateTime(dateStr: string, timeStr: string): string {
  const cleanDate = dateStr.replace(/-/g, '');
  const cleanTime = timeStr.replace(/:/g, '').slice(0, 4).padEnd(4, '0');
  return `${cleanDate}T${cleanTime}00`;
}

export function buildIcsContent(shifts: Shift[], calendarName = 'Cadence Shift Schedule'): string {
  const nowStamp =
    new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z');

  const lines: string[] = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Cadence//Shift Reporting & Scheduling//EN',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    `X-WR-CALNAME:${escapeIcsText(calendarName)}`,
  ];

  for (const shift of shifts) {
    const startDt = toIcsLocalDateTime(shift.date, shift.startTime);
    const endDateStr =
      shift.endTime <= shift.startTime ? addDaysIso(shift.date, 1) : shift.date;
    const endDt = toIcsLocalDateTime(endDateStr, shift.endTime);
    const assigneeLabel = shift.assignedName?.trim() || 'Unassigned';
    const summary = `${shift.title} — ${assigneeLabel}`;
    const descParts = [
      `Department: ${shift.department}`,
      `Assigned: ${assigneeLabel}`,
      `Duration: ${shift.durationHours.toFixed(1)} hours`,
      `Status: ${shift.status}`,
      shift.fairnessNote ? `Note: ${shift.fairnessNote}` : '',
    ].filter(Boolean);

    lines.push(
      'BEGIN:VEVENT',
      `UID:${shift.id}@cadence.schedule`,
      `DTSTAMP:${nowStamp}`,
      `DTSTART:${startDt}`,
      `DTEND:${endDt}`,
      `SUMMARY:${escapeIcsText(summary)}`,
      `DESCRIPTION:${escapeIcsText(descParts.join('\n'))}`,
      `LOCATION:${escapeIcsText(shift.department)}`,
      'END:VEVENT'
    );
  }

  lines.push('END:VCALENDAR');
  return lines.join('\r\n');
}

export function downloadIcsFile(shifts: Shift[], filename: string, calendarName?: string): void {
  const content = buildIcsContent(shifts, calendarName);
  const blob = new Blob([content], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename.endsWith('.ics') ? filename : `${filename}.ics`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export function buildGoogleCalendarUrl(shift: Shift): string {
  const startDt = toIcsLocalDateTime(shift.date, shift.startTime);
  const endDateStr =
    shift.endTime <= shift.startTime ? addDaysIso(shift.date, 1) : shift.date;
  const endDt = toIcsLocalDateTime(endDateStr, shift.endTime);
  const assigneeLabel = shift.assignedName?.trim() || 'Unassigned';

  const details = [
    `Department: ${shift.department}`,
    `Assigned Staff: ${assigneeLabel}`,
    `Duration: ${shift.durationHours.toFixed(1)}h`,
    shift.fairnessNote ? `Scheduling Note: ${shift.fairnessNote}` : '',
  ]
    .filter(Boolean)
    .join('\n');

  const params = new URLSearchParams({
    action: 'TEMPLATE',
    text: `${shift.title} (${assigneeLabel})`,
    dates: `${startDt}/${endDt}`,
    details,
    location: shift.department,
  });

  return `https://calendar.google.com/calendar/render?${params.toString()}`;
}
