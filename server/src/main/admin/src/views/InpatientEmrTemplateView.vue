<template>
  <div>
    <div class="filter-bar">
      <div class="page-toolbar__filters">
        <el-input
          v-model.trim="keyword"
          clearable
          placeholder="输入模板名称、主键或 hash"
          class="search-input"
          @keyup.enter.native="loadData"
        />
        <el-select v-model="sdStatus" clearable placeholder="状态" class="filter-select">
          <el-option label="启用" value="1" />
          <el-option label="停用" value="0" />
        </el-select>
        <el-button type="primary" icon="el-icon-search" @click="loadData">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </div>

    <div class="page-card">
      <el-table :data="records" v-loading="loading">
        <el-table-column prop="templateName" label="模板名称" min-width="180">
          <template slot-scope="{ row }">{{ row.templateName || '未命名模板' }}</template>
        </el-table-column>
        <el-table-column prop="templateId" label="模板主键" min-width="180">
          <template slot-scope="{ row }">
            <code-tag :value="row.templateId" />
          </template>
        </el-table-column>
        <el-table-column prop="templateHash" label="模板 hash" min-width="220">
          <template slot-scope="{ row }">
            <code-tag :value="row.templateHash" />
          </template>
        </el-table-column>
        <el-table-column prop="fieldCount" label="字段数" width="90" />
        <el-table-column label="状态" width="90">
          <template slot-scope="{ row }">
            <status-pill :tone="row.sdStatus === '1' ? 'success' : 'muted'" :label="row.sdStatus === '1' ? '启用' : '停用'" />
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template slot-scope="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template slot-scope="{ row }">
            <div class="table-actions">
              <table-action label="查看模板" @click="openTemplateViewer(row)" />
              <table-action label="字段维护" @click="openDetail(row)" />
              <table-action v-if="row.sdStatus !== '1'" label="启用" @click="enableRecord(row)" />
              <table-action v-else label="停用" @click="disableRecord(row)" />
              <table-action label="删除" danger @click="removeRecord(row)" />
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
    </div>

    <el-dialog
      v-if="detailVisible"
      :title="detailTitle"
      :visible.sync="detailVisible"
      width="96vw"
      top="4vh"
      custom-class="inpatient-field-dialog"
      @closed="resetDetail"
    >
      <div class="field-filter-bar">
        <el-input
          v-model.trim="fieldKeyword"
          clearable
          placeholder="搜索 data-id、字段含义或提示词…"
          class="field-filter-bar__search"
        />
        <el-select v-model="fieldGenerationFilter" placeholder="生成类型" class="field-filter-bar__select">
          <el-option label="全部类型" value="all" />
          <el-option label="AI生成" value="ai" />
          <el-option label="人工/HIS" value="manual" />
        </el-select>
        <el-select v-model="fieldPromptFilter" placeholder="提示词状态" class="field-filter-bar__select">
          <el-option label="全部提示词" value="all" />
          <el-option label="已维护" value="custom" />
          <el-option label="默认" value="default" />
          <el-option label="非AI" value="not_ai" />
        </el-select>
        <div class="field-filter-bar__quick">
          <span>常用过滤</span>
          <el-button size="mini" @click="applyFieldQuickFilter('all')">全部</el-button>
          <el-button size="mini" @click="applyFieldQuickFilter('ai')">AI生成</el-button>
          <el-button size="mini" @click="applyFieldQuickFilter('manual')">人工/HIS</el-button>
          <el-button size="mini" @click="applyFieldQuickFilter('custom')">已维护</el-button>
          <el-button size="mini" @click="applyFieldQuickFilter('pending')">待维护</el-button>
        </div>
      </div>

      <el-table :data="detailFields" :height="fieldTableHeight" class="field-table">
        <el-table-column prop="id" label="data-id" width="160" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <code-tag class="field-code-tag" :value="row.id" />
          </template>
        </el-table-column>
        <el-table-column prop="meaning" label="字段含义" min-width="220" show-overflow-tooltip />
        <el-table-column label="AI生成" width="82">
          <template slot-scope="{ row }">
            <status-pill :tone="row.aiSuitable ? 'success' : 'muted'" :label="row.aiSuitable ? 'AI' : '人工'" />
          </template>
        </el-table-column>
        <el-table-column label="提示词" min-width="300" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <div class="prompt-cell">
              <span class="prompt-cell__text">{{ promptSummary(row) }}</span>
              <el-tag size="mini" :type="promptSourceType(row)">{{ promptSourceLabel(row) }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="76">
          <template slot-scope="{ row }">
            <table-action label="维护" @click="openPrompt(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog
      v-if="promptDialogVisible"
      title="维护字段提示词"
      :visible.sync="promptDialogVisible"
      width="680px"
    >
      <el-form label-position="top">
        <el-form-item label="字段">
          <el-input :value="activeField && activeField.id" disabled />
        </el-form-item>
        <el-form-item label="生成类型">
          <segmented-switch v-model="activeFieldAiSuitable" :options="generationOptions" />
        </el-form-item>
        <el-form-item label="当前生效提示词">
          <el-input :value="resolvePrompt(activeField)" type="textarea" :rows="6" readonly />
        </el-form-item>
        <el-form-item label="生成指令">
          <el-input v-model="promptGeneratorInstruction" type="textarea" :rows="5" :disabled="!activeFieldAiSuitable" />
          <div class="prompt-generator-actions">
            <el-button icon="el-icon-magic-stick" :disabled="!activeFieldAiSuitable" :loading="generatingPrompt" @click="generatePrompt">自动生成</el-button>
          </div>
        </el-form-item>
        <el-form-item label="已维护提示词">
          <el-input v-model="promptText" type="textarea" :rows="8" :disabled="!activeFieldAiSuitable" placeholder="为空时使用字段规则生成的默认提示词…" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="promptDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingPrompt" @click="savePrompt">保存</el-button>
      </span>
    </el-dialog>

    <el-dialog
      v-if="templateViewerVisible"
      :title="templateViewerTitle"
      :visible.sync="templateViewerVisible"
      width="1080px"
      top="5vh"
      @closed="resetTemplateViewer"
    >
      <el-tabs v-model="templateViewMode">
        <el-tab-pane label="HTML预览" name="preview">
          <iframe
            class="template-preview-frame"
            sandbox=""
            :srcdoc="templateViewerHtml"
            title="病历模板HTML预览"
          ></iframe>
        </el-tab-pane>
        <el-tab-pane label="源码" name="source">
          <el-input
            :value="templateViewerHtml"
            type="textarea"
            :rows="22"
            readonly
            class="template-source-input"
          />
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { truncate } from '../utils/admin'
import { CodeTag, SegmentedSwitch, StatusPill, TableAction } from '../components/ui'

export default {
  components: {
    CodeTag,
    SegmentedSwitch,
    StatusPill,
    TableAction
  },
  data() {
    return {
      loading: false,
      keyword: '',
      sdStatus: '',
      current: 1,
      size: 10,
      total: 0,
      records: [],
      detailVisible: false,
      selectedRecord: null,
      templateViewerVisible: false,
      templateViewerRecord: null,
      templateViewMode: 'preview',
      promptDialogVisible: false,
      activeField: null,
      activeFieldAiSuitable: false,
      promptText: '',
      promptGeneratorInstruction: '',
      fieldKeyword: '',
      fieldGenerationFilter: 'all',
      fieldPromptFilter: 'all',
      savingPrompt: false,
      generatingPrompt: false,
      viewportHeight: window.innerHeight || 768,
      generationOptions: [
        { label: 'AI', value: true },
        { label: '人工', value: false }
      ]
    }
  },
  computed: {
    detailTitle() {
      return this.selectedRecord ? `${this.selectedRecord.templateName || '未命名模板'} - 字段维护` : '字段维护'
    },
    allDetailFields() {
      return this.selectedRecord && Array.isArray(this.selectedRecord.fields) ? this.selectedRecord.fields : []
    },
    detailFields() {
      const keyword = (this.fieldKeyword || '').toLowerCase()
      return this.allDetailFields.filter((field) => {
        if (this.fieldGenerationFilter === 'ai' && !field.aiSuitable) return false
        if (this.fieldGenerationFilter === 'manual' && field.aiSuitable) return false
        const promptSource = field && field.rule && field.rule.promptSource ? field.rule.promptSource : (field.aiSuitable ? 'default' : 'not_ai')
        if (this.fieldPromptFilter !== 'all' && promptSource !== this.fieldPromptFilter) return false
        if (!keyword) return true
        const haystack = [
          field.id,
          field.name,
          field.meaning,
          this.resolvePrompt(field),
          promptSource
        ].filter(Boolean).join(' ').toLowerCase()
        return haystack.indexOf(keyword) > -1
      })
    },
    fieldTableHeight() {
      return Math.max(320, Math.min(680, this.viewportHeight - 245))
    },
    templateViewerTitle() {
      return this.templateViewerRecord ? `${this.templateViewerRecord.templateName || '未命名模板'} - 模板查看` : '模板查看'
    },
    templateViewerHtml() {
      return this.templateViewerRecord && this.templateViewerRecord.htmlContent ? this.templateViewerRecord.htmlContent : ''
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
    truncate,
    updateViewportHeight() {
      this.viewportHeight = window.innerHeight || 768
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/inpatient-emr/templates', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.keyword || undefined,
            sdStatus: this.sdStatus || undefined
          }
        })
        this.records = data.records || []
        this.total = data.total || 0
      } catch (error) {
        this.$message.error(error.message || '加载失败')
      } finally {
        this.loading = false
      }
    },
    reset() {
      this.keyword = ''
      this.sdStatus = ''
      this.current = 1
      this.loadData()
    },
    async openDetail(row) {
      try {
        this.selectedRecord = await http.get(`/admin/api/inpatient-emr/templates/${row.id}`)
        this.detailVisible = true
      } catch (error) {
        this.$message.error(error.message || '读取模板缓存失败')
      }
    },
    async openTemplateViewer(row) {
      try {
        this.templateViewerRecord = await http.get(`/admin/api/inpatient-emr/templates/${row.id}`)
        this.templateViewMode = 'preview'
        this.templateViewerVisible = true
      } catch (error) {
        this.$message.error(error.message || '读取模板源码失败')
      }
    },
    resetDetail() {
      this.selectedRecord = null
      this.activeField = null
      this.activeFieldAiSuitable = false
      this.promptText = ''
      this.promptGeneratorInstruction = ''
      this.fieldKeyword = ''
      this.fieldGenerationFilter = 'all'
      this.fieldPromptFilter = 'all'
    },
    resetTemplateViewer() {
      this.templateViewerRecord = null
      this.templateViewMode = 'preview'
    },
    openPrompt(field) {
      if (!field) return
      this.activeField = field
      this.activeFieldAiSuitable = Boolean(field.aiSuitable)
      this.promptText = this.customPrompt(field)
      this.promptGeneratorInstruction = this.generatorInstruction(field)
      this.promptDialogVisible = true
    },
    resolvePrompt(field) {
      if (!field || !field.rule) return ''
      if (field.rule.promptSource === 'not_ai') return ''
      return field.rule.prompt || field.rule.resolvedPrompt || ''
    },
    customPrompt(field) {
      return field && field.rule && field.rule.prompt ? field.rule.prompt : ''
    },
    generatorInstruction(field) {
      return field && field.rule && field.rule.promptGeneratorInstruction ? field.rule.promptGeneratorInstruction : ''
    },
    promptSourceLabel(field) {
      if (field && field.rule && field.rule.promptSource === 'not_ai') return '非AI'
      return field && field.rule && field.rule.promptSource === 'custom' ? '已维护' : '默认'
    },
    promptSourceType(field) {
      if (field && field.rule && field.rule.promptSource === 'not_ai') return 'info'
      return field && field.rule && field.rule.promptSource === 'custom' ? 'success' : 'info'
    },
    promptSummary(field) {
      return this.truncate(this.resolvePrompt(field), 96) || '-'
    },
    applyFieldQuickFilter(type) {
      this.fieldKeyword = ''
      if (type === 'ai') {
        this.fieldGenerationFilter = 'ai'
        this.fieldPromptFilter = 'all'
      } else if (type === 'manual') {
        this.fieldGenerationFilter = 'manual'
        this.fieldPromptFilter = 'all'
      } else if (type === 'custom') {
        this.fieldGenerationFilter = 'ai'
        this.fieldPromptFilter = 'custom'
      } else if (type === 'pending') {
        this.fieldGenerationFilter = 'ai'
        this.fieldPromptFilter = 'default'
      } else {
        this.fieldGenerationFilter = 'all'
        this.fieldPromptFilter = 'all'
      }
    },
    findDetailField(fieldId) {
      return this.allDetailFields.find(field => field.id === fieldId) || null
    },
    async saveActiveFieldGeneration() {
      if (!this.selectedRecord || !this.activeField) return
      const nextValue = Boolean(this.activeFieldAiSuitable)
      if (nextValue === Boolean(this.activeField.aiSuitable)) return
      this.selectedRecord = await http.put(
        `/admin/api/inpatient-emr/templates/${this.selectedRecord.id}/fields/${encodeURIComponent(this.activeField.id)}/generation`,
        { aiSuitable: nextValue }
      )
      this.activeField = this.findDetailField(this.activeField.id)
    },
    async generatePrompt() {
      if (!this.selectedRecord || !this.activeField) return
      if (!this.activeFieldAiSuitable) {
        this.$message.warning('请先将生成类型设为 AI')
        return
      }
      this.generatingPrompt = true
      try {
        await this.saveActiveFieldGeneration()
        const data = await http.post(
          `/admin/api/inpatient-emr/templates/${this.selectedRecord.id}/fields/${encodeURIComponent(this.activeField.id)}/prompt/generate`,
          { generatorInstruction: this.promptGeneratorInstruction }
        )
        this.promptText = data.prompt || ''
        this.promptGeneratorInstruction = data.generatorInstruction || this.promptGeneratorInstruction
        this.$message.success('提示词草稿已生成')
      } catch (error) {
        this.$message.error(error.message || '自动生成失败')
      } finally {
        this.generatingPrompt = false
      }
    },
    async savePrompt() {
      if (!this.selectedRecord || !this.activeField) return
      this.savingPrompt = true
      try {
        await this.saveActiveFieldGeneration()
        if (this.activeFieldAiSuitable) {
          this.selectedRecord = await http.put(
            `/admin/api/inpatient-emr/templates/${this.selectedRecord.id}/fields/${encodeURIComponent(this.activeField.id)}/prompt`,
            { prompt: this.promptText, generatorInstruction: this.promptGeneratorInstruction }
          )
        }
        this.promptDialogVisible = false
        this.$message.success('字段维护已保存')
        await this.loadData()
      } catch (error) {
        this.$message.error(error.message || '保存失败')
      } finally {
        this.savingPrompt = false
      }
    },
    async enableRecord(row) {
      await this.changeStatus(row, 'enable')
    },
    async disableRecord(row) {
      await this.changeStatus(row, 'disable')
    },
    async changeStatus(row, action) {
      try {
        await http.post(`/admin/api/inpatient-emr/templates/${row.id}/${action}`)
        this.$message.success(action === 'enable' ? '已启用' : '已停用')
        this.loadData()
      } catch (error) {
        this.$message.error(error.message || '操作失败')
      }
    },
    async removeRecord(row) {
      try {
        await this.$confirm('删除后客户端会重新上传同模板解析结果，确认删除？', '删除模板缓存', {
          type: 'warning'
        })
      } catch (action) {
        if (action !== 'cancel' && action !== 'close') {
          this.$message.error(action && action.message ? action.message : '删除确认失败')
        }
        return
      }

      try {
        await http.delete(`/admin/api/inpatient-emr/templates/${row.id}`)
        this.$message.success('已删除')
        if (this.records.length === 1 && this.current > 1) {
          this.current -= 1
        }
        await this.loadData()
      } catch (error) {
        this.$message.error(error.message || '删除失败')
      }
    },
    formatTime(value) {
      if (!value) return '-'
      return new Date(value).toLocaleString()
    }
  }
}
</script>

<style scoped>
.template-preview-frame {
  width: 100%;
  height: 560px;
  border: 1px solid #d8e0e3;
  border-radius: 6px;
  background: #ffffff;
}

.template-source-input :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.55;
}

:deep(.inpatient-field-dialog) {
  max-width: 1360px;
}

:deep(.inpatient-field-dialog .el-dialog__body) {
  padding: 12px 16px 16px;
  max-height: calc(92vh - 54px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.field-filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  min-width: 0;
}

.field-filter-bar__search {
  width: 280px;
  flex: 0 0 280px;
}

.field-filter-bar__select {
  width: 128px;
  flex: 0 0 128px;
}

.field-filter-bar__quick {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: #6b7280;
  white-space: nowrap;
}

.field-table :deep(.cell) {
  white-space: nowrap;
}

.field-table :deep(.el-table__body-wrapper) {
  overflow-y: auto;
}

.field-code-tag {
  max-width: 134px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.prompt-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.prompt-cell__text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  line-height: 1.4;
}

.prompt-generator-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

@media (max-width: 1180px) {
  .field-filter-bar {
    flex-wrap: wrap;
  }

  .field-filter-bar__quick {
    flex-basis: 100%;
  }
}
</style>
