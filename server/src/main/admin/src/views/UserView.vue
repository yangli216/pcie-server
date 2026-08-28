<template>
  <div class="page-surface">
    <section class="page-section page-section--padded page-section--toolbar">
      <div class="page-toolbar__filters user-filters">
        <el-input
          v-model.trim="filters.keyword"
          clearable
          aria-label="用户关键词"
          :placeholder="bbpMode ? '输入人员编码、姓名或科室…' : '输入账号或姓名…'"
          class="search-input"
          @keyup.enter.native="search"
        />
        <el-select v-model="filters.sdStatus" clearable aria-label="用户状态" placeholder="状态" class="filter-select">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-if="!bbpMode" v-model="filters.idRole" clearable filterable placeholder="角色" class="filter-select">
          <el-option
            v-for="item in roleOptions"
            :key="resolveRoleValue(item)"
            :label="resolveRoleLabel(item)"
            :value="resolveRoleValue(item)"
          />
        </el-select>
        <el-select
          v-if="bbpMode"
          v-model="filters.idOrg"
          filterable
          aria-label="BBP 机构"
          placeholder="请选择 BBP 机构…"
          class="bbp-org-select"
          @change="handleBbpOrgChange"
        >
          <el-option
            v-for="org in bbpOrganizations"
            :key="org.id"
            :label="org.name || org.fullName || org.code || org.id"
            :value="org.id"
          />
        </el-select>
        <el-select
          v-if="bbpMode && systemAdmin"
          v-model="accessRoleCode"
          aria-label="PCIE 后台权限类型"
          class="access-role-select"
          @change="handleAccessRoleChange"
        >
          <el-option label="机构权限管理员" value="ORG_ADMIN" />
          <el-option label="机构统计员" value="ORG_ANALYST" />
        </el-select>
        <el-input
          v-if="!bbpMode"
          v-model.trim="filters.idOrg"
          clearable
          placeholder="机构标识"
          class="filter-select"
          @keyup.enter.native="search"
        />
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <div class="table-actions">
        <el-tag v-if="bbpMode" size="small" :type="accessManageAvailable ? 'success' : 'info'">
          {{ bbpModeTagLabel }}
        </el-tag>
        <el-button v-if="!bbpMode" type="primary" icon="el-icon-plus" @click="openCreate">新增用户</el-button>
      </div>
    </section>

    <section class="page-section page-section--table">
      <div v-if="accessLoadError" class="access-error" role="alert">
        <el-alert :title="accessLoadError" type="warning" show-icon :closable="false" />
      </div>
      <el-table :data="records" v-loading="loading" :empty-text="userEmptyText">
      <el-table-column :label="bbpMode ? '人员编码' : '登录账号'" min-width="140">
        <template slot-scope="{ row }"><code-tag :value="row.cdUser" /></template>
      </el-table-column>
      <el-table-column prop="naUser" :label="bbpMode ? '人员姓名' : '用户姓名'" min-width="140" />
      <el-table-column label="所属机构" min-width="160">
        <template slot-scope="{ row }">
          {{ row.naOrg || row.idOrg || '--' }}
        </template>
      </el-table-column>
      <el-table-column v-if="bbpMode" label="科室" min-width="180">
        <template slot-scope="{ row }">{{ row.departmentName || row.departmentId || '--' }}</template>
      </el-table-column>
      <el-table-column v-if="bbpMode" label="人员类型 / 职称" min-width="180">
        <template slot-scope="{ row }">
          {{ [row.personTypeText || row.personType, row.titleTypeText || row.titleType].filter(Boolean).join(' / ') || '--' }}
        </template>
      </el-table-column>
      <el-table-column v-if="!bbpMode" label="角色" min-width="220">
        <template slot-scope="{ row }">
          <div class="role-tags">
            <el-tag v-for="role in resolveUserRoleLabels(row)" :key="role" size="mini" type="info">
              {{ role }}
            </el-tag>
            <span v-if="!resolveUserRoleLabels(row).length">--</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template slot-scope="{ row }">
          <status-pill
            v-if="bbpMode"
            :tone="!row.directoryPresent || row.bbpActive === null ? 'warning' : (row.bbpActive === false ? 'muted' : 'success')"
            :label="!row.directoryPresent ? '已移出' : (row.bbpActive === null ? '未知' : (row.bbpActive ? '启用' : '停用'))"
          />
          <status-pill v-else :tone="statusTone(statusMeta(row.sdStatus).type)" :label="statusMeta(row.sdStatus).label" />
        </template>
      </el-table-column>
      <el-table-column v-if="bbpMode && systemAdmin" :label="`${accessRoleLabel}权限`" min-width="150">
        <template slot-scope="{ row }">
          <status-pill
            :tone="row.grantRisk ? 'danger' : (row.adminAccessEnabled ? 'success' : 'muted')"
            :label="row.grantRisk ? '风险授权' : (row.adminAccessEnabled ? accessRoleLabel : '未授权')"
          />
        </template>
      </el-table-column>
      <el-table-column v-if="bbpMode && systemAdmin" label="权限最近变更" min-width="180" show-overflow-tooltip>
        <template slot-scope="{ row }">
          {{ row.accessUpdateTime ? `${row.operatorUserName || '--'} · ${formatDateTime(row.accessUpdateTime)}` : '--' }}
        </template>
      </el-table-column>
      <el-table-column v-if="!bbpMode" label="更新时间" min-width="170">
        <template slot-scope="{ row }">
          {{ formatDateTime(resolveUserTime(row)) }}
        </template>
      </el-table-column>
      <el-table-column v-if="!bbpMode" label="操作" width="180" fixed="right">
        <template slot-scope="{ row }">
          <div class="table-actions">
            <table-action @click="openEdit(row)">编辑</table-action>
            <table-action :danger="isEnabled(row)" @click="toggleStatus(row)">{{ isEnabled(row) ? '停用' : '启用' }}</table-action>
          </div>
        </template>
      </el-table-column>
      <el-table-column v-if="bbpMode && systemAdmin" label="操作" width="120" fixed="right">
        <template slot-scope="{ row }">
          <table-action
            :danger="row.adminAccessEnabled"
            :disabled="!accessManageAvailable || isAccessUpdating(row) || (!row.adminAccessEnabled && (!row.directoryPresent || row.bbpActive !== true))"
            :aria-label="`${row.adminAccessEnabled ? '撤销' : '授予'} ${row.naUser || row.cdUser} 的 PCIE ${accessRoleLabel}权限`"
            :title="!accessManageAvailable ? 'PCIE 后台权限服务当前不可维护' : ((!row.adminAccessEnabled && (!row.directoryPresent || row.bbpActive !== true)) ? '只有 BBP 明确启用且仍在机构目录中的人员可以授予' : '')"
            @click="toggleBbpAdminAccess(row)"
          >
            {{ isAccessUpdating(row) ? '处理中…' : (row.adminAccessEnabled ? '撤销权限' : accessGrantActionLabel) }}
          </table-action>
        </template>
      </el-table-column>
    </el-table>

    <div class="page-footer">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :current-page.sync="current"
        :page-size="size"
        :total="total"
        @current-change="loadData"
      />
    </div>
    </section>

    <el-dialog v-if="dialogVisible" :title="dialogTitle" :visible.sync="dialogVisible" width="760px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="登录账号" prop="cdUser">
            <el-input v-model.trim="form.cdUser" maxlength="64" />
          </el-form-item>
          <el-form-item label="用户姓名" prop="naUser">
            <el-input v-model.trim="form.naUser" maxlength="64" />
          </el-form-item>
          <el-form-item label="登录密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              maxlength="128"
              placeholder="请输入密码…"
            />
          </el-form-item>
          <el-form-item label="所属机构标识" prop="idOrg">
            <el-input v-model.trim="form.idOrg" maxlength="64" placeholder="例如 ORG001" />
          </el-form-item>
          <el-form-item label="角色" prop="roleIds" class="form-span-2">
            <el-select v-model="form.roleIds" multiple clearable filterable placeholder="请选择角色…">
              <el-option
                v-for="item in roleOptions"
                :key="resolveRoleValue(item)"
                :label="resolveRoleLabel(item)"
                :value="resolveRoleValue(item)"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="状态" prop="sdStatus">
            <segmented-switch v-model="form.sdStatus" :options="statusOptions" />
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
import { getAdminUser } from '../utils/auth'

