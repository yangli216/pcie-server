<template>
  <div class="page-surface outpatient-template-panel">
    <admin-filter-bar>
      <div class="filter-row outpatient-template-panel__filters">
        <div class="filter-item outpatient-template-panel__keyword">
          <div class="filter-label">搜索</div>
          <el-input
            v-model.trim="keyword"
            size="small"
            clearable
            placeholder="模板名称、主键或 hash"
            @keyup.enter.native="search"
            @clear="search"
          />
        </div>
        <div class="filter-actions">
          <el-button type="primary" size="small" icon="el-icon-search" @click="search">查询</el-button>
          <el-button size="small" @click="reset">重置</el-button>
        </div>
      </div>
      <template #actions>
        <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="loadData">刷新</el-button>
      </template>
    </admin-filter-bar>

    <section class="page-section page-section--table">
      <div class="page-section__header outpatient-template-panel__header">
        <div>
          <div class="page-section__title">门诊模板解析记录</div>
          <div class="outpatient-template-panel__hint">
            展示正式 HIS 调用在模型分析前登记的渲染 HTML、结构定义 JSON 和确定性合并解析结果，不包含患者、病历上下文或生成值。
          </div>
        </div>
        <span class="outpatient-template-panel__count">共 {{ total }} 个模板版本</span>
      </div>

      <el-table
        v-loading="loading"
        :data="records"
        aria-label="门诊模板解析记录列表"
      >
        <el-table-column label="模板名称" min-width="170">
          <template slot-scope="{ row }">{{ row.templateName }}</template>
        </el-table-column>
        <el-table-column label="模板主键" min-width="160">
          <template slot-scope="{ row }"><code-tag :value="row.templateId" /></template>
        </el-table-column>
        <el-table-column label="字段统计" min-width="175">
          <template slot-scope="{ row }">
            <span class="field-stats">
              {{ row.fieldCount }} 总计 · {{ row.writableFieldCount }} 可写 ·
              {{ row.dictionaryFieldCount }} 字典 · {{ row.mappedFieldCount }} 映射
            </span>
          </template>
        </el-table-column>
        <el-table-column label="模板 hash" min-width="210">
          <template slot-scope="{ row }"><code-tag :value="shortHash(row.templateHash)" /></template>
        </el-table-column>
        <el-table-column label="最近设备" min-width="135">
          <template slot-scope="{ row }"><code-tag :value="row.cdDevice || row.idDevice" /></template>
        </el-table-column>
        <el-table-column label="最近接收" width="180">
          <template slot-scope="{ row }">{{ formatTime(row.receivedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template slot-scope="{ row }">
            <table-action label="查看详情" @click="openDetail(row)" />
          </template>
        </el-table-column>
      </el-table>

      <div class="page-footer">
        <AdminPagination
          :current.sync="current"
          :size.sync="size"
          :total="total"
          @change="loadData"
        />
      </div>
    </section>

    <el-dialog
      v-if="detailVisible"
      :title="detailTitle"
      :visible.sync="detailVisible"
      width="94vw"
      top="4vh"
      custom-class="outpatient-template-detail"
      @closed="resetDetail"
    >
      <div v-loading="detailLoading" class="outpatient-template-detail__body">
        <div v-if="detail" class="snapshot-summary">
          <div class="snapshot-summary__item">
            <span>模板主键</span>
            <code-tag :value="detail.templateId" />
          </div>
          <div class="snapshot-summary__item">
            <span>模板 hash</span>
            <code-tag :value="detail.templateHash" />
          </div>
          <div class="snapshot-summary__item">
            <span>解析统计</span>
            <strong>
              {{ detail.fieldCount }} 总计 / {{ detail.writableFieldCount }} 可写 /
              {{ detail.dictionaryFieldCount }} 字典 / {{ detail.mappedFieldCount }} 映射
            </strong>
          </div>
          <div class="snapshot-summary__item">
            <span>最近接收设备</span>
            <code-tag :value="detail.cdDevice || detail.idDevice" />
          </div>
          <div class="snapshot-summary__item">
            <span>最近接收时间</span>
            <strong>{{ formatTime(detail.receivedAt) }}</strong>
          </div>
        </div>

        <el-tabs v-if="detail" v-model="detailTab" class="snapshot-tabs">
          <el-tab-pane label="解析字段" name="fields">
            <div class="field-toolbar">
              <el-input
                v-model.trim="fieldKeyword"
                size="small"
                clearable
                placeholder="搜索字段 ID、名称、章节或映射"
                prefix-icon="el-icon-search"
              />
              <span>显示 {{ filteredFields.length }} / {{ detailFields.length }} 个字段</span>
            </div>
            <el-table :data="filteredFields" height="470" aria-label="门诊模板解析字段">
              <el-table-column label="字段 ID" min-width="170" show-overflow-tooltip>
                <template slot-scope="{ row }"><code-tag :value="row.id" /></template>
              </el-table-column>
              <el-table-column prop="name" label="字段名称" min-width="140" show-overflow-tooltip />
              <el-table-column label="所属章节" min-width="160" show-overflow-tooltip>
                <template slot-scope="{ row }">
                  {{ row.articleName }} / {{ row.articleDefinitionName }}
                </template>
              </el-table-column>
              <el-table-column prop="type" label="类型" width="105" />
              <el-table-column label="属性" width="150">
                <template slot-scope="{ row }">
                  <div class="field-flags">
                    <status-pill :tone="row.readonly ? 'muted' : 'success'" :label="row.readonly ? '只读' : '可写'" />
                    <status-pill :tone="row.aiSuitable ? 'success' : 'muted'" :label="row.aiSuitable ? 'AI' : '非AI'" />
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="字典项" width="85">
                <template slot-scope="{ row }">{{ row.dictionaryItems.length }}</template>
              </el-table-column>
              <el-table-column label="标准字段映射" min-width="170" show-overflow-tooltip>
                <template slot-scope="{ row }"><code-tag :value="row.recordField || '未映射'" /></template>
              </el-table-column>
              <el-table-column label="映射来源 / 投影" min-width="190" show-overflow-tooltip>
                <template slot-scope="{ row }">{{ row.mappingSource }} / {{ row.projectionMode || '--' }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="渲染 HTML" name="html">
            <div class="source-note">按文本查看客户端实际传入的渲染实例；后台不会执行脚本或渲染 HTML。</div>
            <el-input
              :value="detail.templateHtml"
              type="textarea"
              :rows="24"
              readonly
              class="snapshot-code-input"
            />
          </el-tab-pane>
          <el-tab-pane label="结构定义 JSON" name="definition">
            <div class="source-note">按文本查看与渲染实例配对的完整结构定义和字典。</div>
            <el-input
              :value="detail.templateDefinition"
              type="textarea"
              :rows="24"
              readonly
              class="snapshot-code-input"
            />
          </el-tab-pane>
          <el-tab-pane label="完整解析 JSON" name="json">
            <el-input
              :value="parseResultJson"
              type="textarea"
              :rows="25"
              readonly
              class="snapshot-code-input"
            />
          </el-tab-pane>
        </el-tabs>
      </div>
      <span slot="footer">
        <el-button @click="detailVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { AdminFilterBar, CodeTag, StatusPill, TableAction } from '../components/ui'

const recordFields = new Set([
  'chiefComplaint',
  'historyOfPresentIllness',
  'pastMedicalHistory',
  'personalHistory',
  'menstrualHistory',
  'familyHistory',
  'physicalExam',
  'precautions'
])
const mappingSources = new Set([
  'definition-record-field',
  'definition-article-record-field',
  'canonical-id',
  'deterministic-alias',
  'deterministic-article',
  'unmapped'
])
const countFields = [
  'fieldCount',
  'writableFieldCount',
  'dictionaryFieldCount',
  'mappedFieldCount'
]

function isExactText(value, allowEmpty = false) {
  return typeof value === 'string' &&
    (allowEmpty || value.length > 0) &&
    value === value.trim()
}

function isNullableExactText(value) {
  return value === null || isExactText(value)
}

function isSnapshotSummary(value) {
  return Boolean(
    value &&
    isExactText(value.id) &&
    isExactText(value.idOrg) &&
    isNullableExactText(value.idRegion) &&
    isExactText(value.templateId) &&
    isExactText(value.templateName) &&
    typeof value.templateHash === 'string' && /^[a-f0-9]{64}$/.test(value.templateHash) &&
    isExactText(value.idDevice) &&
    isNullableExactText(value.cdDevice) &&
    Number.isInteger(value.receivedAt) &&
    Number.isInteger(value.createdAt) &&
    Number.isInteger(value.updatedAt) &&
    countFields.every(field => Number.isInteger(value[field]) && value[field] >= 0) &&
    value.fieldCount > 0 &&
    value.writableFieldCount <= value.fieldCount &&
    value.dictionaryFieldCount <= value.fieldCount &&
    value.mappedFieldCount <= value.fieldCount
  )
}

function requireSnapshotPage(data) {
  if (
    !data ||
    !Number.isInteger(data.current) ||
    data.current < 1 ||
    !Number.isInteger(data.size) ||
    data.size < 1 ||
    !Array.isArray(data.records) ||
    !data.records.every(isSnapshotSummary) ||
    !Number.isInteger(data.total) ||
    data.total < 0
  ) {
    throw new Error('门诊模板解析记录响应不符合当前接口协议')
  }
  return data
}

function isDictionaryItems(items) {
  if (!Array.isArray(items)) return false
  const tokens = new Set()
  return items.every(item => {
    if (!item || !isExactText(item.value, true) || !isExactText(item.text)) return false
    const itemTokens = item.value === item.text ? [item.value] : [item.value, item.text]
    if (itemTokens.some(token => tokens.has(token))) return false
    itemTokens.forEach(token => tokens.add(token))
    return true
  })
}

function isSnapshotField(field) {
  if (
    !field ||
    !isExactText(field.id) ||
    !isExactText(field.name) ||
    !isExactText(field.type) ||
    !isExactText(field.articleTemplateId) ||
    !isExactText(field.articleId) ||
    !isExactText(field.articleName) ||
    !isExactText(field.articleDefinitionName) ||
    typeof field.readonly !== 'boolean' ||
    typeof field.aiSuitable !== 'boolean' ||
    !isExactText(field.baselineValue, true) ||
    !isExactText(field.baselineDictionaryValue, true) ||
    !isDictionaryItems(field.dictionaryItems) ||
    !isNullableExactText(field.recordField) ||
    !mappingSources.has(field.mappingSource) ||
    !isNullableExactText(field.projectionMode)
  ) return false

  if (field.recordField === null) {
    return field.mappingSource === 'unmapped' && field.projectionMode === null
  }
  return recordFields.has(field.recordField) &&
    field.mappingSource !== 'unmapped' &&
    (field.projectionMode === 'direct' || field.projectionMode === 'section-compose')
}

function requireSnapshotDetail(data) {
  if (
    !isSnapshotSummary(data) ||
    typeof data.templateHtml !== 'string' ||
    !data.templateHtml.trim() ||
    typeof data.templateDefinition !== 'string' ||
    !data.templateDefinition.trim() ||
    !data.parseResult ||
    data.parseResult.schemaVersion !== 'outpatient-emr-template-pair.v1' ||
    !Array.isArray(data.parseResult.fields) ||
    data.parseResult.fields.length !== data.fieldCount ||
    !data.parseResult.fields.every(isSnapshotField) ||
    new Set(data.parseResult.fields.map(field => field.id)).size !== data.fieldCount ||
    data.parseResult.fields.filter(field => !field.readonly).length !== data.writableFieldCount ||
    data.parseResult.fields.filter(field => field.dictionaryItems.length > 0).length !== data.dictionaryFieldCount ||
    data.parseResult.fields.filter(field => field.recordField !== null).length !== data.mappedFieldCount
  ) {
    throw new Error('门诊模板解析详情响应不符合当前接口协议')
  }
  return data
}

export default {
  name: 'OutpatientEmrTemplatePanel',
  components: {
    AdminFilterBar,
    CodeTag,
    StatusPill,
    TableAction
  },
  data() {
    return {
      loading: false,
      keyword: '',
      current: 1,
      size: 10,
      total: 0,
      records: [],
      detailVisible: false,
      detailLoading: false,
      detail: null,
      detailTab: 'fields',
      fieldKeyword: ''
    }
  },
  computed: {
    detailTitle() {
      return this.detail ? `${this.detail.templateName} - 传入模板与解析结果` : '传入模板与解析结果'
    },
    detailFields() {
      return this.detail ? this.detail.parseResult.fields : []
    },
    filteredFields() {
      const keyword = String(this.fieldKeyword || '').toLowerCase()
      if (!keyword) return this.detailFields
      return this.detailFields.filter(field => [
        field.id,
        field.name,
        field.type,
        field.articleTemplateId,
        field.articleId,
        field.articleName,
        field.articleDefinitionName,
        field.recordField,
        field.mappingSource,
        field.projectionMode
      ].filter(Boolean).join(' ').toLowerCase().indexOf(keyword) > -1)
    },
    parseResultJson() {
      return this.detail && this.detail.parseResult
        ? JSON.stringify(this.detail.parseResult, null, 2)
        : ''
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      try {
        const data = requireSnapshotPage(await http.get('/admin/api/outpatient-emr/templates', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.keyword || undefined
          }
        }))
        this.records = data.records
        this.total = data.total
      } catch (error) {
        this.$message.error(error.message || '门诊模板解析记录加载失败')
      } finally {
        this.loading = false
      }
    },
    search() {
      this.current = 1
      this.loadData()
    },
    reset() {
      this.keyword = ''
      this.current = 1
      this.loadData()
    },
    async openDetail(row) {
      this.detailVisible = true
      this.detailLoading = true
      this.detail = null
      this.detailTab = 'fields'
      this.fieldKeyword = ''
      try {
        this.detail = requireSnapshotDetail(
          await http.get(`/admin/api/outpatient-emr/templates/${row.id}`)
        )
      } catch (error) {
        this.detailVisible = false
        this.$message.error(error.message || '门诊模板解析详情加载失败')
      } finally {
        this.detailLoading = false
      }
    },
    resetDetail() {
      this.detail = null
      this.detailTab = 'fields'
      this.fieldKeyword = ''
    },
    shortHash(value) {
      const text = String(value)
      return text.length > 18 ? `${text.slice(0, 10)}…${text.slice(-6)}` : text
    },
    formatTime(value) {
      return new Date(value).toLocaleString()
    }
  }
}
</script>

<style scoped>
.outpatient-template-panel__filters {
  flex-wrap: wrap;
}

.outpatient-template-panel__keyword {
  min-width: 280px;
}

.outpatient-template-panel__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.outpatient-template-panel__hint,
.outpatient-template-panel__count,
.field-toolbar,
.source-note {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.outpatient-template-panel__hint {
  margin-top: 5px;
}

.field-stats {
  color: var(--color-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.outpatient-template-detail__body {
  min-height: 520px;
}

.snapshot-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  margin-bottom: 16px;
  border: 0.5px solid var(--border-color-base);
  border-radius: 8px;
  background: var(--border-color-light);
}

.snapshot-summary__item {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 12px 14px;
  background: #fff;
}

.snapshot-summary__item > span {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.snapshot-summary__item > strong {
  overflow: hidden;
  color: var(--color-text-primary);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.field-toolbar .el-input {
  width: 360px;
}

.field-flags {
  display: flex;
  align-items: center;
  gap: 6px;
}

.source-note {
  margin-bottom: 10px;
}

.snapshot-code-input ::v-deep textarea {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.55;
}

@media (max-width: 1100px) {
  .snapshot-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
