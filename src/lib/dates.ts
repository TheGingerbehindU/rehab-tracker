export const APP_TIME_ZONE = 'Europe/Berlin'

export const HALF_HOUR_TIMES = Array.from({ length: 48 }, (_, index) => {
  const minutes = index * 30
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
})

export function todayInBerlin(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: APP_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

export function isDateOnly(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  )
}

export function addDays(dateOnly: string, amount: number): string {
  const [year, month, day] = dateOnly.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day + amount))
  return date.toISOString().slice(0, 10)
}

export function monthKey(dateOnly: string): string {
  return dateOnly.slice(0, 7)
}

export function shiftMonth(key: string, amount: number): string {
  const [year, month] = key.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1 + amount, 1))
  return date.toISOString().slice(0, 7)
}

export function monthDays(key: string): Array<string | null> {
  const [year, month] = key.split('-').map(Number)
  const first = new Date(Date.UTC(year, month - 1, 1))
  const mondayOffset = (first.getUTCDay() + 6) % 7
  const count = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const cells: Array<string | null> = Array(mondayOffset).fill(null)
  for (let day = 1; day <= count; day += 1) {
    cells.push(`${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`)
  }
  while (cells.length % 7) cells.push(null)
  return cells
}

export function displayDate(dateOnly: string, options?: Intl.DateTimeFormatOptions): string {
  const [year, month, day] = dateOnly.split('-').map(Number)
  if (!options) return `${String(day).padStart(2, '0')}.${String(month).padStart(2, '0')}.${year}`
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'UTC',
    ...options,
  }).format(new Date(Date.UTC(year, month - 1, day)))
}

export function displayMonth(key: string): string {
  return displayDate(`${key}-01`, { month: 'long', year: 'numeric' })
}

export function sessionTimestamp(dateOnly: string, time: string): string {
  const safeTime = /^\d{2}:\d{2}$/.test(time) ? time : '12:00'
  return new Date(`${dateOnly}T${safeTime}:00`).toISOString()
}

export function sessionTimeValue(sessionAt: string): string {
  const date = new Date(sessionAt)
  if (Number.isNaN(date.getTime())) return '12:00'
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: APP_TIME_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(date)
}

export function nearestHalfHour(time: string): string {
  const match = time.match(/^(\d{1,2}):(\d{2})$/)
  if (!match) return '12:00'
  const minutes = Number(match[1]) * 60 + Number(match[2])
  const rounded = Math.min(23 * 60 + 30, Math.max(0, Math.round(minutes / 30) * 30))
  return `${String(Math.floor(rounded / 60)).padStart(2, '0')}:${String(rounded % 60).padStart(2, '0')}`
}

export function displayTime(sessionAt: string): string {
  return sessionTimeValue(sessionAt)
}

export function followUpTimestamp(sessionAt: string): string {
  const time = new Date(sessionAt).getTime()
  return new Date(time + 24 * 60 * 60 * 1000).toISOString()
}

export function displayDateTime(timestamp: string): string {
  return new Intl.DateTimeFormat('de-DE', {
    timeZone: APP_TIME_ZONE,
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(timestamp))
}

export function elapsedHours(recordedAt: string | null, sessionAt: string): number | null {
  if (!recordedAt) return null
  return Math.max(0, Math.round((new Date(recordedAt).getTime() - new Date(sessionAt).getTime()) / 36e5))
}
