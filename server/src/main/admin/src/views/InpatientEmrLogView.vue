<template>
  <div class="page-surface inpatient-log-page">
    <div v-show="!detailOpen">
      <admin-filter-bar>
        <div class="filter-row">
          <div class="filter-item">
            <div class="filter-label">生成时间</div>
            <time-range-filter v-model="preset" :options="timeOptions" :date-from.sync="dateFrom" :date-to.sync="dateTo" @input="changePreset" @custom-change="changeCustom" />
          </div>
          <div class="filter-item"><div class="filter-label">机构</div>
            <el-select v-model="organization" size="small" filterable clearable placeholder="全部机构" aria-label="机构">
              <el-option v-for="item in organizations" :key="orgKey(item)" :value="orgKey(item)" :label="item.orgName || item.idOrg" />
            </el-select>
          </div>
          <div class="filter-item"><label for="inpatient-log-doctor" class="filter-label">医生</label><el-input id="inpatient-log-doctor" v-model="doctorName" size="small" maxlength="128" clearable placeholder="输入医生姓名…" @keyup.enter.native="search" /></div>
          <div class="filter-item"><label for="inpatient-log-patient" class="filter-label">患者</label><el-input id="inpatient-log-patient" v-model="patientName" size="small" maxlength="128" clearable placeholder="输入患者姓名…" @keyup.enter.native="search" /></div>
          <div class="filter-item"><div class="filter-label">文书模板</div>
            <el-select v-model="templateId" size="small" clearable filterable placeholder="全部模板" aria-label="文书模板">
              <el-option v-for="item in templates" :key="item.templateId + item.templateName" :value="item.templateId" :label="item.templateName" />
            </el-select>
          </div>
          <div class="filter-item"><div class="filter-label">处理结果</div>
            <el-select v-model="status" size="small" clearable placeholder="全部结果" aria-label="处理结果">
              <el-option v-for="item in statuses" :key="item.value" :value="item.value" :label="item.label" />
            </el-select>
          </div>
          <div class="filter-actions"><el-button type="primary" size="small" @click="search">查询</el-button><el-button size="small" @click="reset">重置</el-button></div>
        </div>
        <template #actions><el-button size="small" icon="el-icon-download" :loading="exporting" :disabled="loading || !total" @click="exportCsv">导出 CSV</el-button></template>
      </admin-filter-bar>
      <section class="page-section page-section--table">
        <div class="page-section__header"><div><h1 class="page-section__title">住院病历日志</h1><div class="inpatient-log-page__hint">每次生成独立留痕 · 共 {{ total }} 条</div></div></div>
        <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
        <el-table v-loading="loading" :data="records" empty-text="暂无住院病历生成日志" aria-label="住院病历日志列表">
          <el-table-column label="序号" width="60"><template slot-scope="{ $index }">{{ (current - 1) * size + $index + 1 }}</template></el-table-column>
          <el-table-column label="机构" min-width="190" show-overflow-tooltip><template slot-scope="{ row }">{{ row.orgName || '未记录' }}</template></el-table-column>
          <el-table-column label="医生" min-width="100"><template slot-scope="{ row }">{{ row.doctorName || '未记录' }}</template></el-table-column>
          <el-table-column label="生成时间" width="170"><template slot-scope="{ row }">{{ formatDateTime(row.generationTime) }}</template></el-table-column>
          <el-table-column label="病历书写时间" width="170"><template slot-scope="{ row }">{{ formatDateTime(row.recordTime) }}</template></el-table-column>
          <el-table-column label="患者" min-width="130"><template slot-scope="{ row }"><div>{{ row.patientName || '未记录' }}</div><small class="inpatient-log-page__hint">{{ [row.patientGender, row.patientAge].filter(Boolean).join(' / ') }}</small></template></el-table-column>
          <el-table-column prop="templateName" label="文书模板" min-width="160" show-overflow-tooltip />
          <el-table-column label="处理结果" width="120"><template slot-scope="{ row }"><status-pill :tone="statusMeta(row.status).tone" :label="statusMeta(row.status).label" /></template></el-table-column>
          <el-table-column label="操作" width="75" fixed="right"><template slot-scope="{ row }"><el-button type="text" size="small" @click="openDetail(row.idLog)">详情</el-button></template></el-table-column>
        </el-table>
        <div class="page-footer"><admin-pagination :current.sync="current" :size.sync="size" :total="total" @change="loadData" /></div>
      </section>
    </div>
    <section v-if="detailOpen" v-loading="detailLoading" aria-label="住院病历日志详情">
      <div class="inpatient-log-page__bar"><el-button size="small" icon="el-icon-arrow-left" @click="closeDetail">返回列表</el-button><h1>住院病历生成详情</h1></div>
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
      <template v-if="detail">
        <div class="inpatient-log-page__meta">
          <div><span>机构 / 医生</span><strong>{{ detail.record.orgName || '未记录' }} / {{ detail.record.doctorName || '未记录' }}</strong></div>
          <div><span>患者</span><strong>{{ [detail.record.patientName, detail.record.patientGender, detail.record.patientAge].filter(Boolean).join(' / ') || '未记录' }}</strong></div>
          <div><span>生成时间</span><strong>{{ formatDateTime(detail.record.generationTime) }}</strong></div>
          <div><span>病历书写时间</span><strong>{{ formatDateTime(detail.record.recordTime) }}</strong></div>
          <div><span>文书模板</span><strong>{{ detail.record.templateName }}</strong></div>
          <div><span>处理结果</span><status-pill :tone="statusMeta(detail.record.status).tone" :label="statusMeta(detail.record.status).label" /></div>
        </div>
        <el-alert v-if="detail.record.status === 'writeback_pending'" title="已发起回填，尚未收到HIS终态回执。" type="warning" :closable="false" show-icon />
        <el-alert v-if="detail.record.status === 'fallback'" title="AI生成失败后使用了备用草稿，请核对原始依据。" type="warning" :closable="false" show-icon />
        <div class="inpatient-log-page__columns">
          <chart-panel title="本次生成依据">
            <section v-for="source in detail.snapshot.sources" :key="source.code" class="inpatient-log-page__source">
              <h2>{{ sourceLabel(source.code) }} <small>{{ sourceStatus(source.status) }}</small></h2><div class="inpatient-log-page__text">{{ source.text || '无可展示的来源正文' }}</div>
            </section>
            <p v-if="!detail.snapshot.sources.length" class="inpatient-log-page__hint">尚未获取生成依据</p>
          </chart-panel>
          <chart-panel title="模板字段内容">
            <el-tabs v-model="contentTab">
              <el-tab-pane label="AI生成稿与来源字段" name="generated" />
              <el-tab-pane label="最终稿" name="final" />
              <el-tab-pane :label="`修改对比（${changedFields.length}）`" name="changes" />
            </el-tabs>
            <p v-if="contentTab === 'final' && !hasFinal" class="inpatient-log-page__hint">尚未形成最终稿</p>
            <p v-else-if="contentTab === 'changes' && !changedFields.length" class="inpatient-log-page__hint">暂无字段变更</p>
            <section v-for="field in visibleFields" :key="field.id" class="inpatient-log-page__field">
              <h2>{{ field.section ? field.section + ' / ' : '' }}{{ field.name }} <small>{{ field.source === 'ai' ? 'AI生成' : 'HIS / 模板来源' }}</small></h2>
              <template v-if="contentTab === 'changes'"><del class="inpatient-log-page__text">{{ field.generatedValue || '（空）' }}</del><ins class="inpatient-log-page__text">{{ field.finalValue || '（空）' }}</ins></template>
              <div v-else class="inpatient-log-page__text">{{ (contentTab === 'final' ? field.finalValue : field.generatedValue) || '（空）' }}</div>
            </section>
          </chart-panel>
        </div>
        <chart-panel class="inpatient-log-page__timeline" title="处理时间线">
          <el-timeline><el-timeline-item v-for="event in detail.events" :key="event.eventId" :timestamp="formatDateTime(event.occurredAt)"><strong>{{ statusMeta(event.status).label }}</strong><span class="inpatient-log-page__hint"> · {{ event.message || eventLabel(event.eventType) }} · 序号 {{ event.sequence }}</span></el-timeline-item></el-timeline>
          <div class="inpatient-log-page__ids">生成轮次 <code-tag :value="detail.snapshot.generationId" /> 模板版本 <code-tag :value="detail.snapshot.templateHash" /></div>
        </chart-panel>
      </template>
    </section>
  </div>
