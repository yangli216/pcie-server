<template>
  <div class="page-surface func-page" v-loading="loading">
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
            <el-option v-for="r in regionOptions" :key="r.id" :label="r.name" :value="r.id" />
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
            <el-option v-for="o in hisOrgOptions" :key="o.id" :label="o.label" :value="o.id" />
          </el-select>
        </div>
        <div class="filter-item">
          <div class="filter-label">功能模块</div>
          <el-select v-model="query.functionModules" placeholder="全部功能" multiple collapse-tags clearable size="small" class="function-select" @change="search">
            <el-option v-for="m in moduleOptions" :key="m" :label="m" :value="m" />
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

    <div class="metric-grid metric-grid--three">
      <metric-card label="总调用次数" :value="(response.totalCallCount || 0).toLocaleString()" />
      <metric-card label="平均每日调用" :value="(response.avgDailyCalls || 0).toLocaleString()" />
      <metric-card label="功能使用率" :value="response.usageRate || '0%'" />
    </div>

    <div class="chart-grid">
      <chart-panel title="功能使用排行" :empty="isRankingEmpty">
        <div ref="rankChartRef" class="chart-body--rank"></div>
      </chart-panel>
      <chart-panel title="功能使用趋势" :empty="isTrendEmpty">
        <div ref="trendChartRef" class="chart-body--trend"></div>
        <div class="trend-legend">
          <span
            v-for="(m, idx) in (trend.modules || [])"
            :key="m"
            class="legend-item"
          >
            <span class="legend-dot" :style="{ background: COLORS[idx % COLORS.length] }"></span>
            {{ m }}
          </span>
        </div>
      </chart-panel>
    </div>

    <section class="page-section page-section--table">
      <div class="page-section__header">
        <span class="page-section__title">功能使用明细</span>
        <div class="page-section__meta">
          <AdminPagination
            compact
            :total="response.total || 0"
            :size.sync="pageSize"
            :current.sync="pageNum"
            @change="search"
          />
        </div>
      </div>
      <el-table :data="response.records || []" size="small" class="admin-table">
        <el-table-column prop="moduleName" label="功能模块" min-width="160" />
        <el-table-column prop="callCount" label="调用次数" width="120" sortable />
        <el-table-column prop="doctorCount" label="使用医生数" width="120" sortable />
        <el-table-column prop="avgPerDoctor" label="人均调用" width="100" />
        <el-table-column prop="growthRate" label="增长率(%)" width="100" sortable>
          <template slot-scope="{ row }">
            <span :style="{ color: parseFloat(row.growthRate || 0) >= 0 ? '#10B981' : '#EF4444' }">
              {{ row.growthRate || '0' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import http from '../api/http'
import { fetchHisOrgOptions, fetchRegions } from '../api/reference'
import { AdminFilterBar, ChartPanel, MetricCard, TimeRangeFilter } from '../components/ui'
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

const COLORS = ['#3B82F6', '#10B981', '#F59E0B', '#8B5CF6', '#EF4444', '#EC4899', '#06B6D4', '#F97316']

export default {
  components: {
    AdminFilterBar,
    ChartPanel,
    MetricCard,
    TimeRangeFilter
  },
  data() {
    return {
      loading: false,
      organizationLocked: false,
      timeRange: 'month',
      timeRangeOptions: TIME_RANGES,
      pageNum: 1,
      pageSize: 10,
      query: { dateFrom: '', dateTo: '', idRegion: '', hisOrgId: '', functionModules: [] },
      response: {},
      trend: { modules: [], days: [], values: [] },
      moduleOptions: [],
      regionOptions: [],
      hisOrgOptions: [],
      exporting: false,
      COLORS,
      rankChart: null,
      trendChart: null
    }
  },
  computed: {
    isRankingEmpty() {
      return !(this.response.ranking || []).some(item => Number(item.callCount) > 0)
    },
    isTrendEmpty() {
      return !(this.trend.values || []).some(series => (series || []).some(value => Number(value) > 0))
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
    this.loadRefs()
    this.search()
  },
  beforeDestroy() {
    this.disposeCharts()
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
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    },
    setTimeRange(v) {
      this.timeRange = v
      this.initDateRange()
      this.search()
    },
    onCustomDateChange() {
      if (this.query.dateFrom && this.query.dateTo) this.search()
    },
    async loadRefs() {
      try {
        if (this.organizationLocked) {
          this.moduleOptions = await http.get('/admin/api/analytics/function-modules') || []
          return
        }
        const [regions, hisOrgs, mods] = await Promise.all([
          fetchRegions({ sdStatus: '1' }),
          fetchHisOrgOptions(),
          http.get('/admin/api/analytics/function-modules')
        ])
        this.regionOptions = (regions || []).map(r => ({ id: r.idRegion, name: r.naRegion }))
        this.hisOrgOptions = (hisOrgs || []).map(o => ({
          id: o.hisOrgId,
          label: o.hisOrgName ? `${o.hisOrgName}（${o.hisOrgId}）` : o.hisOrgId
        }))
        this.moduleOptions = mods || []
      } catch (e) { /* degrade */ }
    },
    async search() {
      this.loading = true
      try {
        const params = {
          ...this.query,
          current: this.pageNum,
          size: this.pageSize
        }
        if (!params.idRegion) delete params.idRegion
        if (!params.hisOrgId) delete params.hisOrgId
        if (!params.functionModules || params.functionModules.length === 0) delete params.functionModules

        const data = await http.get('/admin/api/analytics/function-usage', { params })
        this.response = data || {}
        this.pageNum = Number((data && data.current) || this.pageNum)
        this.pageSize = Number((data && data.size) || this.pageSize)
        this.trend = (data && data.trend) || { modules: [], days: [], values: [] }
        this.$nextTick(() => {
          this.renderRankChart()
          this.renderTrendChart()
        })
      } catch (e) {
        this.$message.error((e && e.message) || '加载失败')
      } finally {
        this.loading = false
      }
    },
    reset() {
      this.timeRange = 'month'
      const hisOrgId = this.organizationLocked ? this.query.hisOrgId : ''
      this.query = { dateFrom: '', dateTo: '', idRegion: '', hisOrgId, functionModules: [] }
      this.pageNum = 1
      this.initDateRange()
      this.search()
    },
    async exportData() {
      this.exporting = true
      try {
        const params = { ...this.query }
        if (!params.idRegion) delete params.idRegion
        if (!params.hisOrgId) delete params.hisOrgId
        if (!params.functionModules || params.functionModules.length === 0) delete params.functionModules
        const blob = await http.get('/admin/api/analytics/function-usage/export', { params, responseType: 'blob' })
        this.downloadBlob(blob, '辅诊功能统计_' + new Date().toISOString().slice(0, 10) + '.xlsx')
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
    },
    disposeCharts() {
      if (this.rankChart) { this.rankChart.dispose(); this.rankChart = null }
      if (this.trendChart) { this.trendChart.dispose(); this.trendChart = null }
      if (this._resizeHandler) {
        window.removeEventListener('resize', this._resizeHandler)
        this._resizeHandler = null
      }
    },
    bindResize() {
      if (this._resizeHandler) {
        return
      }
      this._resizeHandler = () => {
        [this.rankChart, this.trendChart].forEach(chart => {
          if (chart) {
            chart.resize()
          }
        })
      }
      window.addEventListener('resize', this._resizeHandler)
    },
    renderRankChart() {
      if (this.rankChart) this.rankChart.dispose()
      this.rankChart = null
      if (!this.$refs.rankChartRef || this.isRankingEmpty) return
      this.rankChart = echarts.init(this.$refs.rankChartRef)
      const data = (this.response.ranking || []).slice(0, 10).reverse()
      this.rankChart.setOption({
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        grid: { left: 140, right: 40, top: 10, bottom: 20 },
        xAxis: { type: 'value', splitLine: { lineStyle: { color: '#F1F5F9' } }, axisLabel: { color: '#94A3B8', fontSize: 11 } },
        yAxis: {
          type: 'category',
          data: data.map(d => d.moduleName),
          axisLine: { lineStyle: { color: '#E2E8F0' } },
          axisLabel: { color: '#475569', fontSize: 12 }
        },
        series: [{
          type: 'bar',
          data: data.map(d => d.callCount),
          barWidth: 20,
          itemStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
              { offset: 0, color: '#60A5FA' },
              { offset: 1, color: '#3B82F6' }
            ]),
            borderRadius: [0, 4, 4, 0]
          },
          label: { show: true, position: 'right', color: '#475569', fontSize: 11 }
        }]
      })
      this.bindResize()
    },
    renderTrendChart() {
      if (this.trendChart) this.trendChart.dispose()
      this.trendChart = null
      if (!this.$refs.trendChartRef || this.isTrendEmpty) return
      this.trendChart = echarts.init(this.$refs.trendChartRef)
      const modules = this.trend.modules || []
      const days = this.trend.days || []
      const values = this.trend.values || []
      this.trendChart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 50, right: 20, top: 10, bottom: 30 },
        xAxis: {
          type: 'category',
          data: days,
          axisLine: { lineStyle: { color: '#E2E8F0' } },
          axisLabel: { color: '#94A3B8', fontSize: 11 }
        },
        yAxis: {
          type: 'value',
          splitLine: { lineStyle: { color: '#F1F5F9' } },
          axisLabel: { color: '#94A3B8', fontSize: 11 }
        },
        series: modules.map((m, i) => ({
          name: m,
          type: 'line',
          data: values[i] || [],
          smooth: true,
          symbol: 'circle',
          symbolSize: 4,
          lineStyle: { color: COLORS[i % COLORS.length], width: 2 },
          itemStyle: { color: COLORS[i % COLORS.length] }
        }))
      })
      this.bindResize()
    }
  }
}
</script>

<style scoped>
.metric-grid--three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.function-select {
  width: 220px;
}

.trend-legend {
  display: flex;
  justify-content: center;
  gap: 16px;
  margin-top: 8px;
  flex-wrap: wrap;
}

@media (max-width: 960px) {
  .metric-grid--three {
    grid-template-columns: 1fr;
  }
}
</style>
