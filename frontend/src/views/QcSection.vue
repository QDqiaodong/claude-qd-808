<template>
  <div>
    <div class="section-head">
      <h2>05 质检</h2>
      <span>抽样批次卡片 · 实测值 vs 标准区间（超标标红）· CSS 圆形印章判定（霉菌毒素超标只能盖退货章）· 退货批次复检闭环（原始记录不改写）</span>
    </div>

    <div class="qc-grid">
      <div v-for="s in samples" :key="s.id" class="qc-card">
        <div style="display:flex;justify-content:space-between;align-items:flex-start">
          <div>
            <b style="font-size:15px">{{ s.sampleNo }}</b>
            <div class="mini muted">
              批次 {{ s.batch.batchNo }} · {{ s.batch.recipe.name }} · {{ s.sampler }} ·
              {{ String(s.sampleTime || '').replace('T', ' ').slice(0, 16) }}
            </div>
          </div>
          <span class="tag-chip" :class="conclusionChip(s.conclusion)">{{ conclusionName(s.conclusion) }}</span>
        </div>

        <el-table :data="s.items" size="small" :cell-class-name="cellClass" style="margin-top:10px">
          <el-table-column prop="itemName" label="检验项" width="90" />
          <el-table-column prop="measuredValue" label="实测值" width="110">
            <template #default="{ row }">
              {{ Number(row.measuredValue).toFixed(3) }} {{ row.unit }}
            </template>
          </el-table-column>
          <el-table-column label="标准区间" width="140">
            <template #default="{ row }">
              {{ Number(row.stdLower).toFixed(3) }} ~ {{ Number(row.stdUpper).toFixed(3) }} {{ row.unit }}
            </template>
          </el-table-column>
          <el-table-column label="判定" width="70">
            <template #default="{ row }">
              <span :class="row.qualified ? 'chip-pass' : 'chip-reject'" class="tag-chip">
                {{ row.qualified ? '合格' : '超标' }}
              </span>
            </template>
          </el-table-column>
        </el-table>

        <div class="conclusion-area">
          <div style="flex:1">
            <div style="display:flex;gap:8px;flex-wrap:wrap">
              <el-button size="small" type="success" :disabled="!canPass(s)" @click="judge(s, 'PASS')">盖合格章</el-button>
              <el-button size="small" type="warning" :disabled="!canDowngrade(s)" @click="judge(s, 'DOWNGRADE')">盖降级章</el-button>
              <el-button size="small" type="danger" :disabled="!canReject(s)" @click="judge(s, 'REJECT')">盖退货章</el-button>
            </div>
            <div class="stamp-hint">
              处理去向：{{ s.disposition || '待判定' }}
              <span v-if="mycoFailed(s)" style="color:#c62828"> · 霉菌毒素超标，按规程只能判退货，不允许人工改判合格</span>
              <span v-else-if="failedCount(s)" style="color:#ef6c00">
                · 存在 {{ failedCount(s) }} 项超标，不能判合格
              </span>
            </div>
            <div class="mini muted" v-if="s.remark">备注：{{ s.remark }}</div>
          </div>

          <!-- CSS 绘制的圆形印章 -->
          <div class="stamp-wrap">
            <div class="stamp" :class="stampClass(s)">
              <span class="stamp-title">{{ stampTitle(s) }}</span>
              <span class="stamp-sub">饲料质检专用</span>
              <span class="stamp-date">{{ stampDate(s) }}</span>
            </div>
          </div>
        </div>

        <!-- 退货批次复检闭环：仅初检结论为退货的批次可进入，其他批次不显示入口 -->
        <div v-if="s.conclusion === 'REJECT'" class="recheck-area">
          <div class="recheck-head">
            <b>退货复检</b>
            <span class="tag-chip" :class="recheckChip(s)">{{ recheckStatusText(s) }}</span>
          </div>

          <!-- 历史退货批次：无复检资料，兼容显示为未发起 -->
          <div v-if="!recheckOf(s)" class="recheck-body">
            <div class="mini muted">该批次初检判退货，未发起复检；原始检验结果与初检处置保持原口径归档。</div>
            <el-button size="small" type="primary" plain @click="openCreate(s)">发起复检</el-button>
          </div>

          <!-- 待复核：保留发起人、原因、待复核状态 -->
          <div v-else-if="recheckOf(s).status === 'PENDING'" class="recheck-body">
            <div class="mini">
              单号 {{ recheckOf(s).recheckNo }} · 发起人 {{ recheckOf(s).initiator }} ·
              {{ fmtTime(recheckOf(s).createdAt) }}
            </div>
            <div class="mini muted">发起原因：{{ recheckOf(s).reason }}</div>
            <div class="mini" style="color:#1565c0">
              待复核：须由初检判定人（{{ s.sampler }}）以外的复核员录入新检验值并给出最终结论
            </div>
            <el-button size="small" type="primary" @click="openReview(s)">复核放行</el-button>
          </div>

          <!-- 已收口：最终结论 + 初检/复检对照，原始记录完整保留 -->
          <div v-else class="recheck-body">
            <div class="mini">
              单号 {{ recheckOf(s).recheckNo }} · 复核员 {{ recheckOf(s).reviewer }} ·
              {{ fmtTime(recheckOf(s).reviewedAt) }}
            </div>
            <div class="mini muted">发起原因：{{ recheckOf(s).reason }}</div>
            <el-table :data="recheckRows(s)" size="small" style="margin-top:4px">
              <el-table-column prop="itemName" label="检验项" width="80" />
              <el-table-column label="初检值" width="100">
                <template #default="{ row }">{{ num(row.originValue) }} {{ row.unit }}</template>
              </el-table-column>
              <el-table-column label="复检值" width="100">
                <template #default="{ row }">
                  <span :class="{ 'bad-text': !row.qualified }">{{ num(row.recheckValue) }} {{ row.unit }}</span>
                </template>
              </el-table-column>
              <el-table-column label="标准区间" width="130">
                <template #default="{ row }">{{ num(row.stdLower) }} ~ {{ num(row.stdUpper) }} {{ row.unit }}</template>
              </el-table-column>
              <el-table-column label="复检判定" width="80">
                <template #default="{ row }">
                  <span class="tag-chip" :class="row.qualified ? 'chip-pass' : 'chip-reject'">
                    {{ row.qualified ? '合格' : '超标' }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
            <div class="stamp-hint">
              最终结论：{{ recheckConclusionName(recheckOf(s).conclusion) }} · 去向：{{ recheckOf(s).disposition }}
              <span v-if="recheckOf(s).remark"> · {{ recheckOf(s).remark }}</span>
            </div>
            <div class="mini muted">初检结果与初检处置（{{ s.disposition || '—' }}）已完整保留，未被复检改写。</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 发起复检：只有初检退货批次能打开 -->
    <el-dialog v-model="createVisible" title="发起退货批次复检" width="440px">
      <el-form label-width="80px">
        <el-form-item label="批次">
          <span>{{ current ? current.batch.batchNo : '' }}（初检结论：退货）</span>
        </el-form-item>
        <el-form-item label="发起人" required>
          <el-input v-model="createForm.initiator" placeholder="发起人姓名" maxlength="32" />
        </el-form-item>
        <el-form-item label="发起原因" required>
          <el-input v-model="createForm.reason" type="textarea" :rows="3" maxlength="255"
                    placeholder="例如：供应商对霉菌毒素检测结果有异议，申请复验" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">提交发起</el-button>
      </template>
    </el-dialog>

    <!-- 复核收口：录入新检验值，原始检验结果与初检处置不改写 -->
    <el-dialog v-model="reviewVisible" title="复检复核 · 录入新检验值" width="580px">
      <div class="mini muted" style="margin-bottom:10px">
        初检判定人：{{ current ? current.sampler : '' }}（复核员须与其不同） · 原检验结果与初检处置将完整保留，复检数据单独归档
      </div>
      <el-form label-width="90px">
        <el-form-item label="复核员" required>
          <el-input v-model="reviewForm.reviewer" placeholder="复核员姓名（不能是初检判定人）" maxlength="32" />
        </el-form-item>
        <el-form-item v-for="it in (current ? current.items : [])" :key="it.itemCode" :label="it.itemName" required>
          <el-input-number v-model="reviewForm.values[it.itemCode]" :precision="3" :step="0.1" :min="0"
                           controls-position="right" style="width:150px" placeholder="复检测定值" />
          <span class="mini muted" style="margin-left:10px">
            标准 {{ num(it.stdLower) }} ~ {{ num(it.stdUpper) }} {{ it.unit }} · 初检 {{ num(it.measuredValue) }} {{ it.unit }}
          </span>
        </el-form-item>
        <el-form-item label="最终结论" required>
          <el-radio-group v-model="reviewForm.conclusion">
            <el-radio-button value="RELEASE" :disabled="!reviewAllPass">放行</el-radio-button>
            <el-radio-button value="DOWNGRADE" :disabled="!reviewCanDowngrade">降级</el-radio-button>
            <el-radio-button value="REJECT" :disabled="!reviewCanReject">继续退货</el-radio-button>
          </el-radio-group>
          <div class="mini muted" style="margin-top:4px">
            霉菌毒素仍超标只能继续退货；全部恢复标准区间才允许放行；介于两者之间判降级并须明确去向
          </div>
        </el-form-item>
        <el-form-item v-if="reviewForm.conclusion === 'DOWNGRADE'" label="明确去向" required>
          <el-input v-model="reviewForm.disposition" placeholder="降级去向，如：降级销售 / 转作他用" maxlength="64" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="reviewForm.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!reviewComplete" @click="submitReview">
          提交最终结论
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchQcSamples, judgeSample, fetchRechecks, createRecheck, reviewRecheck } from '../api'

