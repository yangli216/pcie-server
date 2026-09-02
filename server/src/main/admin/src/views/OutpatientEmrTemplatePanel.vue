<template>
  <div class="page-surface outpatient-template-panel">
    <section class="page-section page-section--padded page-section--toolbar outpatient-template-panel__toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model.trim="keyword"
          clearable
          placeholder="输入模板名称、主键或 hash"
          class="search-input outpatient-template-panel__keyword"
          @keyup.enter.native="search"
          @clear="search"
        />
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-button icon="el-icon-refresh" :loading="loading" @click="loadData">刷新</el-button>
    </section>

    <section class="page-section page-section--table">
      <el-table v-loading="loading" :data="records" aria-label="门诊病例模板列表">
        <template slot="empty">
          <div class="outpatient-template-empty">
            <span class="outpatient-template-empty__title">暂无门诊病例模板</span>
            <span>桌面端首次动态解析门诊模板后会自动登记；你也可以先检查病例预渲染效果。</span>
            <el-button type="text" icon="el-icon-view" @click="openPreviewExample">查看病例预渲染示例</el-button>
          </div>
        </template>
        <el-table-column label="模板名称" min-width="180">
          <template slot-scope="{ row }">{{ row.templateName || '未命名模板' }}</template>
        </el-table-column>
        <el-table-column label="模板主键" min-width="175">
          <template slot-scope="{ row }"><code-tag :value="row.templateId" /></template>
        </el-table-column>
        <el-table-column label="模板 hash" min-width="205">
          <template slot-scope="{ row }"><code-tag :value="shortHash(row.templateHash)" /></template>
        </el-table-column>
        <el-table-column label="字段统计" min-width="195">
          <template slot-scope="{ row }">
            <span class="field-stats">
              {{ row.fieldCount }} 总计 · {{ row.writableFieldCount }} 可写 ·
              {{ row.dictionaryFieldCount }} 字典 · {{ row.mappedFieldCount }} 自动映射
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最近设备" min-width="145">
          <template slot-scope="{ row }"><code-tag :value="row.cdDevice || row.idDevice" /></template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template slot-scope="{ row }">{{ formatTime(row.receivedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template slot-scope="{ row }">
            <div class="table-actions">
              <table-action label="病例预渲染" @click="openPreview(row)" />
              <table-action label="字段映射" @click="openMapping(row)" />
            </div>
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
      v-if="previewVisible"
      :title="previewTitle"
      :visible.sync="previewVisible"
      width="1080px"
      top="5vh"
      custom-class="outpatient-preview-dialog"
      @closed="resetPreview"
    >
      <div v-loading="previewLoading" class="outpatient-preview-dialog__body">
        <el-tabs v-if="previewDetail" v-model="previewTab">
          <el-tab-pane label="病例预渲染" name="preview">
            <outpatient-template-preview
              :template-html="previewDetail.templateHtml"
              :fields="previewFields"
              :example="previewIsExample"
            />
          </el-tab-pane>
          <el-tab-pane label="模板源码" name="source">
            <div class="source-toolbar">
              <el-radio-group v-model="sourceMode" size="small">
                <el-radio-button label="html">渲染 HTML</el-radio-button>
                <el-radio-button label="definition">结构定义 JSON</el-radio-button>
                <el-radio-button label="parse">完整解析 JSON</el-radio-button>
              </el-radio-group>
              <span>{{ sourceNote }}</span>
            </div>
            <el-input
              :value="sourceContent"
              type="textarea"
              :rows="22"
              readonly
              class="snapshot-code-input"
            />
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>

    <el-dialog
      v-if="mappingVisible"
      :title="mappingTitle"
      :visible.sync="mappingVisible"
      width="96vw"
      top="4vh"
      custom-class="outpatient-mapping-dialog"
      @closed="resetMapping"
    >
      <div v-loading="mappingLoading" class="outpatient-mapping-dialog__body">
        <template v-if="mappingDetail">
          <div class="mapping-summary">
            <div>
              <strong>动态解析字段 {{ mappingFields.length }} 个</strong>
              <span>
                自动映射 {{ automaticMappedCount }} · 人工维护 {{ mappingOverrides.length }} ·
                当前有效映射 {{ mappingDetail.mappedFieldCount }}
              </span>
            </div>
            <span>人工维护仅对当前模板版本生效；恢复后继续采用客户端自动解析结果。</span>
          </div>
          <outpatient-field-mapping-panel
            ref="mappingPanel"
            :fields="mappingFields"
            :mapping-overrides="mappingOverrides"
            :table-height="mappingTableHeight"
            @edit="openMappingEditor"
          />
        </template>
      </div>
    </el-dialog>

    <el-dialog
      v-if="mappingEditorVisible"
      :title="mappingEditorTitle"
      :visible.sync="mappingEditorVisible"
      append-to-body
      width="640px"
      custom-class="outpatient-mapping-editor"
      @closed="resetMappingEditor"
    >
      <template v-if="activeField">
        <div class="mapping-editor-field">
          <div>
            <span>字段 ID</span>
            <code-tag :value="activeField.id" />
          </div>
          <div>
            <span>字段名称</span>
            <strong>{{ activeField.name }}</strong>
          </div>
          <div>
            <span>所属章节</span>
            <strong>{{ activeField.articleName }} · {{ activeField.articleDefinitionName }}</strong>
          </div>
        </div>

        <div class="automatic-mapping-note">
          <span>客户端自动解析</span>
          <strong>{{ automaticMappingText }}</strong>
          <small>{{ automaticMappingSourceText }}</small>
        </div>

        <el-form label-position="top" class="mapping-editor-form">
          <el-form-item label="维护方式">
            <el-radio-group v-model="mappingForm.mappingStatus">
              <el-radio-button label="mapped">指定标准字段</el-radio-button>
              <el-radio-button label="unmapped">明确不映射</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <template v-if="mappingForm.mappingStatus === 'mapped'">
            <div class="mapping-editor-form__row">
              <el-form-item label="标准病例字段">
                <el-select v-model="mappingForm.recordField" filterable placeholder="选择标准病例字段">
                  <el-option
                    v-for="item in recordFieldOptions"
                    :key="item.value"
                    :label="`${item.label}（${item.value}）`"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="投影方式">
                <el-select v-model="mappingForm.projectionMode" placeholder="选择投影方式">
                  <el-option label="直接写入" value="direct" />
                  <el-option label="同章节字段组合" value="section-compose" />
                </el-select>
              </el-form-item>
            </div>
          </template>
          <el-alert
            v-else
            title="该字段将被明确排除，不参与门诊病例标准字段回填。"
            type="warning"
            :closable="false"
            show-icon
          />
        </el-form>
      </template>
      <span slot="footer" class="mapping-editor-footer">
        <el-button v-if="activeOverride" type="text" :loading="restoringMapping" @click="restoreAutomaticMapping">恢复自动映射</el-button>
        <span class="mapping-editor-footer__spacer"></span>
        <el-button @click="mappingEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingMapping" @click="saveMapping">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { CodeTag, TableAction } from '../components/ui'
import OutpatientFieldMappingPanel from '../components/outpatient-emr/OutpatientFieldMappingPanel.vue'
import OutpatientTemplatePreview from '../components/outpatient-emr/OutpatientTemplatePreview.vue'
import { outpatientPreviewExample } from '../utils/outpatientEmrPreview'
import {
  mappingSourceLabels,
  recordFieldOptions,
  requireSnapshotDetail,
  requireSnapshotPage
} from '../utils/outpatientEmrTemplateContract'

export default {
  name: 'OutpatientEmrTemplatePanel',
  components: {
    CodeTag,
    OutpatientFieldMappingPanel,
    OutpatientTemplatePreview,
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
      previewVisible: false,
      previewLoading: false,
      previewDetail: null,
      previewName: '',
      previewIsExample: false,
      previewTab: 'preview',
      sourceMode: 'html',
      mappingVisible: false,
      mappingLoading: false,
      mappingDetail: null,
      mappingName: '',
      mappingEditorVisible: false,
      activeField: null,
      mappingForm: {
        mappingStatus: 'mapped',
        recordField: '',
        projectionMode: 'direct'
      },
      savingMapping: false,
      restoringMapping: false,
      viewportHeight: window.innerHeight || 768,
      recordFieldOptions
    }
  },
  computed: {
    previewTitle() {
      return this.previewName ? `${this.previewName} - 病例预渲染` : '病例预渲染'
    },
    previewFields() {
      return this.previewDetail ? this.previewDetail.parseResult.fields : []
    },
    sourceContent() {
      if (!this.previewDetail) return ''
      if (this.sourceMode === 'definition') return this.previewDetail.templateDefinition
      if (this.sourceMode === 'parse') return JSON.stringify(this.previewDetail.parseResult, null, 2)
      return this.previewDetail.templateHtml
    },
    sourceNote() {
      if (this.sourceMode === 'definition') return '与渲染 HTML 配对的结构定义和字典，只读展示。'
      if (this.sourceMode === 'parse') return '客户端动态解析并叠加当前版本人工映射后的结果。'
      return 'HIS 实际传入的渲染实例，只读展示。'
    },
    mappingTitle() {
      return this.mappingName ? `${this.mappingName} - 字段映射` : '字段映射'
    },
    mappingFields() {
      return this.mappingDetail ? this.mappingDetail.parseResult.fields : []
    },
    mappingOverrides() {
      return this.mappingDetail ? this.mappingDetail.mappingOverrides : []
    },
    automaticMappedCount() {
      const overrideMap = this.mappingOverrides.reduce((result, item) => {
        result[item.fieldId] = item
        return result
      }, Object.create(null))
      return this.mappingFields.filter((field) => {
        const override = overrideMap[field.id]
        return override ? override.automaticRecordField : field.recordField
      }).length
    },
    mappingTableHeight() {
      return Math.max(340, Math.min(680, this.viewportHeight - 245))
    },
    activeOverride() {
      if (!this.activeField) return null
      return this.mappingOverrides.find(item => item.fieldId === this.activeField.id) || null
    },
    mappingEditorTitle() {
      return this.activeField ? `${this.activeField.name} - 字段映射维护` : '字段映射维护'
    },
    automaticMappingText() {
      if (!this.activeField) return '未映射'
      const recordField = this.activeOverride
        ? this.activeOverride.automaticRecordField
        : this.activeField.recordField
      const projectionMode = this.activeOverride
        ? this.activeOverride.automaticProjectionMode
        : this.activeField.projectionMode
      if (!recordField) return '未映射'
      return `${this.recordFieldLabel(recordField)} · ${this.projectionLabel(projectionMode)}`
    },
    automaticMappingSourceText() {
      if (!this.activeField) return ''
      const source = this.activeOverride
        ? this.activeOverride.automaticMappingSource
        : this.activeField.mappingSource
      return `来源：${mappingSourceLabels[source] || source || '未识别'}`
    }
  },
  mounted() {
    this.updateViewportHeight()
    window.addEventListener('resize', this.updateViewportHeight)
    this.loadData()
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.updateViewportHeight)
  },
  methods: {
    updateViewportHeight() {
      this.viewportHeight = window.innerHeight || 768
    },
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
        this.$message.error(error.message || '门诊病例模板加载失败')
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
    async fetchDetail(row) {
      return requireSnapshotDetail(
        await http.get(`/admin/api/outpatient-emr/templates/${row.id}`)
      )
    },
    async openPreview(row) {
      this.previewVisible = true
      this.previewLoading = true
      this.previewDetail = null
      this.previewName = row.templateName || '未命名模板'
      this.previewIsExample = false
      this.previewTab = 'preview'
      this.sourceMode = 'html'
      try {
        this.previewDetail = await this.fetchDetail(row)
      } catch (error) {
        this.previewVisible = false
        this.$message.error(error.message || '门诊病例模板读取失败')
      } finally {
        this.previewLoading = false
      }
    },
    openPreviewExample() {
      this.previewDetail = requireSnapshotDetail(outpatientPreviewExample)
      this.previewName = outpatientPreviewExample.templateName
      this.previewIsExample = true
      this.previewLoading = false
      this.previewTab = 'preview'
      this.sourceMode = 'html'
      this.previewVisible = true
    },
    resetPreview() {
      this.previewDetail = null
      this.previewName = ''
      this.previewIsExample = false
      this.previewTab = 'preview'
      this.sourceMode = 'html'
    },
    async openMapping(row) {
      this.mappingVisible = true
      this.mappingLoading = true
      this.mappingDetail = null
      this.mappingName = row.templateName || '未命名模板'
      try {
        this.mappingDetail = await this.fetchDetail(row)
      } catch (error) {
        this.mappingVisible = false
        this.$message.error(error.message || '门诊模板字段映射读取失败')
      } finally {
        this.mappingLoading = false
      }
    },
    resetMapping() {
      this.mappingDetail = null
      this.mappingName = ''
      this.resetMappingEditor()
    },
    openMappingEditor(field) {
      const override = this.mappingOverrides.find(item => item.fieldId === field.id) || null
      this.activeField = field
      this.mappingForm = {
        mappingStatus: override ? override.mappingStatus : (field.recordField ? 'mapped' : 'unmapped'),
        recordField: (override ? override.recordField : field.recordField) || '',
        projectionMode: (override ? override.projectionMode : field.projectionMode) || 'direct'
      }
      this.mappingEditorVisible = true
    },
    resetMappingEditor() {
      this.activeField = null
      this.mappingForm = {
        mappingStatus: 'mapped',
        recordField: '',
        projectionMode: 'direct'
      }
      this.savingMapping = false
      this.restoringMapping = false
    },
    async saveMapping() {
      if (!this.mappingDetail || !this.activeField) return
      if (this.mappingForm.mappingStatus === 'mapped' && !this.mappingForm.recordField) {
        this.$message.warning('请选择标准病例字段')
        return
      }
      this.savingMapping = true
      try {
        const mapped = this.mappingForm.mappingStatus === 'mapped'
        this.mappingDetail = requireSnapshotDetail(await http.put(
          `/admin/api/outpatient-emr/templates/${this.mappingDetail.id}/mapping`,
          {
            fieldId: this.activeField.id,
            mappingStatus: this.mappingForm.mappingStatus,
            recordField: mapped ? this.mappingForm.recordField : null,
            projectionMode: mapped ? this.mappingForm.projectionMode : null
          }
        ))
        this.mappingEditorVisible = false
        this.$message.success('字段映射已保存，并对当前模板版本生效')
        await this.loadData()
      } catch (error) {
        this.$message.error(error.message || '字段映射保存失败')
      } finally {
        this.savingMapping = false
      }
    },
    async restoreAutomaticMapping() {
      if (!this.mappingDetail || !this.activeField || !this.activeOverride) return
      this.restoringMapping = true
      try {
        this.mappingDetail = requireSnapshotDetail(await http.delete(
          `/admin/api/outpatient-emr/templates/${this.mappingDetail.id}/mapping`,
          { params: { fieldId: this.activeField.id } }
        ))
        this.mappingEditorVisible = false
        this.$message.success('已恢复客户端自动映射')
        await this.loadData()
      } catch (error) {
        this.$message.error(error.message || '恢复自动映射失败')
      } finally {
        this.restoringMapping = false
      }
    },
    recordFieldLabel(value) {
      const item = recordFieldOptions.find(option => option.value === value)
      return item ? `${item.label}（${item.value}）` : value || '未映射'
    },
    projectionLabel(value) {
      if (value === 'direct') return '直接写入'
      if (value === 'section-compose') return '章节组合'
      return '无投影'
    },
    shortHash(value) {
      const text = String(value)
      return text.length > 18 ? `${text.slice(0, 10)}…${text.slice(-6)}` : text
    },
    formatTime(value) {
      if (!value) return '-'
      return new Date(value).toLocaleString()
    }
  }
}
</script>

<style scoped>
.outpatient-template-panel__toolbar {
  padding-top: 16px;
  padding-bottom: 16px;
}

.outpatient-template-panel__keyword {
  width: 300px;
}

.field-stats {
  color: var(--color-text-secondary);
  font-size: 12px;
  white-space: nowrap;
}

.outpatient-template-empty {
  min-height: 150px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 7px;
  color: #8a9693;
  line-height: 1.5;
}

.outpatient-template-empty__title {
  color: #53605d;
  font-size: 15px;
  font-weight: 600;
}

.outpatient-preview-dialog__body,
.outpatient-mapping-dialog__body {
  min-height: 120px;
}

.source-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.snapshot-code-input ::v-deep textarea {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.55;
}

.mapping-summary {
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid #dcebe6;
  border-radius: 8px;
  background: #f7fbf9;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.mapping-summary > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.mapping-summary strong {
  color: #253b35;
  font-size: 14px;
  font-weight: 600;
}

.mapping-editor-field {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  margin-bottom: 14px;
  border: 1px solid var(--border-color-base);
  border-radius: 8px;
  background: var(--border-color-light);
}

.mapping-editor-field > div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 10px 12px;
  background: #fff;
}

