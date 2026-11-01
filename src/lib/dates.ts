export const APP_TIME_ZONE = 'Europe/Berlin'

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
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'UTC',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    ...options,
  }).format(new Date(Date.UTC(year, month - 1, day)))
}

export function displayMonth(key: string): string {
  return displayDate(`${key}-01`, { month: 'long', year: 'numeric' })
}

export function elapsedHours(recordedAt: string | null, sessionDate: string): number | null {
  if (!recordedAt) return null
  const noonUtc = new Date(`${sessionDate}T12:00:00.000Z`).getTime()
  return Math.max(0, Math.round((new Date(recordedAt).getTime() - noonUtc) / 36e5))
}

