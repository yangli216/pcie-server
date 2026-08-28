<template>
  <div class="page-surface lis-entry-page">
    <section class="page-section page-section--padded page-section--toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="申请单号、项目、患者或诊断…"
          class="search-input"
          @keyup.enter.native="search"
        />
        <el-select v-model="filters.dispType" clearable placeholder="类别…" class="filter-select">
          <el-option v-for="item in dispTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.businessType" clearable placeholder="门诊/住院…" class="filter-select">
          <el-option v-for="item in businessTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="申请状态…" class="filter-select">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.idOrg" clearable filterable placeholder="机构…" class="filter-select">
          <el-option v-for="item in orgOptions" :key="item.idOrg" :label="item.naOrg" :value="item.idOrg" />
        </el-select>
        <el-date-picker
          v-model="filters.dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="yyyy-MM-dd"
          class="filter-date"
          clearable
        />
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-button icon="el-icon-refresh" :loading="loading" @click="loadData">刷新</el-button>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading">
        <el-table-column label="申请单号" min-width="150">
          <template slot-scope="{ row }">
            <code-tag :value="row.cdApply || row.idApply" />
          </template>
        </el-table-column>
        <el-table-column label="类别" width="88">
          <template slot-scope="{ row }">
            <status-pill :tone="row.sdDisp === '2' ? 'muted' : 'success'" :label="dispTypeLabel(row.sdDisp)" />
          </template>
        </el-table-column>
        <el-table-column label="业务" width="88">
          <template slot-scope="{ row }">
            <status-pill :tone="row.sdBusiness === '2' ? 'warning' : 'muted'" :label="businessTypeLabel(row.sdBusiness)" />
          </template>
        </el-table-column>
        <el-table-column prop="naApply" label="申请项目" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template slot-scope="{ row }">
            <status-pill :tone="statusTone(applyStatusMeta(row.sdApply).type)" :label="applyStatusMeta(row.sdApply).label" />
          </template>
        </el-table-column>
        <el-table-column label="加急" width="78">
          <template slot-scope="{ row }">
            <status-pill v-if="row.fgUrgent === '1'" tone="warning" label="加急" />
            <span v-else class="muted-text">普通</span>
          </template>
        </el-table-column>
        <el-table-column label="患者/就诊" min-width="160">
          <template slot-scope="{ row }">
            <div class="cell-stack">
              <span>{{ row.idPi || '--' }}</span>
              <span>{{ row.idVis || row.idReg || '--' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="nasDiag" label="诊断" min-width="200" show-overflow-tooltip />
        <el-table-column label="执行科室/医生" min-width="160">
          <template slot-scope="{ row }">
            <div class="cell-stack">
              <span>{{ row.naDeptExec || '--' }}</span>
              <span>{{ row.naDocExec || '--' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="机构" min-width="130">
          <template slot-scope="{ row }">
            {{ resolveOrgName(row.idOrg) }}
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template slot-scope="{ row }">
            {{ formatDateTime(row.insertTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="136" fixed="right">
          <template slot-scope="{ row }">
            <div class="table-actions">
              <table-action @click="openEntry(row)">录入结果</table-action>
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
      width="1180px"
      top="5vh"
      custom-class="lis-entry-dialog"
      @closed="resetDialog"
    >
      <div class="lis-entry-layout" :class="{ 'lis-entry-layout--single': isPacsApply }">
        <aside v-if="isLisApply" class="preset-panel" aria-label="常用检验项目模板">
          <button
            v-for="preset in presets"
            :key="preset.name"
            type="button"
            class="preset-button"
            :class="{ 'is-active': activePresetName === preset.name }"
            @click="usePreset(preset)"
          >
            <span>{{ preset.name }}</span>
            <small>{{ preset.items.length }} 项</small>
          </button>
        </aside>

        <div class="entry-main">
          <div class="apply-summary">
            <div>
              <span class="summary-label">申请单</span>
              <strong>{{ currentApply.cdApply || currentApply.idApply }}</strong>
            </div>
            <div>
              <span class="summary-label">项目</span>
              <strong>{{ currentApply.naApply || '--' }}</strong>
            </div>
            <div>
              <span class="summary-label">患者</span>
              <strong>{{ currentApply.idPi || '--' }}</strong>
            </div>
            <div>
              <span class="summary-label">诊断</span>
              <strong>{{ currentApply.nasDiag || '--' }}</strong>
            </div>
          </div>

          <el-form v-if="isLisApply" :model="form" label-position="top" @submit.native.prevent="submitReport">
            <div class="form-grid result-meta-grid">
              <el-form-item label="报告医生">
                <el-input v-model.trim="form.reportDoctor" maxlength="128" placeholder="报告医生…" />
              </el-form-item>
              <el-form-item label="审核医生">
                <el-input v-model.trim="form.auditDoctor" maxlength="128" placeholder="审核医生…" />
              </el-form-item>
              <el-form-item label="仪器编号">
                <el-input v-model.trim="form.instrumentCode" maxlength="128" placeholder="MANUAL" />
              </el-form-item>
              <el-form-item label="仪器名称">
                <el-input v-model.trim="form.instrumentName" maxlength="256" placeholder="手工录入" />
              </el-form-item>
            </div>
          </el-form>

          <el-form v-else :model="pacsForm" label-position="top" @submit.native.prevent="submitReport">
            <div class="form-grid result-meta-grid">
              <el-form-item label="报告医生">
                <el-input v-model.trim="pacsForm.reportDoctor" maxlength="128" placeholder="报告医生…" />
              </el-form-item>
              <el-form-item label="审核医生">
                <el-input v-model.trim="pacsForm.auditDoctor" maxlength="128" placeholder="审核医生…" />
              </el-form-item>
              <el-form-item label="影像号">
                <el-input v-model.trim="pacsForm.cdStudy" maxlength="128" placeholder="影像号…" />
              </el-form-item>
              <el-form-item label="阴阳性">
                <el-select v-model="pacsForm.negativePositive" clearable placeholder="请选择…">
                  <el-option label="阴性" value="阴性" />
                  <el-option label="阳性" value="阳性" />
                  <el-option label="未见异常" value="未见异常" />
                  <el-option label="异常" value="异常" />
                </el-select>
              </el-form-item>
              <el-form-item label="报告科室编码">
                <el-select v-model="pacsForm.idDept" clearable filterable allow-create placeholder="报告科室编码…" @change="handlePacsDeptChange">
                  <el-option v-for="item in pacsDeptOptions" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
              <el-form-item label="报告科室名称">
                <el-input v-model.trim="pacsForm.naDept" maxlength="128" placeholder="报告科室名称…" />
              </el-form-item>
              <el-form-item label="临床印象" class="form-span-2">
                <el-input v-model.trim="pacsForm.clinicalImpression" type="textarea" :rows="2" maxlength="1000" placeholder="临床印象…" />
              </el-form-item>
              <el-form-item label="检查结果" class="form-span-2">
                <el-input v-model.trim="pacsForm.result" type="textarea" :rows="5" placeholder="检查所见或结果正文…" />
              </el-form-item>
              <el-form-item label="影像诊断" class="form-span-2">
                <el-input v-model.trim="pacsForm.diagnosticImaging" type="textarea" :rows="4" placeholder="影像诊断…" />
              </el-form-item>
              <el-form-item label="备注" class="form-span-2">
                <el-input v-model.trim="pacsForm.remark" type="textarea" :rows="2" maxlength="1000" placeholder="备注…" />
              </el-form-item>
            </div>
          </el-form>

          <div v-if="isLisApply" class="result-toolbar">
            <div class="muted-text">已录入 {{ validItemCount }} 项</div>
            <div class="result-toolbar__actions">
              <el-button size="mini" icon="el-icon-plus" @click="addCustomItem">新增指标</el-button>
              <el-button size="mini" icon="el-icon-delete" @click="removeEmptyItems">清理空行</el-button>
            </div>
          </div>

          <el-table v-if="isLisApply" :data="form.items" class="result-table">
            <el-table-column label="编码" width="112">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.cdResult" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="指标" min-width="150">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.naResult" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="定量结果" width="130">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.testResult" size="mini" @input="syncHint(row)" />
              </template>
            </el-table-column>
            <el-table-column label="定性结果" width="120">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.resultQualitative" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="参考范围" width="130">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.referenceRange" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="下限" width="90">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.referenceLow" size="mini" @input="syncHint(row)" />
              </template>
            </el-table-column>
            <el-table-column label="上限" width="90">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.referenceHigh" size="mini" @input="syncHint(row)" />
              </template>
            </el-table-column>
            <el-table-column label="单位" width="105">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.resultUnit" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="提示" width="95">
              <template slot-scope="{ row }">
                <el-input v-model.trim="row.resultHint" size="mini" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70" fixed="right">
              <template slot-scope="{ $index }">
                <button type="button" class="icon-action" aria-label="删除指标" @click="removeItem($index)">
                  <i class="el-icon-delete" aria-hidden="true"></i>
                </button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReport">回写报告</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchOrgs } from '../api/reference'
import http from '../api/http'
import { getAdminUser } from '../utils/auth'
import { buildLabelMap, formatDateTime, statusTone } from '../utils/admin'
import { CodeTag, StatusPill, TableAction } from '../components/ui'

const applyStatusOptions = [
  { value: '0', label: '新建', type: 'info' },
  { value: '1', label: '已提交', type: 'warning' },
  { value: '2', label: '已执行', type: 'success' },
  { value: '3', label: '已报告', type: 'success' },
  { value: '9', label: '已作废', type: 'danger' }
]

const dispTypeOptions = [
  { value: '1', label: '检验' },
  { value: '2', label: '检查' }
]

const businessTypeOptions = [
  { value: '1', label: '门诊' },
  { value: '2', label: '住院' }
]

const pacsDeptOptions = [
  { value: 'A1', label: '放射科-A1' },
  { value: 'A2', label: '超声科-A2' },
  { value: 'A3', label: '内镜中心-A3' },
  { value: 'A4', label: '心电室-A4' }
]

const presets = [
  {
    name: '血常规(五分类)',
    items: [
      ['WBC', '白细胞计数', '3.5-9.5', '3.5', '9.5', '10^9/L'],
      ['RBC', '红细胞计数', '3.8-5.8', '3.8', '5.8', '10^12/L'],
      ['HGB', '血红蛋白', '115-175', '115', '175', 'g/L'],
      ['HCT', '红细胞压积', '35-50', '35', '50', '%'],
      ['MCV', '平均红细胞体积', '82-100', '82', '100', 'fL'],
      ['MCH', '平均红细胞血红蛋白含量', '27-34', '27', '34', 'pg'],
      ['MCHC', '平均红细胞血红蛋白浓度', '316-354', '316', '354', 'g/L'],
      ['RDW-CV', '红细胞分布宽度变异系数', '11.5-14.5', '11.5', '14.5', '%'],
      ['PLT', '血小板计数', '125-350', '125', '350', '10^9/L'],
      ['NEUT%', '中性粒细胞百分比', '40-75', '40', '75', '%'],
      ['LYMPH%', '淋巴细胞百分比', '20-50', '20', '50', '%'],
      ['MONO%', '单核细胞百分比', '3-10', '3', '10', '%'],
      ['EO%', '嗜酸性粒细胞百分比', '0.4-8.0', '0.4', '8.0', '%'],
      ['BASO%', '嗜碱性粒细胞百分比', '0-1.0', '0', '1.0', '%'],
      ['NEUT#', '中性粒细胞绝对值', '1.8-6.3', '1.8', '6.3', '10^9/L'],
      ['LYMPH#', '淋巴细胞绝对值', '1.1-3.2', '1.1', '3.2', '10^9/L'],
      ['MONO#', '单核细胞绝对值', '0.1-0.6', '0.1', '0.6', '10^9/L'],
      ['EO#', '嗜酸性粒细胞绝对值', '0.02-0.52', '0.02', '0.52', '10^9/L'],
      ['BASO#', '嗜碱性粒细胞绝对值', '0-0.06', '0', '0.06', '10^9/L']
    ]
  },
  {
    name: '尿常规',
    items: [
      ['LEU', '白细胞', '阴性', '', '', ''],
      ['NIT', '亚硝酸盐', '阴性', '', '', ''],
      ['PRO', '尿蛋白', '阴性', '', '', ''],
      ['GLU', '尿糖', '阴性', '', '', ''],
      ['KET', '酮体', '阴性', '', '', ''],
      ['BLD', '潜血', '阴性', '', '', ''],
      ['BIL', '胆红素', '阴性', '', '', ''],
      ['URO', '尿胆原', '阴性', '', '', ''],
      ['SG', '尿比重', '1.003-1.030', '1.003', '1.030', ''],
      ['PH', '尿酸碱度', '5.0-8.0', '5.0', '8.0', ''],
      ['VC', '维生素C', '阴性', '', '', '']
    ]
  },
  {
    name: '电解质全套',
    items: [
      ['K', '钾', '3.5-5.3', '3.5', '5.3', 'mmol/L'],
      ['NA', '钠', '137-147', '137', '147', 'mmol/L'],
      ['CL', '氯', '99-110', '99', '110', 'mmol/L'],
      ['CA', '钙', '2.11-2.52', '2.11', '2.52', 'mmol/L'],
      ['MG', '镁', '0.75-1.02', '0.75', '1.02', 'mmol/L'],
      ['P', '无机磷', '0.85-1.51', '0.85', '1.51', 'mmol/L'],
      ['CO2CP', '二氧化碳结合力', '22-29', '22', '29', 'mmol/L']
    ]
  },
  {
    name: '血脂7项',
    items: [
      ['TC', '总胆固醇', '2.8-5.2', '2.8', '5.2', 'mmol/L'],
      ['TG', '甘油三酯', '0.56-1.70', '0.56', '1.70', 'mmol/L'],
      ['HDL-C', '高密度脂蛋白胆固醇', '1.04-1.55', '1.04', '1.55', 'mmol/L'],
      ['LDL-C', '低密度脂蛋白胆固醇', '0-3.37', '0', '3.37', 'mmol/L'],
      ['APOA1', '载脂蛋白A1', '1.00-1.60', '1.00', '1.60', 'g/L'],
      ['APOB', '载脂蛋白B', '0.60-1.10', '0.60', '1.10', 'g/L'],
      ['LPA', '脂蛋白(a)', '0-300', '0', '300', 'mg/L']
    ]
  },
  {
    name: '肝功能（免费）',
    items: [
      ['ALT', '丙氨酸氨基转移酶', '9-50', '9', '50', 'U/L'],
      ['AST', '天门冬氨酸氨基转移酶', '15-40', '15', '40', 'U/L'],
      ['TBIL', '总胆红素', '3.4-20.5', '3.4', '20.5', 'umol/L'],
      ['DBIL', '直接胆红素', '0-6.8', '0', '6.8', 'umol/L'],
      ['IBIL', '间接胆红素', '1.7-13.7', '1.7', '13.7', 'umol/L'],
      ['TP', '总蛋白', '65-85', '65', '85', 'g/L'],
      ['ALB', '白蛋白', '40-55', '40', '55', 'g/L'],
      ['GLOB', '球蛋白', '20-40', '20', '40', 'g/L'],
      ['A/G', '白球比', '1.2-2.4', '1.2', '2.4', ''],
      ['ALP', '碱性磷酸酶', '45-125', '45', '125', 'U/L'],
      ['GGT', '谷氨酰转肽酶', '10-60', '10', '60', 'U/L']
    ]
  },
  {
    name: '糖化血红蛋白测定',
    items: [
      ['HbA1c', '糖化血红蛋白', '4.0-6.0', '4.0', '6.0', '%']
    ]
  },
  {
    name: '空腹血糖（静脉）',
    items: [
      ['GLU', '空腹血糖', '3.9-6.1', '3.9', '6.1', 'mmol/L']
    ]
  },
  {
    name: 'C-反应蛋白测定（CRP）',
    items: [
      ['CRP', 'C-反应蛋白', '0-10', '0', '10', 'mg/L']
    ]
  },
  {
    name: '肾功能',
    items: [
      ['CREA', '肌酐', '57-111', '57', '111', 'umol/L'],
      ['UREA', '尿素', '3.1-8.0', '3.1', '8.0', 'mmol/L'],
      ['UA', '尿酸', '208-428', '208', '428', 'umol/L'],
      ['eGFR', '估算肾小球滤过率', '>90', '90', '', 'ml/min/1.73m2']
    ]
  },
  {
    name: '血糖血脂',
    items: [
      ['GLU', '空腹血糖', '3.9-6.1', '3.9', '6.1', 'mmol/L'],
      ['TC', '总胆固醇', '2.8-5.2', '2.8', '5.2', 'mmol/L'],
      ['TG', '甘油三酯', '0.56-1.70', '0.56', '1.70', 'mmol/L'],
      ['HDL-C', '高密度脂蛋白胆固醇', '1.04-1.55', '1.04', '1.55', 'mmol/L'],
      ['LDL-C', '低密度脂蛋白胆固醇', '0-3.37', '0', '3.37', 'mmol/L']
    ]
  },
  {
    name: '炎症/心肌',
    items: [
      ['CRP', 'C-反应蛋白', '0-10', '0', '10', 'mg/L'],
      ['PCT', '降钙素原', '0-0.05', '0', '0.05', 'ng/mL'],
      ['CK-MB', '肌酸激酶同工酶', '0-24', '0', '24', 'U/L'],
      ['cTnI', '肌钙蛋白I', '0-0.04', '0', '0.04', 'ng/mL']
    ]
  }
]

function createEmptyItem() {
  return {
    cdResult: '',
    naResult: '',
    testResult: '',
    resultQualitative: '',
    referenceRange: '',
    referenceLow: '',
    referenceHigh: '',
    resultUnit: '',
    resultHint: '',
    __key: `${Date.now()}-${Math.random()}`
  }
}

function createItemFromPreset(item) {
  return {
    cdResult: item[0],
    naResult: item[1],
    testResult: '',
    resultQualitative: '',
    referenceRange: item[2],
    referenceLow: item[3],
    referenceHigh: item[4],
    resultUnit: item[5],
    resultHint: '',
    __key: `${item[0]}-${Date.now()}-${Math.random()}`
  }
}

function createDefaultForm(userName) {
  return {
    reportDoctor: userName || '',
    auditDoctor: userName || '',
    instrumentCode: 'MANUAL',
    instrumentName: '手工录入',
    items: presets[0].items.map(createItemFromPreset)
  }
}

function createDefaultPacsForm(userName) {
  return {
    reportDoctor: userName || '',
    auditDoctor: userName || '',
    result: '',
    remark: '',
    clinicalImpression: '',
    negativePositive: '',
    diagnosticImaging: '',
    cdStudy: '',
    idDept: '',
    naDept: ''
  }
}

function formatLocalDate(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function defaultDateRange() {
  const end = new Date()
  const start = new Date()
  start.setDate(end.getDate() - 2)
  return [formatLocalDate(start), formatLocalDate(end)]
}

export default {
  components: {
    CodeTag,
    StatusPill,
    TableAction
  },
  data() {
    const adminUser = getAdminUser()
    const userName = adminUser ? (adminUser.naUser || adminUser.cdUser || '') : ''
    return {
      loading: false,
      saving: false,
      dialogVisible: false,
      current: 1,
      size: 10,
      total: 0,
      records: [],
      orgOptions: [],
      orgMap: {},
      filters: {
        keyword: '',
        dispType: '',
        businessType: '',
        status: '',
        idOrg: '',
        dateRange: defaultDateRange()
      },
      currentApply: {},
      form: createDefaultForm(userName),
      pacsForm: createDefaultPacsForm(userName),
      currentUserName: userName,
      dispTypeOptions,
      businessTypeOptions,
      statusOptions: applyStatusOptions.filter(item => item.value === '0' || item.value === '1' || item.value === '2'),
      pacsDeptOptions,
      presets,
      activePresetName: presets[0].name
    }
  },
  computed: {
    isLisApply() {
      return this.currentApply && this.currentApply.sdDisp !== '2'
    },
    isPacsApply() {
      return this.currentApply && this.currentApply.sdDisp === '2'
    },
    dialogTitle() {
      const type = this.isPacsApply ? '检查报告' : '检验结果'
      return this.currentApply && this.currentApply.naApply ? `录入${type} - ${this.currentApply.naApply}` : `录入${type}`
    },
    validItemCount() {
      return this.collectValidItems().length
    }
  },
  async mounted() {
    await this.loadReferences()
    this.loadData()
  },
  methods: {
    formatDateTime,
    statusTone,
    applyStatusMeta(value) {
      return applyStatusOptions.find(item => item.value === value) || { label: value || '--', type: 'info' }
    },
    dispTypeLabel(value) {
      const item = dispTypeOptions.find(option => option.value === value)
      return item ? item.label : value || '--'
    },
    businessTypeLabel(value) {
      const item = businessTypeOptions.find(option => option.value === value)
      return item ? item.label : value || '--'
    },
    resolveOrgName(idOrg) {
      return idOrg ? this.orgMap[idOrg] || idOrg : '--'
    },
    async loadReferences() {
      try {
        const orgs = await fetchOrgs()
        this.orgOptions = orgs
        this.orgMap = buildLabelMap(orgs, 'idOrg', 'naOrg')
      } catch (error) {
        this.$message.error(error.message || '加载机构失败')
      }
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/exam-result-entry/applies', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.filters.keyword || undefined,
            dispType: this.filters.dispType || undefined,
            businessType: this.filters.businessType || undefined,
            status: this.filters.status || undefined,
            idOrg: this.filters.idOrg || undefined,
            dateFrom: this.filters.dateRange && this.filters.dateRange[0] ? this.filters.dateRange[0] : undefined,
            dateTo: this.filters.dateRange && this.filters.dateRange[1] ? this.filters.dateRange[1] : undefined
          }
        })
        this.records = data.records || []
        this.total = data.total || 0
      } catch (error) {
        this.$message.error(error.message || '加载申请单失败')
      } finally {
        this.loading = false
      }
    },
    search() {
      this.current = 1
      this.loadData()
    },
    reset() {
      this.filters = {
        keyword: '',
        dispType: '',
        businessType: '',
        status: '',
        idOrg: '',
        dateRange: defaultDateRange()
      }
      this.current = 1
      this.loadData()
    },
    openEntry(row) {
      this.currentApply = row || {}
      this.form = createDefaultForm(this.currentUserName)
      this.pacsForm = createDefaultPacsForm(this.currentUserName)
      this.activePresetName = presets[0].name
      this.dialogVisible = true
    },
    resetDialog() {
      this.currentApply = {}
      this.form = createDefaultForm(this.currentUserName)
      this.pacsForm = createDefaultPacsForm(this.currentUserName)
      this.activePresetName = presets[0].name
      this.saving = false
    },
    usePreset(preset) {
      this.activePresetName = preset.name
      this.form.items = preset.items.map(createItemFromPreset)
    },
    addCustomItem() {
      this.form.items.push(createEmptyItem())
    },
    removeItem(index) {
      this.form.items.splice(index, 1)
      if (!this.form.items.length) {
        this.addCustomItem()
      }
    },
    removeEmptyItems() {
      const next = this.form.items.filter(item => {
        return item.naResult || item.testResult || item.resultQualitative
      })
      this.form.items = next.length ? next : [createEmptyItem()]
    },
    collectValidItems() {
      return this.form.items.filter(item => {
        return item && item.naResult && (item.testResult || item.resultQualitative)
      })
    },
    syncHint(row) {
      const value = Number(row.testResult)
      if (Number.isNaN(value)) {
        return
      }
      const low = row.referenceLow === '' ? null : Number(row.referenceLow)
      const high = row.referenceHigh === '' ? null : Number(row.referenceHigh)
      if (low !== null && !Number.isNaN(low) && value < low) {
        row.resultHint = '↓'
        return
      }
      if (high !== null && !Number.isNaN(high) && value > high) {
        row.resultHint = '↑'
        return
      }
      if (row.resultHint === '↑' || row.resultHint === '↓') {
        row.resultHint = ''
      }
    },
    handlePacsDeptChange(value) {
      const item = pacsDeptOptions.find(option => option.value === value)
      if (item) {
        this.pacsForm.naDept = item.label.split('-')[0]
      }
    },
    submitReport() {
      if (this.isPacsApply) {
        this.submitPacsReport()
        return
      }
      const items = this.collectValidItems()
      if (!items.length) {
        this.$message.error('至少录入一项检验结果')
        return
      }
      this.$confirm(`确认回写 ${items.length} 项检验结果到申请单「${this.currentApply.cdApply || this.currentApply.idApply}」吗？`, '提示', {
        type: 'warning'
      }).then(async () => {
        this.saving = true
        try {
          await http.post(`/admin/api/exam-result-entry/applies/${this.currentApply.idApply}/lis-report`, {
            reportDoctor: this.form.reportDoctor || undefined,
            auditDoctor: this.form.auditDoctor || undefined,
            instrumentCode: this.form.instrumentCode || undefined,
            instrumentName: this.form.instrumentName || undefined,
            items: items.map(item => ({
              cdResult: item.cdResult,
              naResult: item.naResult,
              testResult: item.testResult,
              resultQualitative: item.resultQualitative,
              referenceRange: item.referenceRange,
              referenceLow: item.referenceLow,
              referenceHigh: item.referenceHigh,
              resultUnit: item.resultUnit,
              resultHint: item.resultHint
            }))
          })
          this.$message.success('回写成功')
          this.dialogVisible = false
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || '回写失败')
        } finally {
          this.saving = false
        }
      }).catch(() => {})
    },
    submitPacsReport() {
      if (!this.pacsForm.result && !this.pacsForm.diagnosticImaging) {
        this.$message.error('检查结果或影像诊断至少填写一项')
        return
      }
      this.$confirm(`确认回写检查报告到申请单「${this.currentApply.cdApply || this.currentApply.idApply}」吗？`, '提示', {
        type: 'warning'
      }).then(async () => {
        this.saving = true
        try {
          await http.post(`/admin/api/exam-result-entry/applies/${this.currentApply.idApply}/pacs-report`, {
            result: this.pacsForm.result || undefined,
            remark: this.pacsForm.remark || undefined,
            clinicalImpression: this.pacsForm.clinicalImpression || undefined,
            negativePositive: this.pacsForm.negativePositive || undefined,
            diagnosticImaging: this.pacsForm.diagnosticImaging || undefined,
            cdStudy: this.pacsForm.cdStudy || undefined,
            idDept: this.pacsForm.idDept || undefined,
            naDept: this.pacsForm.naDept || undefined,
            reportDoctor: this.pacsForm.reportDoctor || undefined,
            auditDoctor: this.pacsForm.auditDoctor || undefined
          })
          this.$message.success('回写成功')
          this.dialogVisible = false
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || '回写失败')
        } finally {
          this.saving = false
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.lis-entry-page .cell-stack {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.lis-entry-page .cell-stack span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lis-entry-layout {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr);
  gap: 16px;
}

.lis-entry-layout--single {
  grid-template-columns: minmax(0, 1fr);
}

.preset-panel {
  display: grid;
  align-content: start;
  gap: 8px;
}

.preset-button {
  width: 100%;
  min-height: 42px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 10px;
  border: 1px solid var(--border-color-base);
  border-radius: 8px;
  background: #fff;
  color: var(--color-text-regular);
  cursor: pointer;
  font-size: 13px;
  transition: border-color 0.16s ease, background-color 0.16s ease, color 0.16s ease;
}

.preset-button:hover,
.preset-button.is-active {
  border-color: #c8e8dc;
  background: var(--color-primary-soft);
  color: var(--color-primary-hover);
}

.preset-button small {
  flex: 0 0 auto;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.preset-button span {
  min-width: 0;
  overflow-wrap: anywhere;
  text-align: left;
}

.entry-main {
  min-width: 0;
}

.apply-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px;
  border: 1px solid var(--border-color-light);
  border-radius: 8px;
  background: var(--background-color-base);
}

.apply-summary div {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.summary-label {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.apply-summary strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
}

.result-meta-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.result-meta-grid .el-select {
  width: 100%;
}

.result-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 4px 0 10px;
}

.result-toolbar__actions {
  display: flex;
  gap: 8px;
}

.result-table {
  width: 100%;
}

.icon-action {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--color-danger);
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.icon-action:hover {
  background: var(--color-danger-soft);
  color: #791F1F;
}

@media (max-width: 980px) {
  .lis-entry-layout {
    grid-template-columns: 1fr;
  }

  .preset-panel {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .apply-summary,
  .result-meta-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .preset-panel,
  .apply-summary,
  .result-meta-grid {
    grid-template-columns: 1fr;
  }

  .result-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