const statusOptions = [
  { value: '1', label: '启用' },
  { value: '0', label: '停用' }
]

function createDefaultFilters() {
  return {
    keyword: '',
    sdStatus: '',
    idRole: '',
    idOrg: ''
  }
}

function createDefaultForm() {
  return {
    idUser: '',
    cdUser: '',
    naUser: '',
    password: '',
    idOrg: '',
    roleIds: [],
    sdStatus: '1'
  }
}

function normalizeList(value) {
  if (Array.isArray(value)) {
    return value
  }
  if (typeof value === 'string') {
    return value.split(',').map(item => item.trim()).filter(Boolean)
  }
  return []
}

function readObjectField(item, fields) {
  if (!item || typeof item !== 'object') {
    return ''
  }
  for (let index = 0; index < fields.length; index += 1) {
    const field = fields[index]
    if (item[field]) {
      return String(item[field]).trim()
    }
  }
  return ''
}

function extractRoleIds(value) {
  return normalizeList(value).map(item => {
    if (item && typeof item === 'object') {
      return readObjectField(item, ['idRole', 'roleId', 'cdRole', 'roleCode', 'value'])
    }
    return String(item || '').trim()
  }).filter(Boolean)
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
      statusOptions,
      loading: false,
      saving: false,
      dialogVisible: false,
      dialogMode: 'create',
      bbpMode: false,
      systemAdmin: false,
      accessManageAvailable: false,
      accessRoleCode: 'ORG_ADMIN',
      accessLoadError: '',
      accessUpdating: {},
      loadSequence: 0,
      bbpOrganizations: [],
      bbpAllPersons: [],
      current: 1,
      size: 10,
      total: 0,
      records: [],
      filters: createDefaultFilters(),
      roleOptions: [],
      roleMap: {},
      form: createDefaultForm(),
      rules: {
        cdUser: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
        naUser: [{ required: true, message: '请输入用户姓名', trigger: 'blur' }],
        idOrg: [{ required: true, message: '请输入所属机构标识', trigger: 'blur' }],
        roleIds: [{ type: 'array', required: true, message: '请选择至少一个角色', trigger: 'change' }]
      }
    }
  },
  computed: {
    dialogTitle() {
      return this.dialogMode === 'create' ? '新增用户' : '编辑用户'
    },
    bbpModeTagLabel() {
      if (!this.systemAdmin) {
        return 'BBP 同步 · 只读'
      }
      if (!this.filters.idOrg) {
        return 'BBP 人员只读 · 请选择机构'
      }
      return this.accessManageAvailable ? 'BBP 人员只读 · PCIE 后台权限可维护' : 'BBP 人员只读'
    },
    accessRoleLabel() {
      return this.accessRoleCode === 'ORG_ANALYST' ? '机构统计员' : '机构管理员'
    },
    accessGrantActionLabel() {
      return this.accessRoleCode === 'ORG_ANALYST' ? '设为统计员' : '设为管理员'
    },
    userEmptyText() {
      if (this.bbpMode && !this.filters.idOrg) {
        return '请选择机构'
      }
      return this.bbpMode ? '当前机构暂无人员' : '暂无数据'
    }
  },
  async mounted() {
    const currentUser = getAdminUser()
    this.bbpMode = Boolean(currentUser && currentUser.authProvider === 'BBP')
    this.systemAdmin = Boolean(currentUser && Array.isArray(currentUser.roles) && currentUser.roles.includes('SYSTEM_ADMIN'))
    if (this.bbpMode) {
      await this.loadBbpOrganizations()
    } else {
      await this.loadRoleOptions()
    }
    await this.loadData()
  },
  methods: {
    formatDateTime,
    statusTone,
    statusMeta(value) {
      return findStatusMeta(configStatusOptions, value)
    },
    async loadBbpOrganizations() {
      try {
        const data = await http.get('/admin/api/bbp/organizations')
        this.bbpOrganizations = Array.isArray(data) ? data : []
        if (!this.systemAdmin && !this.filters.idOrg && this.bbpOrganizations.length) {
          this.filters.idOrg = this.bbpOrganizations[0].id
        }
      } catch (error) {
        this.$message.error((error && error.message) || '加载 BBP 机构目录失败')
      }
    },
    handleBbpOrgChange() {
      this.current = 1
      this.accessLoadError = ''
      this.accessManageAvailable = false
      this.records = []
      this.bbpAllPersons = []
      this.total = 0
      this.loadData()
    },
    handleAccessRoleChange() {
      this.current = 1
      this.accessLoadError = ''
      this.accessManageAvailable = false
      this.records = []
      this.bbpAllPersons = []
      this.total = 0
      this.loadData()
    },
    isEnabled(row) {
      return row && row.sdStatus === '1'
    },
    resolveRoleLabel(role) {
      if (!role) {
        return '--'
      }
      return role.naRole || role.cdRole || role.idRole || '--'
    },
    resolveRoleValue(role) {
      if (!role) {
        return ''
      }
      return role.idRole || role.cdRole || ''
    },
    resolveUserTime(row) {
      return row.updateTime || row.lastLoginTime || row.dtUpdate || row.dtLastLogin || ''
    },
    resolveUserRoleLabels(row) {
      const roleNames = normalizeList(row.roleNames).map(item => {
        if (item && typeof item === 'object') {
          return readObjectField(item, ['naRole', 'roleName', 'label', 'name'])
        }
        return String(item || '').trim()
      }).filter(Boolean)

      if (roleNames.length) {
        return roleNames
      }

      const roles = normalizeList(row.roles)
      if (roles.length) {
        return roles.map(item => {
          if (item && typeof item === 'object') {
            return readObjectField(item, ['naRole', 'roleName', 'label', 'name', 'cdRole', 'roleCode']) ||
              this.roleMap[readObjectField(item, ['idRole', 'roleId', 'cdRole', 'roleCode', 'value'])] ||
              '--'
          }
          const value = String(item || '').trim()
          return this.roleMap[value] || value
        }).filter(Boolean)
      }

      return extractRoleIds(row.roleIds || row.roleCodes).map(item => this.roleMap[item] || item)
    },
    resolveFormRoleIds(row) {
      const roleIds = extractRoleIds(row.roleIds)
      if (roleIds.length) {
        return roleIds
      }
      const roleCodes = extractRoleIds(row.roleCodes)
      if (roleCodes.length) {
        return roleCodes
      }
      return extractRoleIds(row.roles)
    },
    async loadRoleOptions() {
      try {
        const data = await http.get('/admin/api/roles', {
          params: {
            current: 1,
            size: 200
          }
        })
        const records = data.records || []
        this.roleOptions = records
        this.roleMap = records.reduce((result, item) => {
          const value = this.resolveRoleValue(item)
          if (value) {
            result[value] = this.resolveRoleLabel(item)
          }
          return result
        }, {})
      } catch (error) {
        this.$message.error((error && error.message) || '加载角色选项失败')
      }
    },
    async loadData() {
      const sequence = ++this.loadSequence
      const requestedOrgId = this.filters.idOrg
      this.loading = true
      try {
        if (this.bbpMode) {
          if (!requestedOrgId) {
            this.records = []
            this.total = 0
            this.accessManageAvailable = false
            return
          }
          const directoryPath = `/admin/api/bbp/organizations/${encodeURIComponent(requestedOrgId)}/persons`
          let data
          this.accessLoadError = ''
          if (this.systemAdmin) {
            try {
              data = await http.get('/admin/api/bbp/admin-access', {
                params: { orgId: requestedOrgId, roleCode: this.accessRoleCode }
              })
              if (sequence !== this.loadSequence || requestedOrgId !== this.filters.idOrg) return
              this.accessManageAvailable = true
            } catch (accessError) {
              if (sequence !== this.loadSequence || requestedOrgId !== this.filters.idOrg) return
              this.accessManageAvailable = false
              this.accessLoadError = `${(accessError && accessError.message) || 'PCIE 后台权限加载失败'}；当前仅展示 BBP 人员目录。`
              data = await http.get(directoryPath)
              if (sequence !== this.loadSequence || requestedOrgId !== this.filters.idOrg) return
            }
          } else {
            this.accessManageAvailable = false
            data = await http.get(directoryPath)
            if (sequence !== this.loadSequence || requestedOrgId !== this.filters.idOrg) return
          }
          const org = this.bbpOrganizations.find(item => item.id === requestedOrgId)
          this.bbpAllPersons = (Array.isArray(data) ? data : []).map(item => ({
            idUser: item.bbpUserId || item.userId || item.personId || item.id,
            cdUser: item.loginName || item.personCode || item.code || item.bbpUserId || item.userId || item.personId,
            naUser: item.personName || item.name || item.loginName || item.personCode || item.code || '--',
            idOrg: item.orgId || requestedOrgId,
            naOrg: item.orgName || (org && (org.name || org.fullName || org.code)) || '',
            departmentId: item.departmentId || '',
            departmentName: item.departmentName || '',
            personType: item.personType || '',
            personTypeText: item.personTypeText || '',
            titleType: item.titleType || '',
            titleTypeText: item.titleTypeText || '',
            bbpActive: typeof item.active === 'boolean' ? item.active : null,
            sdStatus: item.active === false ? '0' : (item.active === true ? '1' : ''),
            directoryPresent: Object.prototype.hasOwnProperty.call(item, 'directoryPresent') ? item.directoryPresent : true,
            adminAccessEnabled: Boolean(item.adminAccessEnabled),
            grantRisk: Boolean(item.grantRisk),
            operatorUserName: item.operatorUserName || '',
            accessUpdateTime: item.updateTime || '',
            source: 'BBP'
          }))
          this.applyBbpPage()
          return
        }
        const data = await http.get('/admin/api/users', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.filters.keyword || undefined,
            sdStatus: this.filters.sdStatus || undefined,
            idRole: this.filters.idRole || undefined,
            idOrg: this.filters.idOrg || undefined
          }
        })
        if (sequence !== this.loadSequence) return
        this.records = data.records || []
        this.total = data.total || 0
      } catch (error) {
        if (sequence !== this.loadSequence) return
        this.$message.error((error && error.message) || '加载失败')
      } finally {
        if (sequence === this.loadSequence) {
          this.loading = false
        }
      }
    },
    applyBbpPage() {
      const keyword = String(this.filters.keyword || '').trim().toLowerCase()
      const matched = this.bbpAllPersons.filter(item => {
        if (this.filters.sdStatus && item.sdStatus !== this.filters.sdStatus) {
          return false
        }
        if (!keyword) {
          return true
        }
        return [item.cdUser, item.naUser, item.departmentName, item.personTypeText, item.titleTypeText]
          .some(value => String(value || '').toLowerCase().includes(keyword))
      })
      this.total = matched.length
      const start = (this.current - 1) * this.size
      this.records = matched.slice(start, start + this.size)
    },
    search() {
      this.current = 1
      this.loadData()
    },
    reset() {
      const selectedOrg = this.bbpMode ? this.filters.idOrg : ''
      this.filters = createDefaultFilters()
      this.filters.idOrg = selectedOrg
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
        idUser: row.idUser,
        cdUser: row.cdUser || '',
        naUser: row.naUser || '',
        password: '',
        idOrg: row.idOrg || '',
        roleIds: this.resolveFormRoleIds(row),
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

        if (this.dialogMode === 'create' && !this.form.password) {
          this.$message.error('请输入登录密码')
          return
        }

        this.saving = true
        try {
          const payload = {
            cdUser: this.form.cdUser,
            naUser: this.form.naUser,
            password: this.form.password || '',
            idOrg: this.form.idOrg,
            roleIds: this.form.roleIds,
            sdStatus: this.form.sdStatus
          }

          if (this.dialogMode === 'create') {
            await http.post('/admin/api/users', payload)
          } else {
            await http.put(`/admin/api/users/${this.form.idUser}`, payload)
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
      this.$confirm(`确认${actionText}用户「${row.naUser || row.cdUser}」吗？`, '提示', {
        type: 'warning'
      }).then(async () => {
        try {
          await this.updateUserStatus(row, enable ? '1' : '0')
          this.$message.success(`${actionText}成功`)
          this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || `${actionText}失败`)
        }
      }).catch(() => {})
    },
    updateUserStatus(row, sdStatus) {
      const action = sdStatus === '1' ? 'enable' : 'disable'
      return http.post(`/admin/api/users/${row.idUser}/${action}`)
    },
    isAccessUpdating(row) {
      return Boolean(row && this.accessUpdating[row.idUser])
    },
    toggleBbpAdminAccess(row) {
      if (!this.accessManageAvailable || this.isAccessUpdating(row)) {
        return
      }
      const enabled = !row.adminAccessEnabled
      const action = enabled ? '授予' : '撤销'
      const orgName = row.naOrg || row.idOrg || this.filters.idOrg
      const targetOrgId = row.idOrg || this.filters.idOrg
      this.$confirm(
        `确认在「${orgName}」为「${row.naUser || row.cdUser}」${action} PCIE ${this.accessRoleLabel}权限吗？`,
        'PCIE 后台权限确认',
        { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' }
      ).then(async () => {
        this.$set(this.accessUpdating, row.idUser, true)
        try {
          await http.put(`/admin/api/bbp/admin-access/${encodeURIComponent(row.idUser)}`, {
            orgId: targetOrgId,
            roleCode: this.accessRoleCode,
            enabled
          })
          this.$message.success(`${action}成功`)
          await this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || `${action}失败`)
        } finally {
          this.$delete(this.accessUpdating, row.idUser)
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.user-filters {
  flex-wrap: wrap;
}

.filter-select {
  width: 180px;
}

.bbp-org-select {
  width: 280px;
}

.access-role-select {
  width: 180px;
}

.role-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.access-error {
  display: flex;
  padding: 12px 16px 0;
}

.access-error .el-alert {
  width: 100%;
}
</style>
