<template>
  <div class="page-surface">
    <section class="page-section page-section--padded page-section--toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model.trim="keyword"
          clearable
          placeholder="输入角色编码或名称…"
          class="search-input"
          @keyup.enter.native="search"
        />
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-button type="primary" icon="el-icon-plus" @click="openCreate">新增角色</el-button>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading">
      <el-table-column label="角色编码" min-width="180"><template slot-scope="{ row }"><code-tag :value="row.cdRole" /></template></el-table-column>
      <el-table-column prop="naRole" label="角色名称" min-width="160" />
      <el-table-column label="状态" width="90">
        <template slot-scope="{ row }">
          <status-pill :tone="statusTone(statusMeta(row.sdStatus).type)" :label="statusMeta(row.sdStatus).label" />
        </template>
      </el-table-column>
      <el-table-column label="角色说明" min-width="280">
        <template slot-scope="{ row }">
          {{ row.desRole || '--' }}
        </template>
      </el-table-column>
      <el-table-column label="更新时间" min-width="170">
        <template slot-scope="{ row }">
          {{ formatDateTime(row.updateTime || row.dtUpdate) }}
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

    <el-dialog v-if="dialogVisible" :title="dialogTitle" :visible.sync="dialogVisible" width="720px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="角色编码" prop="cdRole">
            <el-input v-model.trim="form.cdRole" maxlength="64" />
          </el-form-item>
          <el-form-item label="角色名称" prop="naRole">
            <el-input v-model.trim="form.naRole" maxlength="64" />
          </el-form-item>
          <el-form-item label="状态" prop="sdStatus">
            <segmented-switch v-model="form.sdStatus" :options="statusOptions" />
          </el-form-item>
          <el-form-item label="角色说明" class="form-span-2">
            <el-input v-model.trim="form.desRole" type="textarea" :rows="4" maxlength="500" />
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
import http from '../api/http'
import { configStatusOptions, findStatusMeta, formatDateTime, statusTone } from '../utils/admin'
import { CodeTag, SegmentedSwitch, StatusPill, TableAction } from '../components/ui'

function createDefaultForm() {
  return {
    idRole: '',
    cdRole: '',
    naRole: '',
    desRole: '',
    sdStatus: '1'
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
      statusOptions: configStatusOptions,
      form: createDefaultForm(),
      rules: {
        cdRole: [{ required: true, message: '请输入角色编码', trigger: 'blur' }],
        naRole: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
      }
    }
  },
  computed: {
    dialogTitle() {
      return this.dialogMode === 'create' ? '新增角色' : '编辑角色'
    }
  },
  mounted() {
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
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/roles', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.keyword || undefined
          }
        })
        this.records = data.records || []
        this.total = data.total || 0
      } catch (error) {
        this.$message.error((error && error.message) || '加载失败')
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
        idRole: row.idRole,
        cdRole: row.cdRole || '',
        naRole: row.naRole || '',
        desRole: row.desRole || '',
        sdStatus: row.sdStatus || '1'
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
            cdRole: this.form.cdRole,
            naRole: this.form.naRole,
            desRole: this.form.desRole || '',
            sdStatus: this.form.sdStatus
          }

          if (this.dialogMode === 'create') {
            await http.post('/admin/api/roles', payload)
          } else {
            await http.put(`/admin/api/roles/${this.form.idRole}`, payload)
          }

          this.$message.success('保存成功')
          this.dialogVisible = false
          this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || '保存失败')
        } finally {
          this.saving = false
        }
      })
    },
    toggleStatus(row) {
      const enable = !this.isEnabled(row)
      const actionText = enable ? '启用' : '停用'
      this.$confirm(`确认${actionText}角色「${row.naRole || row.cdRole}」吗？`, '提示', {
        type: 'warning'
      }).then(async () => {
        try {
          await this.updateRoleStatus(row, enable ? '1' : '0')
          this.$message.success(`${actionText}成功`)
          this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || `${actionText}失败`)
        }
      }).catch(() => {})
    },
    updateRoleStatus(row, sdStatus) {
      return http.put(`/admin/api/roles/${row.idRole}`, {
        cdRole: row.cdRole || '',
        naRole: row.naRole || '',
        desRole: row.desRole || '',
        sdStatus
      })
    }
  }
}
</script>
