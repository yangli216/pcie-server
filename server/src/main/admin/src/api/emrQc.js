import http from './http'

const endpoint = '/admin/api/emr-qc'
const ANALYSIS_TIMEOUT = 120000

export function fetchSession() {
  return http.get(`${endpoint}/session`)
}

export function fetchDoctors() {
  return http.get(`${endpoint}/doctors`, { timeout: 30000 })
}

export function fetchRecords(params) {
  return http.get(`${endpoint}/records`, { params, timeout: 30000 })
}

export function fetchDetail(idMedrecdoc) {
  return http.get(`${endpoint}/records/${encodeURIComponent(idMedrecdoc)}`, { timeout: 30000 })
}

export function analyzeRecord(idMedrecdoc, force) {
  return http.post(
    `${endpoint}/records/${encodeURIComponent(idMedrecdoc)}/analyze`,
    { force: Boolean(force) },
    { timeout: ANALYSIS_TIMEOUT }
  )
}

export function reviewRecord(idMedrecdoc, payload) {
  return http.put(`${endpoint}/records/${encodeURIComponent(idMedrecdoc)}/review`, payload)
}

export function createTask(recordIds, force) {
  return http.post(`${endpoint}/tasks`, { recordIds, force: Boolean(force) })
}

export function fetchTask(taskId) {
  return http.get(`${endpoint}/tasks/${encodeURIComponent(taskId)}`)
}

export function fetchLatestTask() {
  return http.get(`${endpoint}/tasks/latest`)
}
