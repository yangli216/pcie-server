<template>
  <div class="page-surface">
    <section class="page-section page-section--padded page-section--toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model="keyword"
          clearable
          placeholder="输入区域名称…"
          class="search-input"
          @keyup.enter.native="search"
        />
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-button type="primary" icon="el-icon-plus" @click="openCreate">新增区域</el-button>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading">
      <el-table-column label="区域编码" min-width="140"><template slot-scope="{ row }"><code-tag :value="row.cdRegion" /></template></el-table-column>
      <el-table-column prop="naRegion" label="区域名称" min-width="160" />
      <el-table-column label="上级区域" min-width="160">
        <template slot-scope="{ row }">
          {{ resolveParentName(row.idParent) }}
        </template>
      </el-table-column>
      <el-table-column prop="sdRegionType" label="区域类型" min-width="120" />
      <el-table-column label="状态" width="90">
        <template slot-scope="{ row }">
          <status-pill :tone="statusTone(statusMeta(row.sdStatus).type)" :label="statusMeta(row.sdStatus).label" />
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="90" />
      <el-table-column label="更新时间" width="170">
        <template slot-scope="{ row }">
          {{ formatDateTime(row.updateTime) }}
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="220">
        <template slot-scope="{ row }">
          {{ row.desRegion || '--' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template slot-scope="{ row }">
          <div class="table-actions">
            <table-action @click="openEdit(row)">编辑</table-action>
            <table-action :danger="isEnabled(row)" @click="toggleStatus(row)">{{ isEnabled(row) ? '停用' : '启用' }}</table-action>
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

    <el-dialog v-if="dialogVisible" :title="dialogTitle" :visible.sync="dialogVisible" width="760px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="区域编码">
            <el-input v-model.trim="form.cdRegion" maxlength="64" />
          </el-form-item>
          <el-form-item label="区域名称" prop="naRegion">
            <el-input v-model.trim="form.naRegion" maxlength="128" />
          </el-form-item>
          <el-form-item label="上级区域">
            <el-select v-model="form.idParent" clearable filterable placeholder="可选…">
              <el-option
                v-for="item in parentRegionOptions"
                :key="item.idRegion"
                :label="item.naRegion"
                :value="item.idRegion"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="区域类型">
            <el-input v-model.trim="form.sdRegionType" maxlength="32" placeholder="如 district" />
          </el-form-item>
          <el-form-item label="状态" prop="sdStatus">
            <segmented-switch v-model="form.sdStatus" :options="statusOptions" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number
              v-model="form.sortOrder"
              :min="0"
              :max="9999"
              controls-position="right"
              style="width: 100%;"
            />
          </el-form-item>
          <el-form-item label="说明" class="form-span-2">
            <el-input v-model.trim="form.desRegion" type="textarea" :rows="4" maxlength="500" />
          </el-form-item>
        </div>
      </el-form>
      <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchRegions } from '../api/reference'
import http from '../api/http'
import { buildLabelMap, configStatusOptions, findStatusMeta, formatDateTime, statusTone } from '../utils/admin'
import { CodeTag, SegmentedSwitch, StatusPill, TableAction } from '../components/ui'

function createDefaultForm() {
  return {
    idRegion: '',
    cdRegion: '',
    naRegion: '',
    idParent: '',
    sdRegionType: '',
    sdStatus: '1',
    sortOrder: 0,
    desRegion: ''
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
      keyword: '',
      current: 1,
      size: 10,
      total: 0,
      records: [],
      regionOptions: [],
      regionMap: {},
      statusOptions: configStatusOptions,
      form: createDefaultForm(),
      rules: {
        naRegion: [{ required: true, message: '请输入区域名称', trigger: 'blur' }]
      }
    }
  },
  computed: {
    dialogTitle() {
      return this.dialogMode === 'create' ? '新增区域' : '编辑区域'
    },
    parentRegionOptions() {
      return this.regionOptions.filter(item => item.idRegion !== this.form.idRegion)
    }
  },
  async mounted() {
    await this.loadReferences()
    this.loadData()
  },
  methods: {
    formatDateTime,
    statusTone,
    statusMeta(value) {
      return findStatusMeta(configStatusOptions, value)
    },
    isEnabled(row) {
      return row && row.sdStatus === '1'
    },
    resolveParentName(idParent) {
      return idParent ? this.regionMap[idParent] || idParent : '--'
    },
    async loadReferences() {
      try {
        const regions = await fetchRegions()
        this.regionOptions = regions
        this.regionMap = buildLabelMap(regions, 'idRegion', 'naRegion')
      } catch (error) {
        this.$message.error(error.message || '加载区域选项失败')
      }
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/regions', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.keyword || undefined
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
    search() {
      this.current = 1
      this.loadData()
    },
    reset() {
      this.keyword = ''
      this.current = 1
      this.loadData()
    },
    openCreate() {
      this.dialogMode = 'create'
      this.form = createDefaultForm()
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogMode = 'edit'
      this.form = {
        idRegion: row.idRegion,
        cdRegion: row.cdRegion || '',
        naRegion: row.naRegion || '',
        idParent: row.idParent || '',
        sdRegionType: row.sdRegionType || '',
        sdStatus: row.sdStatus || '1',
        sortOrder: typeof row.sortOrder === 'number' ? row.sortOrder : Number(row.sortOrder) || 0,
        desRegion: row.desRegion || ''
      }
      this.dialogVisible = true
    },
    resetForm() {
      this.form = createDefaultForm()
      if (this.$refs.formRef) {
        this.$refs.formRef.resetFields()
      }
    },
    submitForm() {
      this.$refs.formRef.validate(async valid => {
        if (!valid) {
          return
        }
        this.saving = true
        try {
          const payload = {
            cdRegion: this.form.cdRegion || '',
            naRegion: this.form.naRegion,
            idParent: this.form.idParent || '',
            sdRegionType: this.form.sdRegionType || '',
            sdStatus: this.form.sdStatus,
            sortOrder: this.form.sortOrder || 0,
            desRegion: this.form.desRegion || ''
          }
          if (this.dialogMode === 'create') {
            await http.post('/admin/api/regions', payload)
          } else {
            await http.put(`/admin/api/regions/${this.form.idRegion}`, payload)
          }
          this.$message.success('保存成功')
          this.dialogVisible = false
          await this.loadReferences()
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || '保存失败')
        } finally {
          this.saving = false
        }
      })
    },
    toggleStatus(row) {
      const enable = !this.isEnabled(row)
      const actionText = enable ? '启用' : '停用'
      this.$confirm(`确认${actionText}区域「${row.naRegion || row.cdRegion}」吗？`, '提示', {
        type: 'warning'
      }).then(async () => {
        try {
          if (enable) {
            await http.post(`/admin/api/regions/${row.idRegion}/enable`)
          } else {
            await http.delete(`/admin/api/regions/${row.idRegion}`)
          }
          this.$message.success(`${actionText}成功`)
          await this.loadReferences()
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || `${actionText}失败`)
        }
      }).catch(() => {})
    }
  }
}
</script>
