<template>
  <div>
    <div class="section-head">
      <h2>05 质检</h2>
      <span>抽样批次卡片 · 实测值 vs 标准区间（超标标红）· CSS 圆形印章判定（霉菌毒素超标只能盖退货章）</span>
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
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchQcSamples, judgeSample } from '../api'

const samples = ref([])

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
  samples.value = await fetchQcSamples().catch(() => [])
})
</script>
