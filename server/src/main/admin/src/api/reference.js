import http from './http'

async function fetchAll(resource, params = {}) {
  const size = 100
  let current = 1
  let total = 0
  const records = []

  do {
    const data = await http.get(resource, {
      params: {
        ...params,
        current,
        size
      }
    })
    const pageRecords = data.records || []
    records.push(...pageRecords)
    total = Number(data.total || 0)
    current += 1
    if (!pageRecords.length) break
  } while (records.length < total)

  return records
}

export async function fetchRegions(params = {}) {
  return fetchAll('/admin/api/regions', params)
}

export async function fetchOrgs(params = {}) {
  return fetchAll('/admin/api/orgs', params)
}

export async function refOptions() {
  const [regions, orgs] = await Promise.all([fetchRegions(), fetchOrgs()])
  return { regions, orgs }
}

export async function activeRefOptions() {
  const [regions, orgs] = await Promise.all([
    fetchRegions({ sdStatus: '1' }),
    fetchOrgs({ sdStatus: '1' })
  ])
  const enabledRegionIds = new Set((regions || []).map(item => item.idRegion))
  return {
    regions,
    orgs: (orgs || []).filter(item => enabledRegionIds.has(item.idRegion))
  }
}

export async function fetchHisOrgOptions() {
  const data = await http.get('/admin/api/analytics/his-org-options')
  return data || []
}
