<template>
  <section class="outpatient-template-preview" aria-label="门诊病例预渲染">
    <div class="outpatient-template-preview__notice">
      <div>
        <strong>{{ example ? '内置预渲染示例' : '脱敏病例预渲染' }}</strong>
        <span>按当前有效字段映射填充模拟病例，仅用于检查模板结构和字段回填效果，不读取或保存真实患者数据。</span>
      </div>
      <div class="outpatient-template-preview__legend" aria-label="字段类型图例">
        <span><i class="legend-dot legend-dot--ai"></i>AI 生成字段</span>
        <span><i class="legend-dot legend-dot--readonly"></i>HIS / 人工字段</span>
      </div>
    </div>
    <iframe
      class="outpatient-template-preview__frame"
      sandbox=""
      :srcdoc="previewDocument"
      title="门诊病例预渲染"
    ></iframe>
  </section>
</template>

<script>
import { buildOutpatientPreviewDocument } from '../../utils/outpatientEmrPreview'

export default {
  name: 'OutpatientTemplatePreview',
  props: {
    templateHtml: {
      type: String,
      default: ''
    },
    fields: {
      type: Array,
      default: () => []
    },
    example: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    previewDocument() {
      return buildOutpatientPreviewDocument(this.templateHtml, this.fields)
    }
  }
}
</script>

<style scoped>
.outpatient-template-preview {
  overflow: hidden;
  border: 1px solid #d8e3e0;
  border-radius: 8px;
  background: #ffffff;
}

.outpatient-template-preview__notice {
  min-height: 54px;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid #e7eeec;
  color: #64716e;
  background: #f7fbf9;
  font-size: 13px;
}

.outpatient-template-preview__notice strong {
  display: block;
  margin-bottom: 2px;
  color: #253b35;
  font-size: 14px;
}

.outpatient-template-preview__legend {
  display: flex;
  align-items: center;
  gap: 16px;
  white-space: nowrap;
}

.outpatient-template-preview__legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border: 1px solid transparent;
  border-radius: 3px;
}

.legend-dot--ai {
  border-color: #63b89a;
  background: #dff4ec;
}

.legend-dot--readonly {
  border-color: #c5d0ce;
  background: #eef2f2;
}

.outpatient-template-preview__frame {
  display: block;
  width: 100%;
  height: clamp(340px, calc(92vh - 360px), 540px);
  border: 0;
  background: #f3f7f6;
}

@media (max-width: 760px) {
  .outpatient-template-preview__notice {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
