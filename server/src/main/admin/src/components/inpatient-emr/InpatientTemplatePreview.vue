<template>
  <section class="inpatient-template-preview" aria-label="住院病例预渲染">
    <div class="inpatient-template-preview__notice">
      <div>
        <strong>{{ example ? '内置预渲染示例' : '脱敏病例预渲染' }}</strong>
        <span>仅用于检查模板结构和字段回填效果，不读取或保存真实患者数据。</span>
      </div>
      <div class="inpatient-template-preview__legend" aria-label="字段类型图例">
        <span><i class="legend-dot legend-dot--ai"></i>AI 生成字段</span>
        <span><i class="legend-dot legend-dot--readonly"></i>HIS / 人工字段</span>
      </div>
    </div>
    <iframe
      class="inpatient-template-preview__frame"
      sandbox=""
      :srcdoc="previewDocument"
      title="住院病例预渲染"
    ></iframe>
  </section>
</template>

<script>
import { buildInpatientPreviewDocument } from '../../utils/inpatientEmrPreview'

export default {
  name: 'InpatientTemplatePreview',
  props: {
    htmlContent: {
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
      return buildInpatientPreviewDocument(this.htmlContent, this.fields)
    }
  }
}
</script>

<style scoped>
.inpatient-template-preview {
  border: 1px solid #d8e3e0;
  border-radius: 8px;
  overflow: hidden;
  background: #ffffff;
}

.inpatient-template-preview__notice {
  min-height: 54px;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  border-bottom: 1px solid #e7eeec;
  background: #f7fbf9;
  color: #64716e;
  font-size: 13px;
}

.inpatient-template-preview__notice strong {
  display: block;
  margin-bottom: 2px;
  color: #253b35;
  font-size: 14px;
}

.inpatient-template-preview__legend {
  display: flex;
  align-items: center;
  gap: 16px;
  white-space: nowrap;
}

.inpatient-template-preview__legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  border: 1px solid transparent;
}

.legend-dot--ai {
  background: #dff4ec;
  border-color: #63b89a;
}

.legend-dot--readonly {
  background: #eef2f2;
  border-color: #c5d0ce;
}

.inpatient-template-preview__frame {
  display: block;
  width: 100%;
  height: 560px;
  border: 0;
  background: #f3f7f6;
}

@media (max-width: 760px) {
  .inpatient-template-preview__notice {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
