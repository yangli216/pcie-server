<template>
  <div v-if="provider === 'openai-compatible'" class="form-grid">
    <el-form-item label="录音识别方式">
      <el-select :value="mode" aria-label="录音识别方式" @input="$emit('update:mode', $event)">
        <el-option label="录音结束后识别" value="batch" />
        <el-option label="分段识别（准实时）" value="segmented" />
      </el-select>
      <p class="form-hint">分段识别使用下方同一语音模型，在录音中显示草稿，停止后完整转写校正。</p>
    </el-form-item>
    <el-form-item label="分段时长（秒）">
      <el-input-number
        :value="durationMs / 1000"
        :min="2" :max="15" :step="1" :precision="1"
        :disabled="mode !== 'segmented'"
        aria-label="分段时长（秒）"
        @input="$emit('update:durationMs', Math.round($event * 1000))"
      />
      <p class="form-hint">默认 4 秒，范围 2–15 秒。越短调用越频繁；文字还需等待模型响应。</p>
    </el-form-item>
  </div>
</template>

<script>
export default {
  props: {
    provider: { type: String, required: true },
    mode: { type: String, default: 'batch' },
    durationMs: { type: Number, default: 4000 }
  }
}
</script>

<style scoped>
.form-hint { color: #6b7280; font-size: 12px; line-height: 1.6; margin: 6px 0 0; }
</style>
