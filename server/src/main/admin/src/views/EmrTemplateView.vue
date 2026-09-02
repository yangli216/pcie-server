<template>
  <div class="emr-template-view">
    <el-tabs v-model="activeTab" type="card" class="emr-template-tabs">
      <el-tab-pane label="门诊病例模板" name="outpatient">
        <outpatient-emr-template-panel />
      </el-tab-pane>
      <el-tab-pane label="住院病例模板" name="inpatient" lazy>
        <inpatient-emr-template-view />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
import InpatientEmrTemplateView from './InpatientEmrTemplateView.vue'
import OutpatientEmrTemplatePanel from './OutpatientEmrTemplatePanel.vue'

export default {
  name: 'EmrTemplateView',
  components: {
    InpatientEmrTemplateView,
    OutpatientEmrTemplatePanel
  },
  data() {
    return {
      activeTab: 'outpatient'
    }
  }
}
</script>

<style scoped>
.emr-template-view {
  min-width: 0;
}

.emr-template-tabs > ::v-deep .el-tabs__header {
  margin: 0 0 16px;
}

.emr-template-tabs > ::v-deep .el-tabs__content {
  overflow: visible;
}
</style>
