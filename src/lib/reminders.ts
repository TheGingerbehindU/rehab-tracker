import type { RehabDay } from '../types'

function calendarTimestamp(value: string): string {
  return new Date(value).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
}

function calendarText(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/,/g, '\\,').replace(/;/g, '\\;')
}

export function followUpCalendar(record: RehabDay): string {
  const start = calendarTimestamp(record.followUpDueAt)
  const end = calendarTimestamp(new Date(new Date(record.followUpDueAt).getTime() + 15 * 60 * 1000).toISOString())
  return [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Rehab Tracker//24h Follow-up//EN',
    'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT',
    `UID:${calendarText(record.id)}@rehab-tracker`,
    `DTSTAMP:${calendarTimestamp(new Date().toISOString())}`,
    `DTSTART:${start}`,
    `DTEND:${end}`,
    'SUMMARY:Rehab Tracker: 24h follow-up',
    `DESCRIPTION:${calendarText(`Record the 24-hour response for your ${record.sessionType.toLowerCase()} session.`)}`,
    'BEGIN:VALARM',
    'ACTION:DISPLAY',
    'TRIGGER:PT0M',
    'DESCRIPTION:Your 24-hour session follow-up is due.',
    'END:VALARM',
    'END:VEVENT',
    'END:VCALENDAR',
    '',
  ].join('\r\n')
}

export function downloadFollowUpReminder(record: RehabDay): void {
  const url = URL.createObjectURL(new Blob([followUpCalendar(record)], { type: 'text/calendar;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `rehab-follow-up-${record.date}.ics`
  anchor.click()
  URL.revokeObjectURL(url)
}
