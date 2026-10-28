function parseIsoDate(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value || '')
  if (!match) return null
  return {
    year: Number(match[1]),
    month: Number(match[2]),
    day: Number(match[3])
  }
}

function pad(value) {
  return String(value).padStart(2, '0')
}

export function oneYearAfter(value) {
  const date = parseIsoDate(value)
  if (!date) return ''
  const nextYear = date.year + 1
  const lastDay = new Date(Date.UTC(nextYear, date.month, 0)).getUTCDate()
  return `${nextYear}-${pad(date.month)}-${pad(Math.min(date.day, lastDay))}`
}

export function isWithinOneCalendarYear(from, to) {
  const maximum = oneYearAfter(from)
  return Boolean(maximum && parseIsoDate(to) && to <= maximum)
}