const samples = ref([])
const rechecks = ref([])

const createVisible = ref(false)
const reviewVisible = ref(false)
const submitting = ref(false)
const current = ref(null) // 当前操作的初检抽样单
const createForm = ref({ initiator: '', reason: '' })
const reviewForm = ref({ reviewer: '', conclusion: '', disposition: '', remark: '', values: {} })

function conclusionName (c) {
  return { PASS: '合格', DOWNGRADE: '降级', REJECT: '退货' }[c] || '待判定'
}
function conclusionChip (c) {
  return { PASS: 'chip-pass', DOWNGRADE: 'chip-down', REJECT: 'chip-reject' }[c] || 'chip-gray'
}
function failedItems (s) {
  return (s.items || []).filter(i => !i.qualified)
}
function failedCount (s) {
  return failedItems(s).length
}
function mycoFailed (s) {
  return failedItems(s).some(i => i.itemCode === 'MYCOTOXIN')
}
function canPass (s) {
  return failedCount(s) === 0
}
function canDowngrade (s) {
  return failedCount(s) > 0 && !mycoFailed(s)
}
function canReject (s) {
  return failedCount(s) > 0
}
function cellClass ({ row, column }) {
  if (column.property === 'measuredValue' && !row.qualified) return 'item-cell-bad'
  return ''
}
function stampClass (s) {
  return { PASS: 'pass', DOWNGRADE: 'downgrade', REJECT: 'reject' }[s.conclusion] || 'none'
}
function stampTitle (s) {
  return { PASS: '合格', DOWNGRADE: '降级', REJECT: '退货' }[s.conclusion] || '待判定'
}
function stampDate (s) {
  return String(s.sampleTime || '').slice(0, 10) || '——'
}

