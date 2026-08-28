<template>
  <div class="page-surface prompt-page">
    <section class="page-section page-section--padded page-section--toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model.trim="keyword"
          clearable
          placeholder="搜索编码、名称、类型…"
          class="search-input"
          @keyup.enter.native="loadData"
        />
        <el-select v-model="status" clearable placeholder="状态" class="status-filter">
          <el-option
            v-for="item in statusOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-button type="primary" icon="el-icon-search" @click="loadData">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-button type="primary" icon="el-icon-plus" @click="openCreate">新增提示词</el-button>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading">
        <el-table-column label="编码" min-width="180" show-overflow-tooltip>
          <template slot-scope="{ row }"><code-tag :value="row.cdPrompt" /></template>
        </el-table-column>
        <el-table-column prop="naPrompt" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="sdPromptType" label="类型" width="130" />
        <el-table-column label="来源" width="100">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.builtIn ? 'info' : 'success'">{{ row.builtIn ? '内置' : '配置' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="130" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ row.versionNum || '--' }}</template>
        </el-table-column>
        <el-table-column label="作用域" min-width="150" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ resolveScope(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template slot-scope="{ row }">
            <status-pill :tone="statusTone(statusMeta(row.sdStatus).type)" :label="statusMeta(row.sdStatus).label" />
          </template>
        </el-table-column>
        <el-table-column label="System Prompt" min-width="260">
          <template slot-scope="{ row }">{{ truncate(row.sysPrompt, 56) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template slot-scope="{ row }">
            <div class="table-actions">
              <table-action v-if="row.builtIn" @click="cloneBuiltIn(row)">创建覆盖</table-action>
              <template v-else>
                <table-action @click="openEdit(row)">编辑</table-action>
                <table-action v-if="row.sdStatus !== '1'" @click="publishPrompt(row)">发布</table-action>
                <table-action v-else @click="archivePrompt(row)">归档</table-action>
                <table-action danger @click="removePrompt(row)">停用</table-action>
              </template>
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
      v-if="dialogVisible"
      :title="dialogTitle"
      :visible.sync="dialogVisible"
      width="1040px"
      custom-class="prompt-dialog"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="prompt-form">
        <section class="prompt-section">
          <h3>基础信息</h3>
          <div class="form-grid">
            <el-form-item label="Prompt 编码" prop="cdPrompt">
              <el-input v-model.trim="form.cdPrompt" maxlength="128" placeholder="例如 voiceIntentRecognition" />
            </el-form-item>
            <el-form-item label="名称" prop="naPrompt">
              <el-input v-model.trim="form.naPrompt" maxlength="128" placeholder="输入便于识别的名称…" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="form.sdPromptType" filterable allow-create default-first-option placeholder="选择或输入类型">
                <el-option
                  v-for="item in typeOptions"
                  :key="item"
                  :label="item"
                  :value="item"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="版本号">
              <el-input v-model.trim="form.versionNum" maxlength="64" placeholder="留空则保存时自动生成…" />
            </el-form-item>
          </div>
        </section>

        <section class="prompt-section">
          <h3>作用域与状态</h3>
          <div class="form-grid">
            <el-form-item label="所属区域">
              <el-select v-model="form.idRegion" clearable filterable placeholder="全局">
                <el-option v-for="item in regionOptions" :key="item.idRegion" :label="item.naRegion" :value="item.idRegion" />
              </el-select>
            </el-form-item>
            <el-form-item label="所属机构">
              <el-select v-model="form.idOrg" clearable filterable placeholder="区域/全局" @change="syncRegionByOrg">
                <el-option v-for="item in orgOptions" :key="item.idOrg" :label="item.naOrg" :value="item.idOrg" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <segmented-switch v-model="form.sdStatus" :options="statusOptions" />
            </el-form-item>
          </div>
        </section>

        <section class="prompt-section">
          <h3>提示词内容</h3>
          <el-form-item label="System Prompt" prop="sysPrompt">
            <el-input
              v-model="form.sysPrompt"
              type="textarea"
              :rows="12"
              placeholder="输入系统提示词…"
            />
          </el-form-item>
          <el-form-item label="User Template">
            <el-input
              v-model="form.userTemplate"
              type="textarea"
              :rows="6"
              :placeholder="'输入用户提示词模板，可使用 {{transcribedText}}、{{input}} 等占位符…'"
            />
          </el-form-item>
        </section>
      </el-form>
      <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { fetchOrgs, fetchRegions } from '../api/reference'
import {
  buildLabelMap,
  resolveScopeLabel,
  statusTone,
  truncate
} from '../utils/admin'
import { CodeTag, SegmentedSwitch, StatusPill, TableAction } from '../components/ui'

const STATUS_OPTIONS = [
  { value: '0', label: '草稿', type: 'info' },
  { value: '1', label: '已发布', type: 'success' },
  { value: '2', label: '已归档', type: 'warning' }
]

const TYPE_OPTIONS = ['consultation', 'symptom', 'inpatient_emr', 'fact_check', 'chat']

function createDefaultForm() {
  return {
    idPrompt: '',
    cdPrompt: '',
    naPrompt: '',
    sysPrompt: '',
    userTemplate: '',
    versionNum: '',
    sdPromptType: 'consultation',
    sdStatus: '0',
    idOrg: '',
    idRegion: ''
  }
}

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
      saving: false,
      dialogVisible: false,
      dialogMode: 'create',
      current: 1,
      size: 10,
      total: 0,
      keyword: '',
      status: '',
      records: [],
      regionOptions: [],
      orgOptions: [],
      regionMap: {},
      orgMap: {},
      statusOptions: STATUS_OPTIONS,
      typeOptions: TYPE_OPTIONS,
      form: createDefaultForm(),
      rules: {
        cdPrompt: [{ required: true, message: '请输入 Prompt 编码', trigger: 'blur' }],
        naPrompt: [{ required: true, message: '请输入 Prompt 名称', trigger: 'blur' }],
        sysPrompt: [{ required: true, message: '请输入 System Prompt', trigger: 'blur' }]
      }
    }
  },
  computed: {
    dialogTitle() {
      return this.dialogMode === 'edit' ? '编辑提示词' : '新增提示词'
    }
  },
  async mounted() {
    await this.loadReferences()
    this.loadData()
  },
  methods: {
    statusTone,
    truncate,
    statusMeta(value) {
      return STATUS_OPTIONS.find(item => item.value === value) || { label: value || '--', type: 'info' }
    },
    resolveScope(row) {
      return row.builtIn ? '内置默认' : resolveScopeLabel(row, this.orgMap, this.regionMap)
    },
    async loadReferences() {
      const [regions, orgs] = await Promise.all([fetchRegions(), fetchOrgs()])
      this.regionOptions = regions
      this.orgOptions = orgs
      this.regionMap = buildLabelMap(regions, 'idRegion', 'naRegion')
      this.orgMap = buildLabelMap(orgs, 'idOrg', 'naOrg')
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/prompts', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.keyword || undefined,
            sdStatus: this.status || undefined
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
      this.status = ''
      this.current = 1
      this.loadData()
    },
    openCreate() {
      this.dialogMode = 'create'
      this.form = createDefaultForm()
      this.dialogVisible = true
    },
    cloneBuiltIn(row) {
      this.dialogMode = 'create'
      this.form = {
        ...createDefaultForm(),
        cdPrompt: row.cdPrompt || '',
        naPrompt: row.naPrompt || '',
        sysPrompt: row.sysPrompt || '',
        userTemplate: row.userTemplate || '',
        versionNum: row.versionNum || '',
        sdPromptType: row.sdPromptType || 'consultation'
      }
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogMode = 'edit'
      this.form = {
        idPrompt: row.idPrompt,
        cdPrompt: row.cdPrompt || '',
        naPrompt: row.naPrompt || '',
        sysPrompt: row.sysPrompt || '',
        userTemplate: row.userTemplate || '',
        versionNum: row.versionNum || '',
        sdPromptType: row.sdPromptType || 'consultation',
        sdStatus: row.sdStatus || '0',
        idOrg: row.idOrg || '',
        idRegion: row.idRegion || ''
      }
      this.dialogVisible = true
    },
    resetForm() {
      this.form = createDefaultForm()
      if (this.$refs.formRef) {
        this.$refs.formRef.clearValidate()
      }
    },
    syncRegionByOrg(idOrg) {
      const org = this.orgOptions.find(item => item.idOrg === idOrg)
      if (org && org.idRegion) {
        this.form.idRegion = org.idRegion
      }
    },
    submitForm() {
      this.$refs.formRef.validate(async valid => {
        if (!valid) return
        this.saving = true
        try {
          const payload = {
            cdPrompt: this.form.cdPrompt,
            naPrompt: this.form.naPrompt,
            sysPrompt: this.form.sysPrompt,
            userTemplate: this.form.userTemplate,
            versionNum: this.form.versionNum,
            sdPromptType: this.form.sdPromptType,
            sdStatus: this.form.sdStatus,
            idOrg: this.form.idOrg || undefined,
            idRegion: this.form.idRegion || undefined
          }
          if (this.dialogMode === 'edit') {
            await http.put(`/admin/api/prompts/${this.form.idPrompt}`, payload)
          } else {
            await http.post('/admin/api/prompts', payload)
          }
          this.$message.success('保存成功')
          this.dialogVisible = false
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || '保存失败')
        } finally {
          this.saving = false
        }
      })
    },
    async publishPrompt(row) {
      try {
        await this.$confirm('发布后同编码同作用域的其他已发布版本会自动归档。确认发布？', '发布提示词', { type: 'warning' })
        await http.post(`/admin/api/prompts/${row.idPrompt}/publish`)
        this.$message.success('已发布')
        this.loadData()
      } catch (error) {
        if (error !== 'cancel') {
          this.$message.error(error.message || '发布失败')
        }
      }
    },
    async archivePrompt(row) {
      try {
        await http.post(`/admin/api/prompts/${row.idPrompt}/archive`)
        this.$message.success('已归档')
        this.loadData()
      } catch (error) {
        this.$message.error(error.message || '归档失败')
      }
    },
    async removePrompt(row) {
      try {
        await this.$confirm(`确认停用提示词「${row.naPrompt || row.cdPrompt}」？`, '停用提示词', { type: 'warning' })
        await http.delete(`/admin/api/prompts/${row.idPrompt}`)
        this.$message.success('已停用')
        this.loadData()
      } catch (error) {
        if (error !== 'cancel') {
          this.$message.error(error.message || '停用失败')
        }
      }
    }
  }
}
</script>

<style scoped>
.prompt-page .search-input {
  width: 280px;
}

.status-filter {
  width: 130px;
}

.prompt-form {
  max-height: 70vh;
  overflow: auto;
  padding-right: 6px;
}

.prompt-section {
  margin-bottom: 18px;
  padding-bottom: 16px;
  border-bottom: 1px solid #E8EEEC;
}

.prompt-section:last-child {
  margin-bottom: 0;
  border-bottom: 0;
}

.prompt-section h3 {
  margin: 0 0 12px;
  color: #2C2C2A;
  font-size: 14px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 1000px) {
  .form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
