<template>
  <div class="page-surface user-activity-page" v-loading="loading">
    <admin-filter-bar>
      <div class="filter-row">
        <div class="filter-item">
          <div class="filter-label">时间范围</div>
          <time-range-filter
            v-model="timeRange"
            :options="timeRangeOptions"
            :date-from.sync="query.dateFrom"
            :date-to.sync="query.dateTo"
            @input="setTimeRange"
            @custom-change="onCustomDateChange"
          />
        </div>
        <div v-if="!organizationLocked" class="filter-item">
          <div class="filter-label">区域选择</div>
          <el-select v-model="query.idRegion" placeholder="全部区域" clearable size="small" class="filter-select" @change="search">
            <el-option
              v-for="r in regionOptions"
              :key="r.id"
              :label="r.name"
              :value="r.id"
            />
          </el-select>
        </div>
        <div class="filter-item">
          <div class="filter-label">HIS机构</div>
          <el-select
            v-model="query.hisOrgId"
            :placeholder="organizationLocked ? '本机构' : '全部HIS机构'"
            :clearable="!organizationLocked"
            :disabled="organizationLocked"
            filterable
            size="small"
            class="filter-select"
            @change="search"
          >
            <el-option
              v-for="o in hisOrgOptions"
              :key="o.id"
              :label="o.label"
              :value="o.id"
            />
          </el-select>
        </div>
        <div class="filter-item">
          <div class="filter-label">活跃状态</div>
          <el-select v-model="query.activeStatus" placeholder="全部状态" clearable size="small" class="filter-select" @change="search">
            <el-option label="活跃" value="active" />
            <el-option label="不活跃" value="inactive" />
          </el-select>
        </div>
        <div class="filter-actions">
          <el-button type="primary" size="small" @click="search">查询</el-button>
          <el-button size="small" @click="reset">重置</el-button>
        </div>
      </div>
      <template #actions>
        <el-button size="small" icon="el-icon-download" :loading="exporting" @click="exportData">导出数据</el-button>
      </template>
    </admin-filter-bar>

    <div class="metric-grid user-activity-metrics">
      <metric-card
        v-for="card in cards"
        :key="card.key"
        :label="card.label"
        :value="card.value"
        :growth-text="card.growthText"
        :growth-up="card.growthUp"
        :desc="card.desc"
      />
    </div>

    <section class="page-section page-section--table user-table-card">
      <el-table :data="userList" size="small" class="admin-table">
        <el-table-column prop="naDoctor" label="医生姓名" min-width="120" />
        <el-table-column prop="cdDevice" label="设备编码" min-width="120" />
        <el-table-column label="HIS机构" min-width="180">
          <template slot-scope="{ row }">
            {{ row.hisOrgName || row.hisOrgId || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="naRegion" label="所属区域" min-width="100" />
        <el-table-column label="活跃状态" width="100">
          <template slot-scope="{ row }">
            <status-pill
              :tone="row.activeStatus === 'active' ? 'success' : 'danger'"
              :label="row.activeStatus === 'active' ? '活跃' : '不活跃'"
            />
          </template>
        </el-table-column>
        <el-table-column prop="consultationCount" label="问诊次数" width="100" />
        <el-table-column prop="effectiveConsultationCount" label="有效问诊数" width="120" />
        <el-table-column label="最后活跃时间" width="160">
          <template slot-scope="{ row }">
            {{ row.lastActiveTime || '-' }}
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-bar">
        <el-pagination
          :current-page="userPage.current"
          :page-size="userPage.size"
          :total="userPage.total"
          layout="total, prev, pager, next"
          @current-change="onPageChange"
        />
      </div>
    </section>
  </div>
</template>

<script>
import http from '../api/http'
import { fetchHisOrgOptions, fetchRegions } from '../api/reference'
import { AdminFilterBar, MetricCard, StatusPill, TimeRangeFilter } from '../components/ui'
import { getAdminUser } from '../utils/auth'
import { bbpOrganizationOption, isBbpOrganizationScopedUser } from '../utils/access'

const TIME_RANGES = [
  { value: 'today', label: '今日' },
  { value: 'week', label: '本周' },
  { value: 'month', label: '本月' },
  { value: 'quarter', label: '本季度' },
  { value: 'year', label: '本年' },
  { value: 'custom', label: '自定义' }
]

const CARD_DEFS = [
  { key: 'activeUsers', label: '活跃用户数', desc: '所选时段内有问诊记录的设备数', isAbs: true },
  { key: 'inactiveUsers', label: '不活跃用户数', desc: '所选时段内无问诊记录的设备数', isAbs: true },
  { key: 'activityRate', label: '活跃率', desc: '活跃用户数 / 总设备数', isPct: true },
  { key: 'effectiveConsultationRate', label: '有效问诊率', desc: '有效问诊数 / 总问诊数', isPct: true }
]

export default {
  components: {
    AdminFilterBar,
    MetricCard,
    StatusPill,
    TimeRangeFilter
  },
  data() {
    return {
      loading: false,
      organizationLocked: false,
      timeRange: 'month',
      timeRangeOptions: TIME_RANGES,
      query: { dateFrom: '', dateTo: '', idRegion: '', hisOrgId: '', activeStatus: '' },
      summary: {},
      regionOptions: [],
      hisOrgOptions: [],
      exporting: false,
      userList: [],
      userPage: { current: 1, size: 10, total: 0 }
    }
  },
  computed: {
    cardCompareLabel() {
      const m = {
        today: '较昨日',
        week: '较上周',
        month: '较上月',
        quarter: '较上季度',
        year: '较上年',
        custom: '较上周期'
      }
      return m[this.timeRange] || '较上月'
    },
    cards() {
      const s = this.summary || {}
      const label = this.cardCompareLabel
      return CARD_DEFS.map(def => {
        let value = s[def.key]
        if (value == null) {
          value = def.isPct ? '0%' : 0
        }
        if (def.isPct && value != null && !String(value).endsWith('%')) {
          value = value + '%'
        }
        const growthKey = def.key + 'Growth'
        const growthVal = s[growthKey] || '0'
        const growthNum = parseFloat(growthVal)
        const growthUp = !isNaN(growthNum) && growthNum >= 0
        let growthText = ''
        if (def.isAbs) {
          const n = parseInt(growthVal, 10)
          growthText = isNaN(n) ? `${label}持平` : (n >= 0 ? `${label}增长 ${n} 人` : `${label}减少 ${Math.abs(n)} 人`)
        } else if (def.isPct) {
          growthText = `${label}${growthUp ? '增长' : '下降'} ${Math.abs(growthNum).toFixed(1)}%`
        } else {
          growthText = `${label}${growthUp ? '增长' : '下降'} ${Math.abs(growthNum).toFixed(1)}%`
        }
        return {
          key: def.key,
          label: def.label,
          value,
          desc: def.desc,
          growthUp,
          growthText
        }
      })
    }
  },
  mounted() {
    const currentUser = getAdminUser()
    this.organizationLocked = isBbpOrganizationScopedUser(currentUser)
    const organization = bbpOrganizationOption(currentUser)
    if (this.organizationLocked && organization) {
      this.query.hisOrgId = organization.id
      this.hisOrgOptions = [organization]
    }
    this.initDateRange()
    this.loadRefOptions()
    this.search()
  },
  methods: {
    initDateRange() {
      const now = new Date()
      let from, to
      switch (this.timeRange) {
        case 'today':
          from = to = this.fmt(now)
          break
        case 'week': {
          const d = now.getDay() || 7
          from = this.fmt(new Date(now.getFullYear(), now.getMonth(), now.getDate() - d + 1))
          to = this.fmt(now)
          break
        }
        case 'month':
          from = this.fmt(new Date(now.getFullYear(), now.getMonth(), 1))
          to = this.fmt(now)
          break
        case 'quarter': {
          const qs = Math.floor(now.getMonth() / 3) * 3
          from = this.fmt(new Date(now.getFullYear(), qs, 1))
          to = this.fmt(now)
          break
        }
        case 'year':
          from = this.fmt(new Date(now.getFullYear(), 0, 1))
          to = this.fmt(now)
          break
        default:
          from = this.fmt(new Date(now.getFullYear(), now.getMonth(), 1))
          to = this.fmt(now)
      }
      this.query.dateFrom = from
      this.query.dateTo = to
    },
    fmt(d) {
      const y = d.getFullYear()
      const m = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return `${y}-${m}-${day}`
    },
    setTimeRange(val) {
      this.timeRange = val
      this.initDateRange()
      this.search()
    },
    onCustomDateChange() {
      if (this.query.dateFrom && this.query.dateTo) {
        this.search()
      }
    },
    async loadRefOptions() {
      if (this.organizationLocked) {
        return
      }
      try {
        const [regions, hisOrgs] = await Promise.all([
          fetchRegions({ sdStatus: '1' }),
          fetchHisOrgOptions()
        ])
        this.regionOptions = (regions || []).map(r => ({ id: r.idRegion, name: r.naRegion }))
        this.hisOrgOptions = (hisOrgs || []).map(o => ({
          id: o.hisOrgId,
          label: o.hisOrgName ? `${o.hisOrgName}（${o.hisOrgId}）` : o.hisOrgId
        }))
      } catch (e) {
        // degrade gracefully
      }
    },
    onPageChange(page) {
      this.userPage.current = page
      this.searchUsers()
    },
    async search() {
      this.loading = true
      try {
        this.userPage.current = 1
        const params = { ...this.query, timeRange: this.timeRange }
        if (!params.idRegion) delete params.idRegion
        if (!params.hisOrgId) delete params.hisOrgId
        if (!params.activeStatus) delete params.activeStatus

        const [summary, users] = await Promise.all([
          http.get('/admin/api/user-activity/summary', { params }),
          http.get('/admin/api/user-activity/users', { params: { ...params, current: this.userPage.current, size: this.userPage.size } })
        ])
        this.summary = summary || {}
        this.userList = (users && users.records) || []
        this.userPage.total = (users && users.total) || 0
      } catch (error) {
        this.$message.error((error && error.message) || '加载失败')
      } finally {
        this.loading = false
      }
    },
    async searchUsers() {
      try {
        const params = {
          ...this.query,
          timeRange: this.timeRange,
          current: this.userPage.current,
          size: this.userPage.size
        }
        if (!params.idRegion) delete params.idRegion
        if (!params.hisOrgId) delete params.hisOrgId
        if (!params.activeStatus) delete params.activeStatus

        const data = await http.get('/admin/api/user-activity/users', { params })
        this.userList = (data && data.records) || []
        this.userPage.total = (data && data.total) || 0
      } catch (error) {
        this.$message.error((error && error.message) || '加载用户列表失败')
      }
    },
    reset() {
      this.timeRange = 'month'
      const hisOrgId = this.organizationLocked ? this.query.hisOrgId : ''
      this.query = { dateFrom: '', dateTo: '', idRegion: '', hisOrgId, activeStatus: '' }
      this.userPage.current = 1
      this.initDateRange()
      this.search()
    },
    async exportData() {
      this.exporting = true
      try {
        const params = { ...this.query, timeRange: this.timeRange }
        if (!params.idRegion) delete params.idRegion
        if (!params.hisOrgId) delete params.hisOrgId
        if (!params.activeStatus) delete params.activeStatus
        const blob = await http.get('/admin/api/user-activity/export', { params, responseType: 'blob' })
        this.downloadBlob(blob, '用户活跃度_' + new Date().toISOString().slice(0, 10) + '.xlsx')
      } catch (error) {
        this.$message.error((error && error.message) || '导出失败')
      } finally {
        this.exporting = false
      }
    },
    downloadBlob(blob, filename) {
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
    }
  }
}
</script>

<style scoped>
.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 1280px) {
  .user-activity-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .user-activity-metrics {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