function num (v) {
  return v === null || v === undefined ? '-' : Number(v).toFixed(3)
}
function fmtTime (t) {
  return String(t || '').replace('T', ' ').slice(0, 16)
}

/* ---------- 退货复检 ---------- */

// 每批次取最新一张复检单（列表按 id 倒序，先出现的即最新）
const recheckMap = computed(() => {
  const map = {}
  for (const r of rechecks.value) {
    const batchId = r.batch && r.batch.id
    if (batchId && !map[batchId]) map[batchId] = r
  }
  return map
})
function recheckOf (s) {
  return recheckMap.value[s.batch.id]
}
function recheckChip (s) {
  const r = recheckOf(s)
  if (!r) return 'chip-gray'
  if (r.status === 'PENDING') return 'chip-run'
  return { RELEASE: 'chip-pass', DOWNGRADE: 'chip-down', REJECT: 'chip-reject' }[r.conclusion] || 'chip-gray'
}
function recheckStatusText (s) {
  const r = recheckOf(s)
  if (!r) return '未发起复检'
  if (r.status === 'PENDING') return '待复核'
  return { RELEASE: '复检放行', DOWNGRADE: '复检降级', REJECT: '继续退货' }[r.conclusion] || '已收口'
}
function recheckConclusionName (c) {
  return { RELEASE: '放行', DOWNGRADE: '降级', REJECT: '继续退货' }[c] || c
}
// 初检/复检对照行：初检值来自原始抽样单（未改写），复检值来自复检单
function recheckRows (s) {
  const r = recheckOf(s)
  if (!r) return []
  const originByCode = {}
  for (const it of s.items || []) originByCode[it.itemCode] = it
  return (r.items || []).map(ri => ({
    itemName: ri.itemName,
    unit: ri.unit,
    originValue: originByCode[ri.itemCode] ? originByCode[ri.itemCode].measuredValue : null,
    recheckValue: ri.measuredValue,
    stdLower: ri.stdLower,
    stdUpper: ri.stdUpper,
    qualified: ri.qualified
  }))
}

