<template>
  <div class="page-surface symptom-template-page">
    <section class="page-section page-section--padded">
      <div class="page-toolbar symptom-toolbar">
        <div class="page-toolbar__filters symptom-filters">
          <el-select v-model="filters.medicalMode" class="filter-select" @change="handleMedicalModeChange">
            <el-option v-for="item in medicalModeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-input
            v-model.trim="filters.keyword"
            clearable
            placeholder="输入症状名称或标识…"
            class="search-input"
            @keyup.enter.native="searchTemplates"
          />
          <el-select v-model="filters.sdStatus" clearable class="filter-select" placeholder="状态">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-button type="primary" icon="el-icon-search" @click="searchTemplates">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
          <advanced-filter-panel v-model="advancedFiltersOpen">
            <el-select v-model="filters.systemCategory" clearable class="filter-select" placeholder="系统分类">
              <el-option v-for="item in systemCategoryOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-select
              v-model="filters.idRegion"
              clearable
              filterable
              placeholder="所属区域"
              class="filter-select"
              @change="handleFilterRegionChange"
            >
              <el-option v-for="item in regionOptions" :key="item.idRegion" :label="item.naRegion" :value="item.idRegion" />
            </el-select>
            <el-select
              v-model="filters.idOrg"
              clearable
              filterable
              placeholder="所属机构"
              class="filter-select"
              @change="handleFilterOrgChange"
            >
              <el-option v-for="item in filteredOrgOptions" :key="item.idOrg" :label="item.naOrg" :value="item.idOrg" />
            </el-select>
          </advanced-filter-panel>
        </div>
        <div class="symptom-toolbar__actions">
          <el-button icon="el-icon-document" @click="openChangeLogDialog">修改日志</el-button>
          <el-button :loading="importingJson" @click="triggerJsonImport">导入配置文件</el-button>
          <el-button :loading="importing" @click="importBuiltin">导入内置模板</el-button>
          <el-button @click="exportCurrent">导出配置</el-button>
          <el-button type="primary" icon="el-icon-plus" @click="openCreate">新增症状</el-button>
        </div>
      </div>
      <input
        ref="importJsonInput"
        type="file"
        accept=".json,application/json"
        class="hidden-file-input"
        @change="handleJsonFileChange"
      >
    </section>

    <div class="symptom-layout">
      <section class="page-section page-section--padded symptom-sidebar" v-loading="loading">
        <div class="symptom-sidebar__header">
          <div>
            <div class="section-title">症状列表</div>
            <div class="muted-text">共 {{ pagination.total }} 条，当前模式 {{ medicalModeLabel(filters.medicalMode) }}</div>
          </div>
        </div>
        <div class="symptom-list">
          <button
            v-for="item in records"
            :key="item.id"
            type="button"
            class="symptom-item"
            :class="{ 'is-active': selectedId === item.id }"
            @click="selectRecord(item)"
          >
            <div class="symptom-item__title">{{ item.name || '未命名症状' }}</div>
            <div class="symptom-item__meta">{{ item.key || '--' }}</div>
            <div class="symptom-item__tags">
              <span class="inline-tag">{{ statusLabel(item.sdStatus) }}</span>
              <span class="inline-tag">{{ resolveScope(item) }}</span>
            </div>
          </button>
          <div v-if="!records.length" class="empty-state">当前筛选条件下暂无症状模板</div>
        </div>
        <div class="symptom-sidebar__pagination">
          <AdminPagination
            compact
            :current.sync="pagination.current"
            :size.sync="pagination.size"
            :total="pagination.total"
            @change="loadData"
          />
        </div>
      </section>

      <section class="page-section page-section--padded symptom-editor">
        <div v-if="editingRecord" class="editor-shell">
          <div class="editor-header">
            <div>
              <div class="section-title">{{ editingRecord.id ? '编辑症状模板' : '新增症状模板' }}</div>
              
            </div>
            <div class="editor-actions">
              <el-button @click="resetCurrent">重置</el-button>
              <el-button type="danger" plain @click="removeCurrent">删除</el-button>
              <el-button type="primary" :loading="saving" @click="saveCurrent">保存当前症状</el-button>
            </div>
          </div>

          <div class="editor-scroll">
            <section class="editor-section">
              <div class="section-title">基本信息</div>
              <div class="form-grid">
                <div class="form-item">
                  <label>症状名称</label>
                  <el-input v-model.trim="editingRecord.name" maxlength="200" />
                </div>
                <div class="form-item">
                  <label>标识</label>
                  <el-input v-model.trim="editingRecord.key" maxlength="128" />
                </div>
                <div class="form-item">
                  <label>医学模式</label>
                  <el-select v-model="editingRecord.medicalMode" @change="handleDraftMedicalModeChange">
                    <el-option v-for="item in medicalModeOptions" :key="item.value" :label="item.label" :value="item.value" />
                  </el-select>
                </div>
                <div class="form-item">
                  <label>排序号</label>
                  <el-input-number v-model="editingRecord.sortOrder" :min="0" :step="1" />
                </div>
                <div class="form-item">
                  <label>状态</label>
                  <segmented-switch v-model="editingRecord.sdStatus" :options="statusOptions" />
                </div>
                <div class="form-item">
                  <label class="row-label">
                    <input type="checkbox" v-model="editingRecord.isCommonSymptom" />
                    设为常用症状
                  </label>
                </div>
                <div class="form-item full">
                  <label>症状描述</label>
                  <el-input v-model="editingRecord.description" type="textarea" :rows="3" />
                </div>
                <div class="form-item full">
                  <label>系统分类</label>
                  <div class="check-group">
                    <label v-for="item in systemCategoryOptions" :key="item.value" class="check-label">
                      <input type="checkbox" :value="item.value" :checked="editingRecord.systemCategory.includes(item.value)" @change="toggleListValue(editingRecord.systemCategory, item.value, $event.target.checked)" />
                      {{ item.label }}
                    </label>
                  </div>
                </div>
                <div class="form-item full">
                  <label>部位标签</label>
                  <div class="token-editor">
                    <span v-for="(item, index) in editingRecord.bodyParts" :key="item + index" class="token-chip">
                      {{ item }}
                      <button type="button" class="token-chip__remove" @click="editingRecord.bodyParts.splice(index, 1)">×</button>
                    </span>
                    <input
                      v-model.trim="newBodyPart"
                      type="text"
                      class="token-input"
                      placeholder="输入后回车添加部位…"
                      @keyup.enter.prevent="addBodyPart"
                    />
                  </div>
                </div>
                <div class="form-item">
                  <label>所属区域</label>
                  <el-select v-model="editingRecord.idRegion" clearable filterable placeholder="全局" @change="handleDraftRegionChange">
                    <el-option v-for="item in regionOptions" :key="item.idRegion" :label="item.naRegion" :value="item.idRegion" />
                  </el-select>
                </div>
                <div class="form-item">
                  <label>所属机构</label>
                  <el-select v-model="editingRecord.idOrg" clearable filterable placeholder="区域/全局" @change="handleDraftOrgChange">
                    <el-option v-for="item in draftOrgOptions" :key="item.idOrg" :label="item.naOrg" :value="item.idOrg" />
                  </el-select>
                </div>
                <div class="form-item full">
                  <label>适用性别</label>
                  <div class="check-group">
                    <label class="check-label">
                      <input type="checkbox" :checked="getSymptomGenders().includes('1')" @change="toggleListValue(getSymptomGenders(), '1', $event.target.checked)" />
                      男性
                    </label>
                    <label class="check-label">
                      <input type="checkbox" :checked="getSymptomGenders().includes('2')" @change="toggleListValue(getSymptomGenders(), '2', $event.target.checked)" />
                      女性
                    </label>
                  </div>
                </div>
                <div class="form-item full">
                  <label>自定义脚本</label>
                  <el-input v-model="editingRecord.customScript" type="textarea" :rows="3" placeholder="可选，自定义运行脚本…" />
                </div>
              </div>
            </section>

            <section class="editor-section">
              <div class="section-header">
                <div class="section-title">问诊结构</div>
                <div class="section-header__actions">
                  <el-button size="mini" @click="addSection">新增分组</el-button>
                </div>
              </div>
              <div class="muted-text section-desc">保留 desktop disease editor 的“分组 + 字段”结构，客户端会直接消费这里生成的配置。</div>

              <div v-for="(section, sectionIndex) in editingRecord.config.sections" :key="section.id || sectionIndex" class="section-card">
                <div class="section-card__header">
                  <div class="section-card__title">分组 {{ sectionIndex + 1 }}</div>
                  <div class="section-card__actions">
                    <el-button size="mini" @click="addField(section)">新增字段</el-button>
                    <el-button size="mini" type="danger" plain @click="removeSection(sectionIndex)">删除分组</el-button>
                  </div>
                </div>
                <div class="form-grid">
                  <div class="form-item">
                    <label>分组标题</label>
                    <el-input v-model.trim="section.title" maxlength="128" />
                  </div>
                  <div class="form-item">
                    <label>分组标识</label>
                    <el-input v-model.trim="section.id" maxlength="128" />
                  </div>
                  <div class="form-item full">
                    <label>分组描述</label>
                    <el-input v-model="section.description" type="textarea" :rows="2" placeholder="可选，中医模板常用…" />
                  </div>
                </div>

                <div v-for="(field, fieldIndex) in section.fields" :key="field.id || fieldIndex" class="field-card">
                  <div class="field-card__header">
                    <div class="field-card__title">{{ field.label || `字段 ${fieldIndex + 1}` }}</div>
                    <div class="field-card__actions">
                      <el-button size="mini" type="danger" plain @click="removeField(section, fieldIndex)">删除字段</el-button>
                    </div>
                  </div>
                  <div class="form-grid">
                    <div class="form-item">
                      <label>字段标签</label>
                      <el-input v-model.trim="field.label" maxlength="128" />
                    </div>
                    <div class="form-item">
                      <label>字段标识</label>
                      <el-input v-model.trim="field.key" maxlength="128" />
                    </div>
                    <div class="form-item">
                      <label>存储标识</label>
                      <el-input v-model.trim="field.storageKey" maxlength="128" />
                    </div>
                    <div class="form-item">
                      <label>字段类型</label>
                      <el-select v-model="field.type" @change="handleFieldTypeChange(field)">
                        <el-option v-for="item in fieldTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
                      </el-select>
                    </div>
                    <div class="form-item">
                      <label class="row-label">
                        <input type="checkbox" v-model="field.required" />
                        必填字段
                      </label>
                    </div>
                    <div class="form-item full" v-if="usesOptionList(field)">
                      <label>{{ field.type === 'input_radio' ? '单位选项' : '候选项' }}</label>
                      <div class="token-editor">
                        <span v-for="(item, index) in getOptionList(field)" :key="item + index" class="token-chip">
                          {{ item }}
                          <button type="button" class="token-chip__remove" @click="removeOption(field, index)">×</button>
                        </span>
                        <input
                          v-model.trim="field.__newOption"
                          type="text"
                          class="token-input"
                          placeholder="输入后回车添加…"
                          @keyup.enter.prevent="addOption(field)"
                        />
                      </div>
                    </div>
                    <div class="form-item" v-if="field.type === 'degree_slider'">
                      <label>最小值</label>
                      <el-input-number v-model="field.props.min" :min="0" :step="1" />
                    </div>
                    <div class="form-item" v-if="field.type === 'degree_slider'">
                      <label>最大值</label>
                      <el-input-number v-model="field.props.max" :min="0" :step="1" />
                    </div>
                    <div class="form-item full" v-if="field.type === 'degree_slider'">
                      <label>等级文案</label>
                      <div class="token-editor">
                        <span v-for="(item, index) in ensureArray(field.props, 'labels')" :key="item + index" class="token-chip">
                          {{ item }}
                          <button type="button" class="token-chip__remove" @click="field.props.labels.splice(index, 1)">×</button>
                        </span>
                        <input
                          v-model.trim="field.__newLabel"
                          type="text"
                          class="token-input"
                          placeholder="输入后回车添加…"
                          @keyup.enter.prevent="addSliderLabel(field)"
                        />
                      </div>
                    </div>
                    <div class="form-item full" v-if="field.type === 'preference_pair'">
                      <label>偏好配对</label>
                      <div class="pair-list">
                        <div v-for="(pair, pairIndex) in ensurePairs(field)" :key="pairIndex" class="pair-row">
                          <el-input v-model.trim="pair[0]" placeholder="左侧选项" />
                          <span class="pair-sep">vs</span>
                          <el-input v-model.trim="pair[1]" placeholder="右侧选项" />
                          <button type="button" class="icon-button danger" :aria-label="`删除第 ${pairIndex + 1} 组偏好配对`" @click="field.props.pairs.splice(pairIndex, 1)">×</button>
                        </div>
                        <el-button size="mini" @click="addPreferencePair(field)">新增配对</el-button>
                      </div>
                    </div>
                    <div class="form-item" v-if="field.type === 'input' || field.type === 'number' || field.type === 'input_radio'">
                      <label>占位提示</label>
                      <el-input v-model.trim="field.props.placeholder" maxlength="128" />
                    </div>
                    <div class="form-item" v-if="field.type === 'number'">
                      <label>单位</label>
                      <el-input v-model.trim="field.props.unit" maxlength="32" />
                    </div>
                    <div class="form-item full" v-if="field.type === 'checkbox'">
                      <label>互斥组</label>
                      <div class="mutual-group-list">
                        <div v-for="(group, groupIndex) in ensureMutualGroups(field)" :key="groupIndex" class="mutual-group">
                          <div class="mutual-group__items">
                            <span v-for="(item, index) in group" :key="item + index" class="token-chip">
                              {{ item }}
                              <button type="button" class="token-chip__remove" :aria-label="`移除${item}`" @click="group.splice(index, 1)">×</button>
                            </span>
                            <select class="mutual-select" @change="addToMutualGroup(field, groupIndex, $event.target.value); $event.target.value = ''">
                              <option value="">添加到组…</option>
                              <option v-for="item in getMutualAvailableOptions(field, group)" :key="item" :value="item">{{ item }}</option>
                            </select>
                          </div>
                          <button type="button" class="icon-button danger" :aria-label="`删除第 ${groupIndex + 1} 个互斥组`" @click="field.props.mutualExclusions.splice(groupIndex, 1)">删除组</button>
                        </div>
                        <el-button size="mini" @click="addMutualGroup(field)">新增互斥组</el-button>
                      </div>
                    </div>
                    <div class="form-item full">
                      <label>字段适用性别</label>
                      <div class="check-group">
                        <label class="check-label">
                          <input type="checkbox" :checked="fieldGenders(field).includes('1')" @change="toggleListValue(fieldGenders(field), '1', $event.target.checked)" />
                          男性
                        </label>
                        <label class="check-label">
                          <input type="checkbox" :checked="fieldGenders(field).includes('2')" @change="toggleListValue(fieldGenders(field), '2', $event.target.checked)" />
                          女性
                        </label>
                      </div>
                    </div>
                    <div class="form-item">
                      <label>年龄下限</label>
                      <el-input-number v-model="fieldAgeRange(field).min" :min="0" :step="1" />
                    </div>
                    <div class="form-item">
                      <label>年龄上限</label>
                      <el-input-number v-model="fieldAgeRange(field).max" :min="0" :step="1" />
                    </div>
                    <div class="form-item">
                      <label>年龄单位</label>
                      <el-select v-model="fieldAgeRange(field).unit">
                        <el-option label="岁" value="Y" />
                        <el-option label="月" value="M" />
                        <el-option label="天" value="D" />
                      </el-select>
                    </div>
                    <div class="form-item full">
                      <label>文本生成目标</label>
                      <div class="check-group">
                        <label class="check-label">
                          <input type="checkbox" :checked="fieldTargets(field).includes('chiefComplaint')" @change="toggleListValue(fieldTargets(field), 'chiefComplaint', $event.target.checked)" />
                          主诉
                        </label>
                        <label class="check-label">
                          <input type="checkbox" :checked="fieldTargets(field).includes('historyOfPresentIllness')" @change="toggleListValue(fieldTargets(field), 'historyOfPresentIllness', $event.target.checked)" />
                          现病史
                        </label>
                      </div>
                    </div>
                    <div class="form-item full">
                      <label>文本模板</label>
                      <el-input v-model="ensureTextGenConfig(field).template" maxlength="256" placeholder="例如：{label}：{value}" />
                    </div>
                    <div class="form-item">
                      <label>多选分隔符</label>
                      <el-input v-model.trim="ensureTextGenConfig(field).separator" maxlength="8" placeholder="默认 、…" />
                    </div>
                    <div class="form-item full">
                      <label>忽略值</label>
                      <div class="token-editor">
                        <span v-for="(item, index) in ignoreValues(field)" :key="item + index" class="token-chip">
                          {{ item }}
                          <button type="button" class="token-chip__remove" @click="ignoreValues(field).splice(index, 1)">×</button>
                        </span>
                        <select class="mutual-select" @change="addIgnoreValue(field, $event.target.value); $event.target.value = ''">
                          <option value="">选择要忽略的值…</option>
                          <option v-for="item in getOptionList(field)" :key="item" :value="item" :disabled="ignoreValues(field).includes(item)">{{ item }}</option>
                        </select>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <section class="editor-section" v-if="editingRecord.medicalMode === 'tcm'">
              <div class="section-title">中医扩展元数据</div>
              <div class="muted-text section-desc">保留 `tcmMetadata` 原始结构，便于维护辨证分类、脏腑经络与可能证型。</div>
              <el-input v-model="tcmMetadataText" type="textarea" :rows="12" placeholder="请输入合法配置，对应 tcmMetadata…" />
            </section>
          </div>
        </div>

        <div v-else class="empty-editor">
          <div class="section-title">请选择或新建一个症状模板</div>
          
        </div>
      </section>
    </div>

    <el-dialog
      v-if="changeLogDialogVisible"
      title="症状模板修改日志"
      :visible.sync="changeLogDialogVisible"
      width="1080px"
      @opened="loadChangeLogs"
    >
      <div class="change-log-toolbar">
        <el-input
          v-model.trim="changeLogFilters.keyword"
          clearable
          placeholder="搜索症状、操作者或摘要…"
          class="search-input"
          @keyup.enter.native="searchChangeLogs"
        />
        <el-select v-model="changeLogFilters.operationType" clearable placeholder="操作类型" class="filter-select">
          <el-option v-for="item in operationTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-input
          v-model.trim="changeLogFilters.operatorKeyword"
          clearable
          placeholder="操作者"
          class="filter-input"
          @keyup.enter.native="searchChangeLogs"
        />
        <el-date-picker
          v-model="changeLogFilters.dateRange"
          type="datetimerange"
          clearable
          unlink-panels
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="yyyy-MM-dd HH:mm:ss"
          class="filter-date"
        />
        <el-button type="primary" icon="el-icon-search" @click="searchChangeLogs">查询</el-button>
        <el-button @click="resetChangeLogFilters">重置</el-button>
      </div>

      <el-table :data="changeLogRecords" v-loading="changeLogLoading" class="change-log-table">
        <el-table-column label="操作" width="118">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="operationTypeMeta(row.operationType).type">
              {{ operationTypeMeta(row.operationType).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="症状" min-width="150" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <div class="log-main-text">{{ row.symptomName || '--' }}</div>
            <div class="log-sub-text">{{ row.symptomKey || '--' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作者" min-width="140" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <div class="log-main-text">{{ row.operatorName || row.operatorCode || '--' }}</div>
            <div class="log-sub-text">{{ row.operatorCode || row.operatorId || '--' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="变更摘要" min-width="260" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ row.changeSummary || '--' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="168">
          <template slot-scope="{ row }">{{ formatDateTime(row.operationTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template slot-scope="{ row }">
            <table-action @click="openChangeLogDetail(row)">详情</table-action>
          </template>
        </el-table-column>
      </el-table>

      <div class="page-footer">
        <AdminPagination
          :current.sync="changeLogPagination.current"
          :size.sync="changeLogPagination.size"
          :total="changeLogPagination.total"
          @change="loadChangeLogs"
        />
      </div>
      <span slot="footer">
        <el-button @click="changeLogDialogVisible = false">关闭</el-button>
      </span>
    </el-dialog>

    <el-dialog
      v-if="changeLogDetailVisible"
      title="修改日志详情"
      :visible.sync="changeLogDetailVisible"
      width="860px"
    >
      <div v-if="selectedChangeLog" class="detail-grid">
        <div class="detail-card">
          <div class="detail-card__label">操作类型</div>
          <div class="detail-card__value">{{ operationTypeMeta(selectedChangeLog.operationType).label }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">症状</div>
          <div class="detail-card__value">{{ selectedChangeLog.symptomName || selectedChangeLog.symptomKey || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">操作者</div>
          <div class="detail-card__value">{{ selectedChangeLog.operatorName || selectedChangeLog.operatorCode || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">操作时间</div>
          <div class="detail-card__value">{{ formatDateTime(selectedChangeLog.operationTime) }}</div>
        </div>
      </div>
      <el-tabs v-model="changeLogDetailTab" class="change-log-tabs">
        <el-tab-pane label="字段差异" name="diff">
          <pre class="code-block">{{ formatJson(selectedChangeLog && selectedChangeLog.diff) }}</pre>
        </el-tab-pane>
        <el-tab-pane label="修改前" name="before">
          <pre class="code-block">{{ formatJson(selectedChangeLog && selectedChangeLog.beforeSnapshot) }}</pre>
        </el-tab-pane>
        <el-tab-pane label="修改后" name="after">
          <pre class="code-block">{{ formatJson(selectedChangeLog && selectedChangeLog.afterSnapshot) }}</pre>
        </el-tab-pane>
      </el-tabs>
      <span slot="footer">
        <el-button @click="changeLogDetailVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { fetchOrgs, fetchRegions } from '../api/reference'
import { buildLabelMap, formatDateTime, resolveScopeLabel } from '../utils/admin'
import { AdvancedFilterPanel, SegmentedSwitch, TableAction } from '../components/ui'

const medicalModeOptions = [
  { value: 'western', label: '西医' },
  { value: 'tcm', label: '中医' }
]

const statusOptions = [
  { value: '1', label: '启用' },
  { value: '0', label: '停用' }
]

const operationTypeOptions = [
  { value: 'create', label: '新增', type: 'success' },
  { value: 'update', label: '修改', type: 'warning' },
  { value: 'delete', label: '删除', type: 'danger' },
  { value: 'import_builtin', label: '导入内置', type: 'info' },
  { value: 'import_json', label: '导入文件', type: 'info' }
]

const fieldTypeOptions = [
  { value: 'radio', label: '单选' },
  { value: 'checkbox', label: '多选' },
  { value: 'input', label: '文本输入' },
  { value: 'input_radio', label: '数值+单位' },
  { value: 'number', label: '数字输入' },
  { value: 'degree_slider', label: '程度滑条' },
  { value: 'preference_pair', label: '偏好配对' }
]

const systemCategoryOptions = [
  { value: 'respiratory', label: '呼吸系统' },
  { value: 'circulatory', label: '循环系统' },
  { value: 'endocrine', label: '内分泌系统' },
  { value: 'digestive', label: '消化系统' },
  { value: 'urinary', label: '泌尿系统' },
  { value: 'reproductive', label: '生殖系统' },
  { value: 'nervous', label: '神经系统' },
  { value: 'motor', label: '运动系统' },
  { value: 'other', label: '其他' }
]

function createFilters() {
  return {
    keyword: '',
    medicalMode: 'western',
    systemCategory: '',
    sdStatus: '1',
    idRegion: '',
    idOrg: ''
  }
}

function createChangeLogFilters() {
  return {
    keyword: '',
    operationType: '',
    operatorKeyword: '',
    dateRange: []
  }
}

function createDefaultSection(index) {
  const ts = Date.now() + index
  return {
    id: `section_${ts}`,
    title: '症状属性问诊',
    description: '',
    fields: []
  }
}

function createDefaultField() {
  const ts = Date.now()
  return {
    id: `field_${ts}`,
    key: `field_${ts}`,
    label: '新字段',
    type: 'radio',
    props: {
      options: ['选项1', '选项2']
    },
    storageKey: `field_${ts}`,
    required: false,
    applicablePopulation: {
      genders: [],
      ageRange: {
        unit: 'Y'
      }
    },
    textGenConfig: {
      targets: [],
      template: '{value}',
      separator: '、',
      optionConfig: {
        ignoreValues: []
      }
    }
  }
}

function createDefaultRecord(mode, scope) {
  return {
    id: '',
    medicalMode: mode || 'western',
    key: '',
    name: '',
    description: '',
    isCommonSymptom: false,
    systemCategory: [],
    bodyParts: [],
    customScript: '',
    config: {
      title: '智能问诊',
      sections: [createDefaultSection(0)]
    },
    applicablePopulation: {
      genders: [],
      ageGroups: []
    },
    tcmMetadata: null,
    sortOrder: 0,
    sdStatus: '1',
    idRegion: (scope && scope.idRegion) || '',
    idOrg: (scope && scope.idOrg) || '',
    createdAt: Date.now(),
    updatedAt: Date.now()
  }
}

function cloneRecord(value) {
  return JSON.parse(JSON.stringify(value))
}

export default {
  components: {
    AdvancedFilterPanel,
    SegmentedSwitch,
    TableAction
  },
  data() {
    return {
      medicalModeOptions,
      statusOptions,
      operationTypeOptions,
      fieldTypeOptions,
      systemCategoryOptions,
      loading: false,
      saving: false,
      importing: false,
      importingJson: false,
      filters: createFilters(),
      advancedFiltersOpen: false,
      records: [],
      pagination: {
        current: 1,
        size: 10,
        total: 0
      },
      deletedTemplateIds: [],
      selectedId: '',
      editingRecord: null,
      originalRecord: null,
      regionOptions: [],
      orgOptions: [],
      regionMap: {},
      orgMap: {},
      tcmMetadataText: '',
      newBodyPart: '',
      changeLogDialogVisible: false,
      changeLogDetailVisible: false,
      changeLogLoading: false,
      changeLogRecords: [],
      changeLogFilters: createChangeLogFilters(),
      changeLogPagination: {
        current: 1,
        size: 10,
        total: 0
      },
      selectedChangeLog: null,
      changeLogDetailTab: 'diff'
    }
  },
  computed: {
    filteredOrgOptions() {
      if (!this.filters.idRegion) {
        return this.orgOptions
      }
      return this.orgOptions.filter(item => item.idRegion === this.filters.idRegion)
    },
    draftOrgOptions() {
      if (!this.editingRecord || !this.editingRecord.idRegion) {
        return this.orgOptions
      }
      return this.orgOptions.filter(item => item.idRegion === this.editingRecord.idRegion)
    }
  },
  async mounted() {
    await this.loadReferences()
    this.loadData()
  },
  methods: {
    medicalModeLabel(value) {
      const item = medicalModeOptions.find(option => option.value === value)
      return item ? item.label : value || '--'
    },
    statusLabel(value) {
      const item = statusOptions.find(option => option.value === value)
      return item ? item.label : value || '--'
    },
    operationTypeMeta(value) {
      return operationTypeOptions.find(option => option.value === value) || { label: value || '--', type: 'info' }
    },
    formatDateTime(value) {
      return formatDateTime(value)
    },
    resolveScope(row) {
      return resolveScopeLabel(row, this.orgMap, this.regionMap)
    },
    async loadReferences() {
      const [regions, orgs] = await Promise.all([fetchRegions(), fetchOrgs()])
      this.regionOptions = regions
      this.orgOptions = orgs
      this.regionMap = buildLabelMap(regions, 'idRegion', 'naRegion')
      this.orgMap = buildLabelMap(orgs, 'idOrg', 'naOrg')
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/symptom-templates', {
          params: this.templateListParams(this.pagination.current, this.pagination.size)
        })
        const deletedIds = this.deletedTemplateIds
        this.records = (data.records || []).filter(item => item && !deletedIds.includes(item.id))
        this.pagination.current = Number(data.current || this.pagination.current)
        this.pagination.size = Number(data.size || this.pagination.size)
        this.pagination.total = Number(data.total || 0)
        if (this.selectedId) {
          const selected = this.records.find(item => item.id === this.selectedId)
          if (selected) {
            this.selectRecord(selected)
            return
          }
        }
        if (this.records.length) {
          this.selectRecord(this.records[0])
        } else {
          this.selectedId = ''
          this.editingRecord = null
          this.originalRecord = null
          this.tcmMetadataText = ''
        }
      } catch (error) {
        this.$message.error((error && error.message) || '加载症状模板失败')
      } finally {
        this.loading = false
      }
    },
    templateListParams(current, size) {
      return {
        current,
        size,
        keyword: this.filters.keyword || undefined,
        medicalMode: this.filters.medicalMode || undefined,
        systemCategory: this.filters.systemCategory || undefined,
        sdStatus: this.filters.sdStatus || undefined,
        idRegion: this.filters.idRegion || undefined,
        idOrg: this.filters.idOrg || undefined
      }
    },
    searchTemplates() {
      this.pagination.current = 1
      this.loadData()
    },
    handleMedicalModeChange() {
      this.selectedId = ''
      this.editingRecord = null
      this.originalRecord = null
      this.pagination.current = 1
      this.loadData()
    },
    handleFilterRegionChange(idRegion) {
      if (!idRegion) {
        return
      }
      const org = this.orgOptions.find(item => item.idOrg === this.filters.idOrg)
      if (org && org.idRegion !== idRegion) {
        this.filters.idOrg = ''
      }
    },
    handleFilterOrgChange(idOrg) {
      const org = this.orgOptions.find(item => item.idOrg === idOrg)
      if (org) {
        this.filters.idRegion = org.idRegion || ''
      }
    },
    handleDraftRegionChange(idRegion) {
      if (!this.editingRecord || !idRegion) {
        return
      }
      const org = this.orgOptions.find(item => item.idOrg === this.editingRecord.idOrg)
      if (org && org.idRegion !== idRegion) {
        this.editingRecord.idOrg = ''
      }
    },
    handleDraftOrgChange(idOrg) {
      if (!this.editingRecord) {
        return
      }
      const org = this.orgOptions.find(item => item.idOrg === idOrg)
      if (org) {
        this.editingRecord.idRegion = org.idRegion || ''
      }
    },
    handleDraftMedicalModeChange() {
      if (this.editingRecord && this.editingRecord.medicalMode !== 'tcm') {
        this.editingRecord.tcmMetadata = null
        this.tcmMetadataText = ''
      }
    },
    resetFilters() {
      this.filters = createFilters()
      this.pagination.current = 1
      this.selectedId = ''
      this.editingRecord = null
      this.originalRecord = null
      this.tcmMetadataText = ''
      this.loadData()
    },
    selectRecord(record) {
      this.selectedId = record.id
      this.originalRecord = cloneRecord(record)
      this.editingRecord = cloneRecord(record)
      this.normalizeRecord(this.editingRecord)
      this.tcmMetadataText = this.editingRecord.tcmMetadata ? JSON.stringify(this.editingRecord.tcmMetadata, null, 2) : ''
      this.newBodyPart = ''
    },
    rememberDeletedTemplate(id) {
      if (id && !this.deletedTemplateIds.includes(id)) {
        this.deletedTemplateIds.push(id)
      }
    },
    removeDeletedRecord(id) {
      const deletedIndex = this.records.findIndex(item => item.id === id)
      if (deletedIndex !== -1) {
        this.records.splice(deletedIndex, 1)
      }
      if (!this.records.length) {
        this.selectedId = ''
        this.editingRecord = null
        this.originalRecord = null
        this.tcmMetadataText = ''
        return
      }
      const nextIndex = deletedIndex === -1 ? 0 : Math.min(deletedIndex, this.records.length - 1)
      this.selectRecord(this.records[nextIndex])
    },
    openCreate() {
      const draft = createDefaultRecord(this.filters.medicalMode, this.filters)
      this.normalizeRecord(draft)
      this.selectedId = ''
      this.originalRecord = null
      this.editingRecord = draft
      this.tcmMetadataText = ''
      this.newBodyPart = ''
    },
    normalizeRecord(record) {
      if (!record.systemCategory) record.systemCategory = []
      if (!record.bodyParts) record.bodyParts = []
      if (!record.applicablePopulation) record.applicablePopulation = { genders: [], ageGroups: [] }
      if (!Array.isArray(record.applicablePopulation.genders)) record.applicablePopulation.genders = []
      if (!record.config) record.config = { title: '智能问诊', sections: [] }
      if (!Array.isArray(record.config.sections)) record.config.sections = []
      record.config.sections.forEach((section, index) => {
        if (!section.id) section.id = `section_${Date.now()}_${index}`
        if (!Array.isArray(section.fields)) section.fields = []
        section.fields.forEach(field => this.normalizeField(field))
      })
      if (!record.config.sections.length) {
        record.config.sections.push(createDefaultSection(0))
      }
    },
    normalizeField(field) {
      if (!field.id) field.id = `field_${Date.now()}`
      if (!field.key) field.key = field.id
      if (!field.storageKey) field.storageKey = field.key || field.id
      if (!field.label) field.label = '新字段'
      if (!field.type) field.type = 'radio'
      if (!field.props || typeof field.props !== 'object') field.props = {}
      if (!field.applicablePopulation || typeof field.applicablePopulation !== 'object') {
        field.applicablePopulation = { genders: [], ageRange: { unit: 'Y' } }
      }
      if (!Array.isArray(field.applicablePopulation.genders)) {
        field.applicablePopulation.genders = []
      }
      if (!field.applicablePopulation.ageRange || typeof field.applicablePopulation.ageRange !== 'object') {
        field.applicablePopulation.ageRange = { unit: 'Y' }
      }
      if (!field.textGenConfig || typeof field.textGenConfig !== 'object') {
        field.textGenConfig = { targets: [], template: '{value}', separator: '、', optionConfig: { ignoreValues: [] } }
      }
      if (!Array.isArray(field.textGenConfig.targets)) {
        field.textGenConfig.targets = []
      }
      if (!field.textGenConfig.optionConfig || typeof field.textGenConfig.optionConfig !== 'object') {
        field.textGenConfig.optionConfig = { ignoreValues: [] }
      }
      if (!Array.isArray(field.textGenConfig.optionConfig.ignoreValues)) {
        field.textGenConfig.optionConfig.ignoreValues = []
      }
      this.handleFieldTypeChange(field)
    },
    addSection() {
      this.editingRecord.config.sections.push(createDefaultSection(this.editingRecord.config.sections.length))
    },
    removeSection(index) {
      if (this.editingRecord.config.sections.length === 1) {
        this.$message.warning('至少保留一个分组')
        return
      }
      this.editingRecord.config.sections.splice(index, 1)
    },
    addField(section) {
      section.fields.push(createDefaultField())
    },
    removeField(section, index) {
      section.fields.splice(index, 1)
    },
    handleFieldTypeChange(field) {
      if (!field.props || typeof field.props !== 'object') {
        field.props = {}
      }
      if (field.type === 'radio' || field.type === 'checkbox') {
        if (!Array.isArray(field.props.options)) {
          this.$set(field.props, 'options', ['选项1', '选项2'])
        }
      } else if (field.type === 'input_radio') {
        if (!Array.isArray(field.props.radioOptions)) {
          this.$set(field.props, 'radioOptions', ['小时', '天'])
        }
      } else if (field.type === 'degree_slider') {
        if (typeof field.props.min !== 'number') {
          this.$set(field.props, 'min', 1)
        }
        if (typeof field.props.max !== 'number') {
          this.$set(field.props, 'max', 10)
        }
        if (!Array.isArray(field.props.labels)) {
          this.$set(field.props, 'labels', ['轻微', '中等', '剧烈'])
        }
      } else if (field.type === 'preference_pair') {
        if (!Array.isArray(field.props.pairs)) {
          this.$set(field.props, 'pairs', [['选项A', '选项B']])
        }
      }
      if (field.type === 'checkbox' && !Array.isArray(field.props.mutualExclusions)) {
        this.$set(field.props, 'mutualExclusions', [])
      }
    },
    usesOptionList(field) {
      return field.type === 'radio' || field.type === 'checkbox' || field.type === 'input_radio'
    },
    getOptionList(field) {
      this.handleFieldTypeChange(field)
      if (field.type === 'input_radio') {
        return field.props.radioOptions
      }
      return field.props.options
    },
    addOption(field) {
      const value = (field.__newOption || '').trim()
      if (!value) {
        return
      }
      const list = this.getOptionList(field)
      if (!list.includes(value)) {
        list.push(value)
      }
      field.__newOption = ''
    },
    removeOption(field, index) {
      this.getOptionList(field).splice(index, 1)
    },
    ensureArray(target, key) {
      if (!Array.isArray(target[key])) {
        this.$set(target, key, [])
      }
      return target[key]
    },
    addSliderLabel(field) {
      const value = (field.__newLabel || '').trim()
      if (!value) {
        return
      }
      this.ensureArray(field.props, 'labels').push(value)
      field.__newLabel = ''
    },
    ensurePairs(field) {
      if (!Array.isArray(field.props.pairs)) {
        this.$set(field.props, 'pairs', [])
      }
      return field.props.pairs
    },
    addPreferencePair(field) {
      this.ensurePairs(field).push(['', ''])
    },
    ensureMutualGroups(field) {
      if (!Array.isArray(field.props.mutualExclusions)) {
        this.$set(field.props, 'mutualExclusions', [])
      }
      return field.props.mutualExclusions
    },
    addMutualGroup(field) {
      this.ensureMutualGroups(field).push([])
    },
    addToMutualGroup(field, groupIndex, value) {
      if (!value) {
        return
      }
      const groups = this.ensureMutualGroups(field)
      const group = groups[groupIndex]
      if (group && !group.includes(value)) {
        group.push(value)
      }
    },
    getMutualAvailableOptions(field, group) {
      return this.getOptionList(field).filter(item => !group.includes(item))
    },
    fieldGenders(field) {
      if (!field.applicablePopulation) {
        this.$set(field, 'applicablePopulation', { genders: [], ageRange: { unit: 'Y' } })
      }
      if (!Array.isArray(field.applicablePopulation.genders)) {
        this.$set(field.applicablePopulation, 'genders', [])
      }
      return field.applicablePopulation.genders
    },
    fieldAgeRange(field) {
      if (!field.applicablePopulation) {
        this.$set(field, 'applicablePopulation', { genders: [], ageRange: { unit: 'Y' } })
      }
      if (!field.applicablePopulation.ageRange || typeof field.applicablePopulation.ageRange !== 'object') {
        this.$set(field.applicablePopulation, 'ageRange', { unit: 'Y' })
      }
      if (!field.applicablePopulation.ageRange.unit) {
        this.$set(field.applicablePopulation.ageRange, 'unit', 'Y')
      }
      return field.applicablePopulation.ageRange
    },
    ensureTextGenConfig(field) {
      if (!field.textGenConfig || typeof field.textGenConfig !== 'object') {
        this.$set(field, 'textGenConfig', { targets: [], template: '{value}', separator: '、', optionConfig: { ignoreValues: [] } })
      }
      if (!field.textGenConfig.optionConfig || typeof field.textGenConfig.optionConfig !== 'object') {
        this.$set(field.textGenConfig, 'optionConfig', { ignoreValues: [] })
      }
      if (!Array.isArray(field.textGenConfig.optionConfig.ignoreValues)) {
        this.$set(field.textGenConfig.optionConfig, 'ignoreValues', [])
      }
      if (!Array.isArray(field.textGenConfig.targets)) {
        this.$set(field.textGenConfig, 'targets', [])
      }
      return field.textGenConfig
    },
    fieldTargets(field) {
      return this.ensureTextGenConfig(field).targets
    },
    ignoreValues(field) {
      return this.ensureTextGenConfig(field).optionConfig.ignoreValues
    },
    addIgnoreValue(field, value) {
      if (!value) {
        return
      }
      const list = this.ignoreValues(field)
      if (!list.includes(value)) {
        list.push(value)
      }
    },
    toggleListValue(list, value, checked) {
      const index = list.indexOf(value)
      if (checked && index === -1) {
        list.push(value)
      }
      if (!checked && index !== -1) {
        list.splice(index, 1)
      }
    },
    addBodyPart() {
      const value = this.newBodyPart.trim()
      if (!value || this.editingRecord.bodyParts.includes(value)) {
        this.newBodyPart = ''
        return
      }
      this.editingRecord.bodyParts.push(value)
      this.newBodyPart = ''
    },
    resetCurrent() {
      if (this.originalRecord) {
        this.selectRecord(this.originalRecord)
        return
      }
      this.openCreate()
    },
    validateCurrent() {
      if (!this.editingRecord) {
        return false
      }
      if (!this.editingRecord.name) {
        this.$message.error('症状名称不能为空')
        return false
      }
      if (!this.editingRecord.key) {
        this.$message.error('症状标识不能为空')
        return false
      }
      if (!this.editingRecord.config || !Array.isArray(this.editingRecord.config.sections) || !this.editingRecord.config.sections.length) {
        this.$message.error('至少需要一个问诊分组')
        return false
      }
      for (const section of this.editingRecord.config.sections) {
        if (!section.title) {
          this.$message.error('分组标题不能为空')
          return false
        }
        if (!Array.isArray(section.fields)) {
          this.$message.error('分组字段结构不合法')
          return false
        }
        for (const field of section.fields) {
          if (!field.label || !field.storageKey || !field.type) {
            this.$message.error('字段标签、存储标识、类型不能为空')
            return false
          }
        }
      }
      if (this.editingRecord.medicalMode === 'tcm' && this.tcmMetadataText) {
        try {
          JSON.parse(this.tcmMetadataText)
        } catch (error) {
          this.$message.error('中医扩展元数据格式不正确')
          return false
        }
      }
      return true
    },
    buildPayload() {
      const payload = cloneRecord(this.editingRecord)
      if (payload.medicalMode !== 'tcm') {
        payload.tcmMetadata = null
      } else if (this.tcmMetadataText) {
        payload.tcmMetadata = JSON.parse(this.tcmMetadataText)
      } else {
        payload.tcmMetadata = null
      }
      payload.idOrg = payload.idOrg || null
      payload.idRegion = payload.idRegion || null
      return payload
    },
    async saveCurrent() {
      if (!this.validateCurrent()) {
        return
      }
      this.saving = true
      try {
        const payload = this.buildPayload()
        const data = payload.id
          ? await http.put(`/admin/api/symptom-templates/${payload.id}`, payload)
          : await http.post('/admin/api/symptom-templates', payload)
        this.$message.success('保存成功')
        this.selectedId = data.id
        await this.loadData()
      } catch (error) {
        this.$message.error((error && error.message) || '保存失败')
      } finally {
        this.saving = false
      }
    },
    async removeCurrent() {
      if (!this.editingRecord) {
        return
      }
      if (!this.editingRecord.id) {
        this.editingRecord = null
        this.originalRecord = null
        this.selectedId = ''
        return
      }
      const deletedId = this.editingRecord.id
      const deletedName = this.editingRecord.name || this.editingRecord.key
      this.$confirm(`确认删除症状模板「${deletedName}」吗？`, '提示', {
        type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/admin/api/symptom-templates/${deletedId}`)
          this.$message.success('删除成功')
          this.rememberDeletedTemplate(deletedId)
          this.removeDeletedRecord(deletedId)
          await this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || '删除失败')
        }
      }).catch(() => {})
    },
    async importBuiltin() {
      this.$confirm(`确认将${this.medicalModeLabel(this.filters.medicalMode)}内置模板导入当前作用域吗？同标识的现有记录会被覆盖更新。`, '提示', {
        type: 'warning'
      }).then(async () => {
        this.importing = true
        try {
          const data = await http.post('/admin/api/symptom-templates/import-builtin', {
            medicalMode: this.filters.medicalMode,
            idRegion: this.filters.idRegion || null,
            idOrg: this.filters.idOrg || null,
            overwriteExisting: true
          })
          this.$message.success(`导入完成：新增 ${data.createdCount || 0} 条，更新 ${data.updatedCount || 0} 条`)
          this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || '导入内置模板失败')
        } finally {
          this.importing = false
        }
      }).catch(() => {})
    },
    triggerJsonImport() {
      if (!this.$refs.importJsonInput) {
        return
      }
      this.$refs.importJsonInput.value = ''
      this.$refs.importJsonInput.click()
    },
    handleJsonFileChange(event) {
      const input = event && event.target
      const file = input && input.files && input.files[0]
      if (!file) {
        return
      }
      const reader = new FileReader()
      reader.onerror = () => {
        this.$message.error('读取模板文件失败')
      }
      reader.onload = () => {
        const contentJson = typeof reader.result === 'string' ? reader.result : ''
        if (!contentJson.trim()) {
          this.$message.error('模板文件内容不能为空')
          return
        }
        try {
          JSON.parse(contentJson)
        } catch (error) {
          this.$message.error('模板文件格式不正确')
          return
        }
        this.$confirm(`确认将文件「${file.name}」按${this.medicalModeLabel(this.filters.medicalMode)}导入当前作用域吗？同标识的现有记录会被覆盖更新。`, '提示', {
          type: 'warning'
        }).then(async () => {
          this.importingJson = true
          try {
            const data = await http.post('/admin/api/symptom-templates/import-json', {
              medicalMode: this.filters.medicalMode,
              idRegion: this.filters.idRegion || null,
              idOrg: this.filters.idOrg || null,
              overwriteExisting: true,
              contentJson
            })
            this.$message.success(`导入完成：新增 ${data.createdCount || 0} 条，更新 ${data.updatedCount || 0} 条`)
            this.loadData()
          } catch (error) {
            this.$message.error((error && error.message) || '导入模板文件失败')
          } finally {
            this.importingJson = false
          }
        }).catch(() => {})
      }
      reader.readAsText(file, 'utf-8')
    },
    async exportCurrent() {
      try {
        const records = []
        let current = 1
        let total = 0
        do {
          const data = await http.get('/admin/api/symptom-templates', {
            params: this.templateListParams(current, 100)
          })
          const pageRecords = data.records || []
          records.push(...pageRecords)
          total = Number(data.total || 0)
          current += 1
          if (!pageRecords.length) break
        } while (records.length < total)

        const content = JSON.stringify(records, null, 2)
        const blob = new Blob([content], { type: 'application/json;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = `symptom-templates-${this.filters.medicalMode}.json`
        link.click()
        URL.revokeObjectURL(url)
      } catch (error) {
        this.$message.error((error && error.message) || '导出症状模板失败')
      }
    },
    openChangeLogDialog() {
      this.changeLogDialogVisible = true
      this.changeLogPagination.current = 1
      this.changeLogFilters = createChangeLogFilters()
    },
    async loadChangeLogs() {
      if (!this.changeLogDialogVisible) {
        return
      }
      this.changeLogLoading = true
      try {
        const range = this.changeLogFilters.dateRange || []
        const data = await http.get('/admin/api/symptom-templates/change-logs', {
          params: {
            current: this.changeLogPagination.current,
            size: this.changeLogPagination.size,
            idTemplate: this.selectedId || undefined,
            keyword: this.changeLogFilters.keyword || undefined,
            medicalMode: this.filters.medicalMode || undefined,
            operationType: this.changeLogFilters.operationType || undefined,
            operatorKeyword: this.changeLogFilters.operatorKeyword || undefined,
            dateFrom: range[0] || undefined,
            dateTo: range[1] || undefined
          }
        })
        this.changeLogRecords = data.records || []
        this.changeLogPagination.total = data.total || 0
      } catch (error) {
        this.$message.error((error && error.message) || '加载修改日志失败')
      } finally {
        this.changeLogLoading = false
      }
    },
    searchChangeLogs() {
      this.changeLogPagination.current = 1
      this.loadChangeLogs()
    },
    resetChangeLogFilters() {
      this.changeLogFilters = createChangeLogFilters()
      this.searchChangeLogs()
    },
    openChangeLogDetail(row) {
      this.selectedChangeLog = row
      this.changeLogDetailTab = 'diff'
      this.changeLogDetailVisible = true
    },
    formatJson(value) {
      if (!value || (typeof value === 'object' && !Object.keys(value).length)) {
        return '{}'
      }
      return JSON.stringify(value, null, 2)
    },
    getSymptomGenders() {
      if (!this.editingRecord) {
        return []
      }
      if (!this.editingRecord.applicablePopulation) {
        this.$set(this.editingRecord, 'applicablePopulation', { genders: [], ageGroups: [] })
      }
      if (!Array.isArray(this.editingRecord.applicablePopulation.genders)) {
        this.$set(this.editingRecord.applicablePopulation, 'genders', [])
      }
      return this.editingRecord.applicablePopulation.genders
    }
  }
}
</script>

<style scoped>
.symptom-template-page {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 16px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.symptom-toolbar {
  align-items: flex-start;
  gap: 16px;
}

.hidden-file-input {
  display: none;
}

.symptom-filters {
  flex-wrap: wrap;
}

.symptom-toolbar__actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.symptom-layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  min-height: 0;
}

.symptom-sidebar,
.symptom-editor {
  overflow: hidden;
  min-height: 0;
}

.symptom-sidebar {
  display: flex;
  flex-direction: column;
}

.symptom-sidebar__header {
  padding-bottom: 12px;
  border-bottom: 1px solid #eef2f7;
}

.symptom-list {
  flex: 1;
  min-height: 0;
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.symptom-sidebar__pagination {
  flex: 0 0 auto;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #eef2f7;
}

.symptom-item {
  width: 100%;
  border: 1px solid #e4ebf3;
  background: #fff;
  border-radius: 12px;
  padding: 12px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.18s ease, transform 0.18s ease;
}

.symptom-item:hover,
.symptom-item.is-active {
  border-color: #3770ab;
  transform: translateY(-1px);
}

.symptom-item__title {
  font-size: 14px;
  font-weight: 500;
  color: #16324f;
}

.symptom-item__meta {
  margin-top: 4px;
  font-size: 12px;
  color: #7d8ca0;
}

.symptom-item__tags {
  margin-top: 10px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.inline-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: #eef4fb;
  color: #3770ab;
  font-size: 12px;
}

.empty-state,
.empty-editor {
  min-height: 240px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: #7d8ca0;
}

.editor-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eef2f7;
}

.editor-actions {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex-shrink: 0;
}

.editor-scroll {
  flex: 1;
  min-height: 0;
  margin-top: 16px;
  display: grid;
  gap: 16px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-gutter: stable;
}

.editor-section {
  border: 1px solid #e9eef5;
  border-radius: 16px;
  padding: 18px;
  background:
    linear-gradient(180deg, rgba(245, 248, 251, 0.7), rgba(255, 255, 255, 0)),
    #fff;
}

.section-title {
  font-size: 18px;
  font-weight: 500;
  color: #16324f;
}

.section-desc,
.muted-text {
  margin-top: 6px;
  color: #7d8ca0;
  line-height: 1.7;
}

.section-header,
.section-card__header,
.field-card__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.section-header__actions,
.section-card__actions,
.field-card__actions {
  display: flex;
  gap: 8px;
}

.form-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-item.full {
  grid-column: 1 / -1;
}

.form-item label {
  font-size: 13px;
  font-weight: 500;
  color: #4f6278;
}

.row-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.check-group {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 12px;
  border-radius: 12px;
  background: #f7f9fc;
  border: 1px solid #edf2f8;
}

.check-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #41556c;
  font-size: 13px;
}

.token-editor {
  min-height: 42px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid #dbe5ef;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  background: #fff;
}

.token-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 999px;
  background: #eef4fb;
  color: #2f5e90;
  font-size: 12px;
}

.token-chip__remove,
.icon-button {
  border: none;
  background: transparent;
  cursor: pointer;
}

.icon-button.danger,
.token-chip__remove {
  color: #d14343;
}

.token-input,
.mutual-select {
  border: none;
  font-size: 13px;
  color: #33475c;
  min-width: 180px;
  background: transparent;
}

.token-input:focus-visible,
.mutual-select:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  box-shadow: var(--focus-ring);
}

.section-card,
.field-card {
  margin-top: 14px;
  border: 1px solid #e8eef5;
  border-radius: 14px;
  padding: 14px;
  background: #fff;
}

.section-card__title,
.field-card__title {
  font-size: 15px;
  font-weight: 500;
  color: #20466d;
}

.pair-list,
.mutual-group-list {
  display: grid;
  gap: 10px;
}

.pair-row,
.mutual-group {
  display: flex;
  gap: 10px;
  align-items: center;
}

.pair-row .el-input {
  flex: 1;
}

.pair-sep {
  color: #7d8ca0;
  font-size: 12px;
}

.mutual-group__items {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid #dbe5ef;
}

.change-log-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.change-log-table {
  width: 100%;
}

.filter-input {
  width: 180px;
}

.filter-date {
  width: 360px;
}

.log-main-text {
  color: #16324f;
  font-weight: 500;
}

.log-sub-text {
  margin-top: 3px;
  color: #7d8ca0;
  font-size: 12px;
}

.page-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.detail-card {
  border: 1px solid #e8eef5;
  border-radius: 8px;
  padding: 10px 12px;
  background: #f8fafc;
}

.detail-card__label {
  color: #7d8ca0;
  font-size: 12px;
}

.detail-card__value {
  margin-top: 4px;
  color: #16324f;
  font-size: 14px;
  word-break: break-all;
}

.change-log-tabs {
  margin-top: 6px;
}

.code-block {
  max-height: 460px;
  overflow: auto;
  padding: 14px;
  border-radius: 8px;
  border: 1px solid #e8eef5;
  background: #0f172a;
  color: #dbeafe;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

@media (max-width: 1280px) {
  .symptom-layout {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(180px, 32%) minmax(0, 1fr);
  }
}

@media (max-width: 960px) {
  .symptom-template-page {
    height: auto;
    overflow: visible;
  }

  .symptom-layout {
    grid-template-rows: auto;
  }

  .symptom-sidebar,
  .symptom-editor {
    overflow: visible;
  }

  .symptom-list,
  .editor-scroll {
    max-height: none;
    overflow: visible;
    padding-right: 0;
  }

  .editor-header,
  .symptom-toolbar {
    flex-direction: column;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
