export const inpatientLogStatuses = [
  { value: 'generating', label: '生成中', tone: 'warning' },
  { value: 'waiting', label: '待补充依据', tone: 'warning' },
  { value: 'generated', label: '已生成', tone: 'success' },
  { value: 'generation_failed', label: '生成失败', tone: 'danger' },
  { value: 'fallback', label: '备用草稿', tone: 'warning' },
  { value: 'writeback_pending', label: '待HIS回执', tone: 'warning' },
  { value: 'writeback_success', label: '回填成功', tone: 'success' },
  { value: 'writeback_failed', label: '回填失败', tone: 'danger' },
  { value: 'abandoned', label: '已放弃', tone: 'muted' },
  { value: 'superseded', label: '已重新生成', tone: 'muted' }
]
export function inpatientLogStatus(value) {
  return inpatientLogStatuses.find(item => item.value === value) || { label: value || '未记录', tone: 'muted' }
}
