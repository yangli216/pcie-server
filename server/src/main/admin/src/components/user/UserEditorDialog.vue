<script>
import { SegmentedSwitch } from '../ui'

const statusOptions = [
  { value: '1', label: '启用' },
  { value: '0', label: '停用' }
]

function copyForm(value) {
  const source = value || {}
  return {
    idUser: source.idUser || '',
    cdUser: source.cdUser || '',
    naUser: source.naUser || '',
    password: source.password || '',
    idOrg: source.idOrg || '',
    roleIds: Array.isArray(source.roleIds) ? source.roleIds.slice() : [],
    sdStatus: source.sdStatus || '1'
  }
}

export default {
  name: 'UserEditorDialog',
  components: { SegmentedSwitch },
  props: {
    visible: { type: Boolean, default: false },
    mode: { type: String, default: 'create' },
    value: { type: Object, default: () => ({}) },
    orgOptions: { type: Array, default: () => [] },
    roleOptions: { type: Array, default: () => [] },
    orgLoading: { type: Boolean, default: false },
    saving: { type: Boolean, default: false }
  },
  data() {
    return {
      form: copyForm(this.value),
      statusOptions,
      rules: {
        cdUser: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
        naUser: [{ required: true, message: '请输入用户姓名', trigger: 'blur' }],
        idOrg: [{ required: true, message: '请选择所属机构', trigger: 'change' }],
        roleIds: [{ type: 'array', required: true, message: '请选择至少一个角色', trigger: 'change' }]
      }
    }
  },
  computed: {
    dialogTitle() {
      return this.mode === 'create' ? '新增用户' : '编辑用户'
    }
  },
  watch: {
    visible: {
      immediate: true,
      handler(value) {
        if (!value) {
          return
        }
        this.form = copyForm(this.value)
        this.$nextTick(() => {
          if (this.$refs.formRef) {
            this.$refs.formRef.clearValidate()
          }
        })
      }
    }
  },
  methods: {
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
    resolveOrgLabel(org) {
      const name = String((org && org.naOrg) || '').trim()
      const id = String((org && org.idOrg) || '').trim()
      if (name && id) {
        return `${name}（${id}）`
      }
      return name || id || '--'
    },
    close() {
      this.$emit('update:visible', false)
    },
    resetForm() {
      this.form = copyForm({})
    },
    submit() {
      this.$refs.formRef.validate(valid => {
        if (!valid) {
          return
        }
        if (this.mode === 'create' && !this.form.password) {
          this.$message.error('请输入登录密码')
          return
        }
        this.$emit('submit', copyForm(this.form))
      })
    }
  }
}
</script>

<template>
  <el-dialog
    v-if="visible"
    :title="dialogTitle"
    :visible="visible"
    width="760px"
    @close="close"
    @closed="resetForm"
  >
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
        <el-form-item label="所属机构" prop="idOrg">
          <el-select
            v-model="form.idOrg"
            clearable
            filterable
            :loading="orgLoading"
            placeholder="请选择所属机构…"
            no-data-text="暂无可用机构"
          >
            <el-option
              v-for="item in orgOptions"
              :key="item.idOrg"
              :label="resolveOrgLabel(item)"
              :value="item.idOrg"
            />
          </el-select>
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
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </span>
  </el-dialog>
</template>
