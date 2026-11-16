package com.cadence.shifts.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.cadence.shifts.data.model.Shift
import com.cadence.shifts.data.model.ShiftStatus
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object CalendarExportHelper {

    private fun escapeIcsText(text: String): String =
        text.replace("\\", "\\\\")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace(",", "\\,")
            .replace(";", "\\;")

    private fun toIcsStamp(dateStr: String, timeStr: String): String {
        val d = dateStr.replace("-", "")
        val t = timeStr.replace(":", "") + "00"
        return "${d}T$t"
    }

    private fun resolveEndDate(dateStr: String, startTime: String, endTime: String): String {
        return if (endTime <= startTime) {
            runCatching {
                LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                    .plusDays(1)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
            }.getOrDefault(dateStr)
        } else {
            dateStr
        }
    }

    fun buildGoogleCalendarIntent(shift: Shift): Intent {
        val endDate = resolveEndDate(shift.date, shift.startTime, shift.endTime)
        val start = toIcsStamp(shift.date, shift.startTime)
        val end = toIcsStamp(endDate, shift.endTime)
        val assigneeLabel = if (shift.assignedName.isNotBlank()) shift.assignedName else "Open / Unassigned"
        val details = buildString {
            appendLine("Department: ${shift.department}")
            appendLine("Assigned: $assigneeLabel")
            appendLine("Duration: ${shift.durationHours}h")
            if (shift.fairnessNote.isNotBlank()) {
                append("Notes: ${shift.fairnessNote}")
            }
        }.trim()

        val uri = Uri.Builder()
            .scheme("https")
            .authority("calendar.google.com")
            .path("/calendar/render")
            .appendQueryParameter("action", "TEMPLATE")
            .appendQueryParameter("text", "${shift.title} (${shift.department})")
            .appendQueryParameter("dates", "$start/$end")
            .appendQueryParameter("details", details)
            .build()

        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun generateIcsContent(shifts: List<Shift>): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Cadence//Shift Reporting & Fair Scheduling//EN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH"
        )
        for (shift in shifts) {
            val endDate = resolveEndDate(shift.date, shift.startTime, shift.endTime)
            val dtStart = toIcsStamp(shift.date, shift.startTime)
            val dtEnd = toIcsStamp(endDate, shift.endTime)
            val assignee = if (shift.assignedName.isNotBlank()) shift.assignedName else "Open Shift"
            val summary = escapeIcsText("${shift.title} — $assignee")
            val desc = escapeIcsText(
                "Department: ${shift.department}\nStatus: ${shift.status.value}\nHours: ${shift.durationHours}h" +
                    if (shift.fairnessNote.isNotBlank()) "\n${shift.fairnessNote}" else ""
            )
            val statusVal = if (shift.status == ShiftStatus.OPEN) "TENTATIVE" else "CONFIRMED"
            lines.addAll(
                listOf(
                    "BEGIN:VEVENT",
                    "UID:cadence-${shift.id}@cadence.app",
                    "DTSTART:$dtStart",
                    "DTEND:$dtEnd",
                    "SUMMARY:$summary",
                    "DESCRIPTION:$desc",
                    "CATEGORIES:${escapeIcsText(shift.department)}",
                    "STATUS:$statusVal",
                    "END:VEVENT"
                )
            )
        }
        lines.add("END:VCALENDAR")
        return lines.joinToString("\r\n")
    }

    fun shareIcsFile(context: Context, shifts: List<Shift>, filename: String = "cadence-shifts.ics") {
        val dir = File(context.cacheDir, "calendar_exports").apply { mkdirs() }
        val file = File(dir, filename)
        file.writeText(generateIcsContent(shifts))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cadence Shift Schedule")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Export Calendar (.ics)")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