</template>
<script>
import http from '../api/http'
import Timeline from 'element-ui/lib/timeline'
import TimelineItem from 'element-ui/lib/timeline-item'
import { AdminFilterBar, TimeRangeFilter, ChartPanel, StatusPill, CodeTag } from '../components/ui'
import { formatDateTime } from '../utils/admin'
import { assistantLogDateRange } from '../utils/assistantLogDates'
import { inpatientLogStatuses, inpatientLogStatus } from '../utils/inpatientLogLabels'
const endpoint = '/admin/api/inpatient-emr-logs'
export default {
  name: 'InpatientEmrLogView',
  components: { AdminFilterBar, TimeRangeFilter, ChartPanel, StatusPill, CodeTag, ElTimeline: Timeline, ElTimelineItem: TimelineItem },
  data() { return {
    preset: 'week', dateFrom: '', dateTo: '', doctorName: '', patientName: '', templateId: '', status: '', organization: '', organizations: [], templates: [],
    statuses: inpatientLogStatuses,
    timeOptions: [{value:'day',label:'本日'},{value:'week',label:'本周'},{value:'month',label:'本月'},{value:'quarter',label:'本季度'},{value:'year',label:'本年'},{value:'custom',label:'自定义'}],
    current: 1, size: 10, total: 0, records: [], applied: {}, loading: false, exporting: false, listError: '', listSequence: 0,
    detailOpen: false, detail: null, detailLoading: false, detailError: '', detailSequence: 0, contentTab: 'generated'
  } },
  computed: {
    changedFields() { return (this.detail?.snapshot.fields || []).filter(field => field.finalValue !== null && field.generatedValue !== field.finalValue) },
    hasFinal() { return (this.detail?.snapshot.fields || []).some(field => field.finalValue !== null) },
    visibleFields() {
      if (this.contentTab === 'changes') return this.changedFields
      if (this.contentTab === 'final' && !this.hasFinal) return []
      return this.detail?.snapshot.fields || []
    }
  },
  mounted() { this.search(); this.loadOptions() },
  beforeDestroy() { this.listSequence++; this.detailSequence++ },
  methods: {
    formatDateTime, statusMeta: inpatientLogStatus,
    sourceStatus(value) { return { used: '已使用', available: '可用', missing: '缺少', waiting: '待获取', failed: '获取失败', skipped: '未使用' }[value] || '未记录' },
    eventLabel(value) { return { started: '开始生成', waiting: '等待补充依据', generated: '生成完成', generation_failed: '生成失败', fallback: '使用备用草稿', writeback_requested: '发起回填', writeback_dispatched: '回填请求已发送', writeback_success: 'HIS回执成功', writeback_failed: '回填失败', abandoned: '医生放弃', superseded: '重新生成上一轮结束' }[value] || '处理事件' },
    sourceLabel(code) { return { doctor_supplement: '医生补充要点', his_summary: 'HIS参考摘要', outpatient_record: '门诊引用正文' }[code] || code },
    orgKey(item) { return JSON.stringify([item.idOrg || '', item.hisOrgId || '']) },
    async loadOptions() {
      try { const data = await http.get(`${endpoint}/options`); this.organizations = data.organizations || []; this.templates = data.templates || [] }
      catch (error) { this.$message.error(`筛选选项加载失败：${error.message}`) }
    },
    changePreset() { if (this.preset !== 'custom') this.search() },
    changeCustom() { if (this.dateFrom && this.dateTo) this.search() },
    search() {
      let range
      if (this.preset === 'custom') {
        if (!this.dateFrom || !this.dateTo) return this.$message.warning('请同时选择开始和结束日期')
        if (this.dateFrom > this.dateTo) return this.$message.warning('开始日期不能晚于结束日期')
        range = {dateFrom: this.dateFrom, dateTo: this.dateTo}
      } else range = assistantLogDateRange(this.preset)
      const org = this.organizations.find(item => this.orgKey(item) === this.organization)
      this.applied = {...range, doctorName: this.doctorName.trim() || undefined, patientName: this.patientName.trim() || undefined,
        templateId: this.templateId || undefined, status: this.status || undefined, idOrg: org?.idOrg || undefined, hisOrgId: org?.hisOrgId || undefined}
      this.current = 1; this.loadData()
    },
    reset() { this.preset = 'week'; this.dateFrom = ''; this.dateTo = ''; this.doctorName = ''; this.patientName = ''; this.organization = ''; this.templateId = ''; this.status = ''; this.search() },
    async loadData() {
      const sequence = ++this.listSequence
      this.loading = true; this.listError = ''; this.records = []
      try {
        const data = await http.get(endpoint, {params: {...this.applied, current: this.current, size: this.size}})
        if (sequence !== this.listSequence) return
        this.records = data.records || []; this.total = data.total; this.current = data.current; this.size = data.size
      } catch (error) { if (sequence === this.listSequence) {this.listError = error.message; this.total = 0} }
      finally { if (sequence === this.listSequence) this.loading = false }
    },
    async openDetail(id) {
      const sequence = ++this.detailSequence
      this.detailOpen = true; this.detail = null; this.detailError = ''; this.contentTab = 'generated'; this.detailLoading = true
      try { const detail = await http.get(`${endpoint}/${encodeURIComponent(id)}`); if (sequence === this.detailSequence) this.detail = detail }
      catch (error) { if (sequence === this.detailSequence) this.detailError = error.message }
      finally { if (sequence === this.detailSequence) this.detailLoading = false }
    },
    closeDetail() { this.detailSequence++; this.detailOpen = false; this.detail = null },
    async exportCsv() {
      this.exporting = true
      try {
        const blob = await http.get(`${endpoint}/export`, {params: {...this.applied}, responseType: 'blob', timeout: 60000})
        const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url
        link.download = `住院病历日志_${this.applied.dateFrom}_${this.applied.dateTo}.csv`
        document.body.appendChild(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000)
        this.$message.success('已导出当前筛选结果')
      } catch (error) { this.$message.error(error.message) }
      finally { this.exporting = false }
    }
  }
}
</script>
<style scoped>
.inpatient-log-page { grid-template-columns: minmax(0, 1fr); }
.inpatient-log-page > div, .inpatient-log-page > section { min-width: 0; }
.inpatient-log-page h1 { margin: 0; font-size: 17px; font-weight: 600; }
.inpatient-log-page__hint { margin-top: 6px; font-size: 12px; color: var(--color-text-secondary); }
.inpatient-log-page__bar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
.inpatient-log-page__meta { display: flex; flex-wrap: wrap; gap: 20px 32px; background: #fff; padding: 18px; margin-bottom: 16px; border: 1px solid var(--border-color-base); border-radius: 8px; }
.inpatient-log-page__meta > div { display: grid; gap: 6px; }
.inpatient-log-page__meta span { font-size: 12px; color: var(--color-text-secondary); }
.inpatient-log-page__meta strong { font-size: 13px; overflow-wrap: anywhere; }
.inpatient-log-page__meta .status-pill { justify-self: start; }
.inpatient-log-page__columns { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.3fr); gap: 16px; margin-top: 16px; }
.inpatient-log-page h2 { font-size: 13px; margin: 0 0 8px; line-height: 1.6; }
.inpatient-log-page h2 small { font-size: 11px; color: var(--color-text-secondary); font-weight: normal; }
.inpatient-log-page__source, .inpatient-log-page__field { margin-bottom: 20px; padding-bottom: 16px; border-bottom: 1px solid var(--border-color-base); }
.inpatient-log-page__text { display: block; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; font-size: 13px; }
.inpatient-log-page del { color: #9a3f3f; background: #fff2f2; padding: 8px; }
.inpatient-log-page ins { color: #17694a; background: #eefaf3; padding: 8px; text-decoration: none; }
.inpatient-log-page__timeline { margin-top: 16px; }
.inpatient-log-page__ids { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; }
.inpatient-log-page .el-alert { margin-bottom: 12px; }
@media(max-width: 1100px) { .inpatient-log-page__columns { grid-template-columns: minmax(0, 1fr); } }
</style>
