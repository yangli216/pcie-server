<template>
  <el-form class="rule-editor" label-position="top" :disabled="readonly || busy">
    <el-form-item class="inline-item" label="是否展示">
      <segmented-switch v-model="form.visible" :options="[{ label: '是', value: true }, { label: '否', value: false }]" />
      <small>控制 PCIE 病历界面上的字段展示</small>
    </el-form-item>
    <el-form-item class="inline-item" label="生成类型">
      <el-radio-group v-model="form.generationMode" size="small">
        <el-radio-button label="inherit">使用默认</el-radio-button>
        <el-radio-button label="ai" :disabled="!!detail.restriction">AI</el-radio-button>
        <el-radio-button label="manual_or_his">人工/HIS</el-radio-button>
      </el-radio-group>
      <small>默认 {{ detail.defaultAiSuitable ? 'AI' : '人工/HIS' }}<template v-if="detail.restriction"> · {{ detail.restriction }}</template></small>
    </el-form-item>
    <el-form-item class="inline-item" label="字段含义">
      <el-input v-model="form.meaningOverride" clearable :placeholder="detail.defaultMeaning" maxlength="2000" />
    </el-form-item>
    <template v-if="ai">
      <div class="rule-comparison">
        <section><strong>默认提示词</strong><p>{{ detail.defaultPrompt }}</p></section>
        <section><strong>当前生效提示词</strong><small>{{ detail.effective.promptSource === 'custom' ? '已维护' : detail.effective.aiSuitable ? '默认' : '人工/HIS' }}</small><p>{{ detail.effective.resolvedPrompt || '当前由人工或 HIS 填写' }}</p></section>
      </div>
      <el-form-item label="附加正文约束"><el-input v-model="form.additionalConstraint" type="textarea" :rows="2" maxlength="16000" placeholder="例如：持续时间由相邻栏填写" /></el-form-item>
      <div class="rule-label"><label for="outpatient-rule-body">正文草稿</label><el-button type="text" icon="el-icon-edit" :loading="drafting" @click="generate">生成草稿</el-button></div>
      <el-form-item>
        <el-input id="outpatient-rule-body" v-model="form.customBody" type="textarea" :rows="3" maxlength="16000" :placeholder="detail.defaultBody" />
      </el-form-item>
      <div class="rule-preview"><strong>保存后预览</strong><p>{{ preview }}</p></div>
    </template>
    <p v-else>本栏由人工或 HIS 填写。已维护的提示词保留供再次启用。</p>
    <div class="rule-footer">
      <el-button v-if="ai" type="text" @click="restore">恢复默认提示词</el-button>
      <span></span><el-button @click="$emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存书写规则</el-button>
    </div>
  </el-form>
</template>

<script>
import http from '../../api/http'
import { SegmentedSwitch } from '../ui'

export default {
  name: 'OutpatientFieldRuleEditor',
  components: { SegmentedSwitch },
  props: { detail: { type: Object, required: true }, snapshotId: String, revision: Number, readonly: Boolean },
  data() { return { form: { ...this.detail.settings, visible: this.detail.settings.visible !== false }, saving: false, drafting: false } },
  computed: {
    ai() { return !this.detail.restriction && (this.form.generationMode === 'inherit' ? this.detail.defaultAiSuitable : this.form.generationMode === 'ai') },
    busy() { return this.saving || this.drafting },
    preview() {
      // The compiler places the effective meaning on the first line of its default body.
      const defaultBody = this.detail.defaultBody.replace('字段含义：' + this.detail.effective.meaning + '。', () =>
        '字段含义：' + (this.form.meaningOverride?.trim() || this.detail.defaultMeaning) + '。')
      return [this.form.customBody?.trim() || defaultBody, this.form.additionalConstraint?.trim(), this.detail.formatRule].filter(Boolean).join('\n')
    }
  },
  methods: {
    payload() {
      return { fieldId: this.detail.effective.fieldId, expectedRevision: this.revision, generationMode: this.form.generationMode, visible: this.form.visible,
        meaningOverride: this.form.meaningOverride?.trim() || null, customBody: this.form.customBody?.trim() || null,
        additionalConstraint: this.form.additionalConstraint?.trim() || null }
    },
    restore() { this.form.customBody = null; this.form.additionalConstraint = null },
    async generate() {
      this.drafting = true
      try {
        const draft = await http.post('/admin/api/outpatient-emr/templates/' + this.snapshotId + '/field-rules/draft', this.payload())
        this.form.customBody = draft.bodyDraft
      } catch (error) { this.$message.error(error.message); this.$emit('reload') }
      finally { this.drafting = false }
    },
    async save() {
      this.saving = true
      try {
        const detail = await http.put('/admin/api/outpatient-emr/templates/' + this.snapshotId + '/field-rules', this.payload())
        this.$emit('saved', detail)
        this.$message.success('当前模板版本的书写规则已保存')
      } catch (error) { this.$message.error(error.message); this.$emit('reload') }
      finally { this.saving = false }
    }
  }
}
</script>

<style scoped>
.rule-editor .el-form-item { margin-bottom: 12px; }
.inline-item { display: flex; align-items: baseline; gap: 8px; }
.inline-item ::v-deep .el-form-item__label { width: 100px; text-align: left; flex-shrink: 0; }
.inline-item ::v-deep .el-form-item__content { flex: 1; min-width: 0; }
.rule-editor ::v-deep .el-form-item__label { padding-bottom: 3px; color: #314138; }
.rule-editor ::v-deep .el-radio-button__orig-radio:checked + .el-radio-button__inner { background: #e1f4e9; color: #126d48; border-color: #269d70; box-shadow: -1px 0 0 #269d70; }
.rule-editor small { margin-left: 12px; color: #61726c; }
.rule-comparison { margin-left: 108px; display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 12px; margin-bottom: 14px; }
.rule-comparison section, .rule-preview { padding: 10px 12px; border: 1px solid #dfe6e1; border-radius: 5px; background: #f5f7f6; }
.rule-preview { background: #edf7f3; display: grid; grid-template-columns: auto 1fr; align-items: start; gap: 14px; }
.rule-preview strong { color: #136c4c; }
.rule-preview p { margin: 0; }
.rule-editor p { margin: 5px 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.5; }
.rule-label, .rule-footer { display: flex; align-items: center; justify-content: space-between; }
.rule-footer { gap: 8px; margin-top: 15px; }
.rule-footer span { flex: 1; }
@media(max-width: 650px) { .rule-comparison { grid-template-columns: 1fr; margin-left: 0; } .inline-item { display: block; } .rule-preview { display: block; } }
</style>
