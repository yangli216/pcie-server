<template>
  <div class="outpatient-field-mapping">
    <div class="mapping-filter-bar">
      <el-input
        v-model.trim="keyword"
        clearable
        placeholder="搜索字段 ID、字段含义或提示词…"
        class="mapping-filter-bar__search"
        aria-label="搜索字段"
      />
      <el-select v-model="generation" aria-label="生成类型" class="mapping-filter-bar__select">
        <el-option label="全部类型" value="all" />
        <el-option label="AI生成" value="ai" />
        <el-option label="人工/HIS" value="manual" />
      </el-select>
      <el-select v-model="prompt" aria-label="提示词状态" class="mapping-filter-bar__select">
        <el-option label="全部提示词" value="all" />
        <el-option label="已维护" value="custom" />
        <el-option label="默认" value="default" />
        <el-option label="非AI" value="not_ai" />
      </el-select>
      <div class="mapping-filter-bar__quick">
        <span>常用过滤</span>
        <el-button size="mini" @click="applyQuickFilter('all')">全部</el-button>
        <el-button size="mini" @click="applyQuickFilter('ai')">AI生成</el-button>
        <el-button size="mini" @click="applyQuickFilter('manual')">人工/HIS</el-button>
        <el-button size="mini" @click="applyQuickFilter('custom')">已维护</el-button>
        <el-button size="mini" @click="applyQuickFilter('pending')">待维护</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="filteredFields"
      :height="tableHeight"
      class="mapping-table"
      aria-label="门诊病例模板字段映射"
    >
      <el-table-column label="原始字段 / 章节" min-width="160" show-overflow-tooltip>
        <template slot-scope="{ row }"><div class="mapping-primary-cell"><strong>{{ row.name }}</strong><span>{{ row.articleName }}</span><code-tag :value="row.id" /></div></template>
      </el-table-column>
      <el-table-column label="字段含义" min-width="165" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ row.policy.effective.meaning }}</template>
      </el-table-column>
      <el-table-column label="类型 / 属性" width="150">
        <template slot-scope="{ row }">
          <div class="mapping-field-meta">
            <span>{{ row.type }}</span>
            <status-pill :tone="row.readonly ? 'muted' : 'success'" :label="row.readonly ? '只读' : '可写'" />
            <span v-if="row.dictionaryItems.length" class="mapping-field-meta__dictionary">字典 {{ row.dictionaryItems.length }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="当前有效映射" min-width="190" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="mapping-value-cell">
            <code-tag :value="row.recordField || '未映射'" :tone="row.recordField ? 'primary' : 'default'" />
            <span>{{ projectionLabel(row.projectionMode) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="是否展示" width="88">
        <template slot-scope="{ row }"><status-pill :tone="row.policy.settings.visible === false ? 'muted' : 'success'" :label="row.policy.settings.visible === false ? '否' : '是'" /></template>
      </el-table-column>
      <el-table-column label="生成类型" width="92">
        <template slot-scope="{ row }"><status-pill :tone="row.policy.effective.aiSuitable ? 'success' : 'muted'" :label="row.policy.effective.aiSuitable ? 'AI' : '人工/HIS'" /></template>
      </el-table-column>
      <el-table-column label="当前生效提示词" min-width="220" show-overflow-tooltip>
        <template slot-scope="{ row }"><template v-if="row.policy.effective.aiSuitable">{{ row.policy.effective.promptSource === 'custom' ? '已维护' : '默认' }} · {{ row.policy.effective.resolvedPrompt }}</template><span v-else>—</span></template>
      </el-table-column>
      <el-table-column label="操作" width="76" fixed="right">
        <template slot-scope="{ row }">
          <table-action label="维护" @click="$emit('edit', row)" />
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
import { CodeTag, StatusPill, TableAction } from '../ui'
import { mappingSourceLabels } from '../../utils/outpatientEmrTemplateContract'

export default {
  name: 'OutpatientFieldMappingPanel',
  components: {
    CodeTag,
    StatusPill,
    TableAction
  },
  props: {
    fields: {
      type: Array,
      default: () => []
    },
    mappingOverrides: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    },
    tableHeight: {
      type: Number,
      default: 520
    }
  },
  data() {
    return {
      keyword: '',
      generation: 'all',
      prompt: 'all'
    }
  },
  computed: {
    overrideMap() {
      return this.mappingOverrides.reduce((result, item) => {
        result[item.fieldId] = item
        return result
      }, Object.create(null))
    },
    filteredFields() {
      const keyword = String(this.keyword || '').toLowerCase()
      return this.fields.filter((field) => {
        const rule = field.policy.effective
        if (this.generation !== 'all' && rule.aiSuitable !== (this.generation === 'ai')) return false
        if (this.prompt !== 'all' && this.prompt !== rule.promptSource) return false
        if (!keyword) return true
        return [
          rule.meaning, rule.resolvedPrompt, field.id,
          field.name,
          field.type,
          field.articleTemplateId,
          field.articleId,
          field.articleName,
          field.articleDefinitionName,
          field.recordField,
          field.mappingSource,
          this.automaticRecordField(field),
          this.automaticMappingSource(field)
        ].filter(Boolean).join(' ').toLowerCase().indexOf(keyword) > -1
      })
    }
  },
  methods: {
    resetFilters() {
      this.keyword = ''
      this.generation = 'all'
      this.prompt = 'all'
    },
    applyQuickFilter(value) {
      this.resetFilters()
      if (value === 'ai' || value === 'manual') {
        this.generation = value
      } else if (value === 'custom' || value === 'pending') {
        this.generation = 'ai'
        this.prompt = value === 'custom' ? 'custom' : 'default'
      }
    },
    overrideFor(field) {
      return field ? this.overrideMap[field.id] || null : null
    },
    automaticRecordField(field) {
      const override = this.overrideFor(field)
      return override ? override.automaticRecordField : field.recordField
    },
    automaticMappingSource(field) {
      const override = this.overrideFor(field)
      return override ? override.automaticMappingSource : field.mappingSource
    },
    automaticProjectionMode(field) {
      const override = this.overrideFor(field)
      return override ? override.automaticProjectionMode : field.projectionMode
    },
    mappingSourceLabel(value) {
      return mappingSourceLabels[value] || value || '未识别'
    },
    projectionLabel(value) {
      if (value === 'direct') return '直接写入'
      if (value === 'section-compose') return '章节组合'
      return '无投影'
    },
    mappingState(field) {
      const override = this.overrideFor(field)
      if (override && override.mappingStatus === 'unmapped') {
        return { label: '人工排除', tone: 'danger' }
      }
      if (override) return { label: '人工映射', tone: 'warning' }
      if (field.recordField) return { label: '自动映射', tone: 'success' }
      return { label: '待映射', tone: 'muted' }
    }
  }
}
</script>

<style scoped>
.mapping-filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  margin-bottom: 12px;
}

.mapping-filter-bar__search {
  width: 280px;
  flex: 0 0 280px;
}

.mapping-filter-bar__select {
  width: 128px;
  flex: 0 0 128px;
}

.mapping-filter-bar__quick {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: #6b7280;
  white-space: nowrap;
}

.mapping-table ::v-deep .cell {
  white-space: nowrap;
}

.mapping-code-tag {
  display: inline-block;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: middle;
}

.mapping-primary-cell,
.mapping-value-cell {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mapping-primary-cell strong {
  overflow: hidden;
  color: var(--color-text-primary);
  font-weight: 500;
  text-overflow: ellipsis;
}

.mapping-primary-cell span,
.mapping-value-cell span {
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
}

.mapping-field-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.mapping-field-meta__dictionary {
  color: #5d6b68;
}

@media (max-width: 1180px) {
  .mapping-filter-bar {
    flex-wrap: wrap;
  }

  .mapping-filter-bar__quick {
    flex-basis: 100%;
  }
}
</style>