.mapping-editor-field > div:last-child {
  grid-column: 1 / -1;
}

.mapping-editor-field span,
.automatic-mapping-note span,
.automatic-mapping-note small {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.mapping-editor-field strong,
.automatic-mapping-note strong {
  overflow: hidden;
  color: var(--color-text-primary);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.automatic-mapping-note {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  padding: 10px 12px;
  border-left: 3px solid #33a77a;
  background: #f4faf7;
}

.mapping-editor-form__row {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr);
  gap: 12px;
}

.mapping-editor-form__row .el-select {
  width: 100%;
}

.mapping-editor-footer {
  width: 100%;
  display: flex;
  align-items: center;
}

.mapping-editor-footer__spacer {
  flex: 1;
}

::v-deep .outpatient-preview-dialog {
  max-width: 94vw;
}

::v-deep .outpatient-preview-dialog .el-dialog__body {
  padding: 10px 20px 20px;
}

::v-deep .outpatient-preview-dialog .source-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

::v-deep .outpatient-preview-dialog .snapshot-code-input textarea {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.55;
}

::v-deep .outpatient-mapping-dialog {
  max-width: 1440px;
}

::v-deep .outpatient-mapping-dialog .el-dialog__body {
  max-height: calc(92vh - 54px);
  padding: 12px 16px 16px;
  overflow: hidden;
}

::v-deep .outpatient-mapping-dialog .mapping-summary {
  min-height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 12px;
  padding: 10px 14px;
  border: 1px solid #dcebe6;
  border-radius: 8px;
  background: #f7fbf9;
  color: var(--color-text-secondary);
  font-size: 12px;
}

::v-deep .outpatient-mapping-dialog .mapping-summary > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

::v-deep .outpatient-mapping-dialog .mapping-summary strong {
  color: #253b35;
  font-size: 14px;
  font-weight: 600;
}

::v-deep .outpatient-mapping-editor .mapping-editor-field {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  margin-bottom: 14px;
  border: 1px solid var(--border-color-base);
  border-radius: 8px;
  background: var(--border-color-light);
}

::v-deep .outpatient-mapping-editor .mapping-editor-field > div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 10px 12px;
  background: #fff;
}

