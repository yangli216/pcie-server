export function assistantLogDateRange(preset, now = new Date()) {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const end = new Date(start)
  if (preset === 'week') {
    start.setDate(start.getDate() - (start.getDay() + 6) % 7)
    end.setTime(start.getTime()); end.setDate(end.getDate() + 6)
  } else if (preset === 'month') {
    start.setDate(1); end.setMonth(end.getMonth() + 1, 0)
  } else if (preset === 'quarter') {
    start.setMonth(Math.floor(start.getMonth() / 3) * 3, 1)
    end.setFullYear(start.getFullYear(), start.getMonth() + 3, 0)
  } else if (preset === 'year') {
    start.setMonth(0, 1); end.setMonth(11, 31)
  }
  const format = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  return { dateFrom: format(start), dateTo: format(end) }
}
