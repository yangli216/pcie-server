import axios from 'axios'
import { clearAdminAuth, getAdminToken } from '../utils/auth'

function withRequestId(message, requestId) {
  return requestId ? `${message}（请求ID：${requestId}）` : message
}

function normalizeTechnicalMessage(message, fallback) {
  const text = String(message || '').trim()
  const lower = text.toLowerCase()

  if (!text || text === 'undefined' || text === 'null') {
    return fallback
  }
  if (text === 'Network Error' || lower.includes('network error') || lower.includes('failed to fetch')) {
    return '无法连接后台服务，请检查服务是否启动、网络或代理配置。'
  }
  if (lower.includes('timeout') || lower.includes('exceeded')) {
    return '请求超时，请稍后重试；如持续出现，请检查后台服务负载。'
  }
  if (text === 'Internal Server Error' || lower.includes('internal server error')) {
    return '后台服务处理失败，请稍后重试；如持续出现，请联系管理员查看日志。'
  }
  if (/ORA-\d{5}/i.test(text) || /(^|\s)(java|org|com)\.[\w.]+/.test(text) || lower.includes('sqlexception') || lower.includes('nullpointerexception')) {
    return '后台服务处理失败，请稍后重试；如持续出现，请联系管理员查看日志。'
  }
  return text
}

function resolveErrorMessage(error) {
  const response = error && error.response
  const body = response && response.data
  const requestId = body && body.requestId
  const status = response && response.status

  if (body && body.message) {
    return withRequestId(
      normalizeTechnicalMessage(body.message, status >= 500 ? '后台服务处理失败，请稍后重试。' : '请求失败'),
      requestId
    )
  }
  if (status === 401) {
    return withRequestId('登录已失效，请重新登录', requestId)
  }
  if (status === 403) {
    return withRequestId('当前账号没有权限执行该操作，请联系管理员确认授权范围。', requestId)
  }
  if (status === 404) {
    return withRequestId('请求的管理接口不存在，请确认前后端版本是否匹配。', requestId)
  }
  if (status >= 500) {
    return withRequestId('后台服务处理失败，请稍后重试；如持续出现，请联系管理员查看日志。', requestId)
  }
  return normalizeTechnicalMessage(error && error.message, '请求失败')
}

async function decodeBlobError(error) {
  const response = error && error.response
  const body = response && response.data
  if (typeof Blob === 'undefined' || !(body instanceof Blob)) {
    return error
  }

  try {
    const text = await body.text()
    if (text) {
      response.data = JSON.parse(text)
    }
  } catch (decodeError) {
    // Keep Axios' original error when the download response is not JSON.
  }
  return error
}

function isUnauthorizedCode(code) {
  const value = String(code || '').toLowerCase()
  return value === '401' || value === 'unauthorized'
}

function redirectToLogin() {
  if (typeof window === 'undefined') {
    return
  }
  const hash = window.location.hash || '#/overview'
  if (hash.indexOf('#/login') === 0) {
    return
  }
  const redirect = hash.replace(/^#/, '') || '/overview'
  window.location.replace(`#/login?redirect=${encodeURIComponent(redirect)}`)
}

function handleUnauthorized(message) {
  clearAdminAuth()
  redirectToLogin()
  return Promise.reject(new Error(message || '登录已失效，请重新登录'))
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000
})

http.interceptors.request.use(config => {
  const token = getAdminToken()
  const url = config && config.url ? config.url : ''

  if (token && url !== '/admin/api/auth/login') {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

http.interceptors.response.use(
  response => {
    const body = response.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'code')) {
      if (String(body.code) === '0') {
        return body.data
      }
      if (isUnauthorizedCode(body.code)) {
        return handleUnauthorized(body.message)
      }
      return Promise.reject(new Error(withRequestId(
        normalizeTechnicalMessage(body.message, '请求失败'),
        body.requestId
      )))
    }

    return response.data
  },
  async error => {
    const decodedError = await decodeBlobError(error)
    if (decodedError && decodedError.response && decodedError.response.status === 401) {
      return handleUnauthorized(resolveErrorMessage(decodedError))
    }
    return Promise.reject(new Error(resolveErrorMessage(decodedError)))
  }
)

export default http