::v-deep .outpatient-mapping-editor .mapping-editor-field > div:last-child {
  grid-column: 1 / -1;
}

::v-deep .outpatient-mapping-editor .mapping-editor-field span,
::v-deep .outpatient-mapping-editor .automatic-mapping-note span,
::v-deep .outpatient-mapping-editor .automatic-mapping-note small {
  color: var(--color-text-secondary);
  font-size: 12px;
}

::v-deep .outpatient-mapping-editor .mapping-editor-field strong,
::v-deep .outpatient-mapping-editor .automatic-mapping-note strong {
  overflow: hidden;
  color: var(--color-text-primary);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

::v-deep .outpatient-mapping-editor .automatic-mapping-note {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  padding: 10px 12px;
  border-left: 3px solid #33a77a;
  background: #f4faf7;
}

::v-deep .outpatient-mapping-editor .mapping-editor-form__row {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr);
  gap: 12px;
}

::v-deep .outpatient-mapping-editor .mapping-editor-form__row .el-select {
  width: 100%;
}

::v-deep .outpatient-mapping-editor .mapping-editor-footer {
  width: 100%;
  display: flex;
  align-items: center;
}

::v-deep .outpatient-mapping-editor .mapping-editor-footer__spacer {
  flex: 1;
}

@media (max-width: 900px) {
  .source-toolbar,
  .mapping-summary {
    align-items: flex-start;
    flex-direction: column;
  }

  .automatic-mapping-note {
    grid-template-columns: 1fr;
  }
}
</style>
