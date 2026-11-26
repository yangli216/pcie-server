export const QUERY_BATCH_SIZE = 50

export function isCurrentQcRuleVersion(qc, ruleVersion) {
  return Boolean(qc && typeof ruleVersion === 'string' && ruleVersion && qc.ruleVersion === ruleVersion)
}

export function canReviewQc(qc, ruleVersion) {
  return isCurrentQcRuleVersion(qc, ruleVersion) && !qc.stale && qc.resultSource !== 'proxy_refill'
}

export function proxyRefillLabel(result) {
  if (!result || !result.proxyRefill || result.resultSource !== 'proxy_refill') return ''
  return result.stale || result.snapshot ? '上次代配药（待重新核验）' : '代配药 · 免质控'
}

export function pendingQueryRows(rows) {
  return rows.map(row => ({ ...row, score: null, findingCount: null, highSeverityCount: null,
    resultSource: null, proxyRefill: false, analyzedAt: null, qcStatus: 'queued', queryState: 'pending', queryError: '' }))
}

export function applyQueryItems(rows, items) {
  const byId = new Map(items.map(item => [item.idMedrecdoc, item]))
  return rows.map(row => {
    const item = byId.get(row.idMedrecdoc)
    if (!item) return row
    const succeeded = item.status === 'succeeded'
    return { ...row, queryState: item.status, queryError: item.errorMessage || item.errorCode || '',
      qcStatus: succeeded ? 'analyzed' : item.status === 'failed' ? 'failed' : 'running',
      score: succeeded ? item.score : null, findingCount: succeeded ? item.findingCount : null,
      highSeverityCount: succeeded ? item.highSeverityCount : null,
      proxyRefill: succeeded && Boolean(item.proxyRefill) && item.resultSource === 'proxy_refill',
      stale: succeeded ? false : row.stale,
      analyzedAt: succeeded ? item.analyzedAt : null, resultSource: succeeded ? item.resultSource || 'ai' : null }
  })
}

export function queryTaskItems(task) {
  const running = ['queued', 'running'].includes(task.status)
  return (task.items || []).map(item => !running && item.status === 'pending'
    ? { ...item, status: 'failed', errorMessage: task.errorMessage || '任务已中断，请重新查询' } : item)
}

/** Read-only polling never creates a task. Cancelled queries cannot start their next batch. */
export async function runPageQuery({ records, createTask, fetchTask, onTask, onItems,
  isCurrent, wait = () => new Promise(resolve => setTimeout(resolve, 2500)) }) {
  const ids = [...new Set(records.map(row => row.idMedrecdoc))]
  for (let offset = 0; offset < ids.length; offset += QUERY_BATCH_SIZE) {
    if (!isCurrent()) return
    const batch = ids.slice(offset, offset + QUERY_BATCH_SIZE)
    const created = await createTask(batch, true)
    if (!isCurrent()) return // The accepted server task still runs; do not claim cancellation.
    let task
    do {
      if (!isCurrent()) return
      task = await fetchTask(created.taskId)
      if (!isCurrent()) return
      const items = queryTaskItems(task)
      if (items.length !== batch.length || new Set(items.map(item => item.idMedrecdoc)).size !== batch.length ||
        items.some(item => !batch.includes(item.idMedrecdoc))) {
        throw new Error('质控任务进度不完整，请重新查询或联系管理员')
      }
      onTask(task)
      const running = ['queued', 'running'].includes(task.status)
      onItems(items)
      if (!running) break
      await wait()
    } while (isCurrent())
  }
}