// 复核表单：按录入的复检测定值实时判定各结论可用性（与后端硬性规则一致）
const reviewResults = computed(() => {
  if (!current.value) return []
  return (current.value.items || []).map(it => {
    const v = reviewForm.value.values[it.itemCode]
    const entered = v !== null && v !== undefined && v !== ''
    const ok = entered && Number(v) >= Number(it.stdLower) && Number(v) <= Number(it.stdUpper)
    return { itemCode: it.itemCode, entered, ok }
  })
})
const reviewComplete = computed(() =>
  reviewResults.value.length > 0 && reviewResults.value.every(r => r.entered) && !!reviewForm.value.conclusion
)
const reviewMycoFail = computed(() =>
  reviewResults.value.some(r => r.itemCode === 'MYCOTOXIN' && r.entered && !r.ok)
)
const reviewOtherFailCount = computed(() =>
  reviewResults.value.filter(r => r.itemCode !== 'MYCOTOXIN' && r.entered && !r.ok).length
)
const reviewAllPass = computed(() =>
  reviewResults.value.length > 0 && reviewResults.value.every(r => r.entered && r.ok)
)
const reviewCanDowngrade = computed(() => !reviewMycoFail.value && reviewOtherFailCount.value > 0)
const reviewCanReject = computed(() => reviewMycoFail.value || reviewOtherFailCount.value > 0)

function openCreate (s) {
  current.value = s
  createForm.value = { initiator: '', reason: '' }
  createVisible.value = true
}
function openReview (s) {
  current.value = s
  const values = {}
  for (const it of s.items || []) values[it.itemCode] = null
  reviewForm.value = { reviewer: '', conclusion: '', disposition: '', remark: '', values }
  reviewVisible.value = true
}

async function submitCreate () {
  const initiator = createForm.value.initiator.trim()
  const reason = createForm.value.reason.trim()
  if (!initiator) return ElMessage.warning('请填写发起人')
  if (!reason) return ElMessage.warning('请填写发起原因')
  submitting.value = true
  try {
    await createRecheck({ batchId: current.value.batch.id, initiator, reason })
    ElMessage.success(`${current.value.batch.batchNo} 复检单已发起，待复核`)
    createVisible.value = false
    await loadRechecks()
  } catch (e) {
    ElMessage.error(e.message)
    createVisible.value = false
    await loadRechecks() // 并发发起被拦截时，刷新后看到进行中的复检单
  } finally {
    submitting.value = false
  }
}

async function submitReview () {
  const reviewer = reviewForm.value.reviewer.trim()
  if (!reviewer) return ElMessage.warning('请填写复核员')
  if (current.value && reviewer === current.value.sampler) {
    return ElMessage.error('复核员不能与初检判定人为同一人')
  }
  if (!reviewComplete.value) return ElMessage.warning('请录入全部检验项的复检测定值并选择最终结论')
  if (reviewForm.value.conclusion === 'DOWNGRADE' && !reviewForm.value.disposition.trim()) {
    return ElMessage.warning('降级放行必须填写明确去向')
  }
  submitting.value = true
  try {
    const recheck = recheckOf(current.value)
    await reviewRecheck(recheck.id, {
      reviewer,
      conclusion: reviewForm.value.conclusion,
      disposition: reviewForm.value.disposition.trim() || undefined,
      remark: reviewForm.value.remark.trim() || undefined,
      items: (current.value.items || []).map(it => ({
        itemCode: it.itemCode,
        measuredValue: reviewForm.value.values[it.itemCode]
      }))
    })
    ElMessage.success('复检结论已生效，批次状态与统计口径已回写')
    reviewVisible.value = false
    await loadRechecks()
  } catch (e) {
    ElMessage.error(e.message)
    reviewVisible.value = false
    await loadRechecks() // 并发收口：后到请求刷新后看到已完成结果，不能覆盖
  } finally {
    submitting.value = false
  }
}

/* ---------- 数据加载 ---------- */

async function loadRechecks () {
  rechecks.value = await fetchRechecks().catch(() => [])
}

async function judge (s, conclusion) {
  try {
    const updated = await judgeSample(s.id, { conclusion })
    const idx = samples.value.findIndex(x => x.id === s.id)
    if (idx >= 0) samples.value[idx] = updated
    ElMessage.success(`${s.sampleNo} 判定为${conclusionName(conclusion)}，去向：${updated.disposition}`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  const [list] = await Promise.all([fetchQcSamples().catch(() => []), loadRechecks()])
  samples.value = list
})
</script>
