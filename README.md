# 饲料加工厂 · 配方批次与质检管理

以「配方 → 投料 → 制粒 → 质检」连续生产闭环为核心的饲料厂中控演示系统。

- 前端：Vue 3 + Element Plus + Vite（单页滚动 + 顶部锚点吸顶条，主色 `#b71c1c`）
- 后端：Spring Boot 3.3.0 + Java 17 + Spring Data JPA(Hibernate 6.5) + MySQL 8 + Redis
- 端口：前端 8288 / 后端 8388 / MySQL 3588 / Redis 6588

## 启动

```bash
./start.sh          # 镜像已存在时秒起
./start.sh --build  # 首次或代码变更后构建
./stop.sh           # 停止
./resume.sh         # 恢复
./clean.sh          # 收摊（down -v，清匿名卷）
```

## 五个区块（一页竖排，锚点吸顶高亮）

| 锚点 | 内容 |
| --- | --- |
| 01 概览 | 今日批次 / 累计产量 / 合格率 三张指标卡 + 纯 SVG 近 7 日产量折线 |
| 02 配料 | 横向堆叠占比条，点色段微调 ±0.5%，锁定某原料后自动平摊其余至 100% |
| 03 投料 | 投料口大按钮阵列，按投料顺序称量，环形倒计时 + 脉冲动画，误差超 ±2% 拦截记异常 |
| 04 制粒 | 温度 / 蒸汽压力 / 环模孔径 三滑块，实时给出推荐环模寿命消耗与寿命消耗条 |
| 05 质检 | 抽样批次卡片 + 检验项表格（超标标红）+ CSS 圆形印章判定 |

## 数据访问路线（本仓技术标识）

- 有序集合：`Recipe.items`、`QcSample.items` 用 `@SortNatural` + `SortedSet`（实体实现 `Comparable`）；
  `Batch.feedings` 用 `@SortComparator(FeedingItem.FeedSeqComparator)` + `SortedSet` 保证严格按投料顺序。
  不使用 `@OrderColumn`，也不是只读 `@OrderBy`。
- 手写 JPQL（`Object[]` 聚合投影）：
  - `RecipeRepository#speciesStats` —— 按畜种统计配方数与成本
  - `FeedingItemRepository#batchFeedErrorStats` —— 按批次统计投料误差率与异常条数
  - `QcSampleRepository#failDistribution` —— 质检不合格项分布
  - `BatchRepository#dailyOutput` —— 按生产日期统计产量（概览折线）
- Redis：概览结果缓存 30 秒（`feed-mill:dashboard`），Redis 不可用时自动降级直查。

## 业务规则（service 抛 BizException）

1. 配方明细占比合计必须 100.00%，否则发布拦截并给出差值。
2. 单一原料不得超过其占比上限（鱼粉 ≤ 8%、预混料 ≤ 4%、石粉 ≤ 3% …）。
3. 投料必须按投料口顺序；实际称重与计划量偏差超过 ±2% 拦截并记为异常。
4. 环模累计产量 + 本批产量超过寿命上限不允许继续制粒（记录 BLOCKED）。
5. 霉菌毒素超标必须判退货，不允许人工改判合格；其它项超标不允许判合格。
6. 退货批次复检闭环：仅初检结论为退货的批次可发起复检（保留发起人/原因/待复核状态）；
   复核员必须不同于初检判定人；复检测定值落新表，原始检验结果与初检处置不改写；
   霉菌毒素仍超标必须继续退货，全部恢复标准区间才允许放行，介于两者之间判降级并须明确去向；
   同一批次同一时刻只有一张进行中的复检单（`qc_recheck.active_key` 生成列唯一索引），
   最终结论用条件更新原子收口，后到请求只能看到已完成结果，不能覆盖；
   收口后批次状态置为终态 DONE，概览合格率/结论分布按每批最终有效结论统计（放行计入合格）。

## 接口

```
GET  /api/materials                 原料与筒仓
GET  /api/materials/low-stock       缺料预警
GET  /api/recipes                   配方列表
GET  /api/recipes/{id}              配方详情（@SortNatural 有序明细）
POST /api/recipes/{id}/adjust       占比微调 ±0.5%
POST /api/recipes/{id}/spread       锁定占比并自动平摊
POST /api/recipes/{id}/submit       发布校验（合计 100%）
GET  /api/recipes/stats/species     按畜种统计配方数与成本
GET  /api/batches                   批次列表
GET  /api/batches/{id}              批次详情（@SortComparator 有序投料明细）
POST /api/batches/{id}/feeding      开始称量 / 确认投料
GET  /api/batches/stats/feed-error  按批次统计投料误差率
GET  /api/pellets                   制粒记录（按环模磨损倒序）
POST /api/pellets/batch/{id}/adjust 工艺参数调整 + 推荐寿命消耗
POST /api/pellets/batch/{id}/produce 制粒产出（超寿命拦截）
GET  /api/qc/samples                抽样批次（@SortNatural 有序检验项）
POST /api/qc/samples/{id}/judge     质检判定
GET  /api/qc/rechecks               复检单列表（含复检测定值）
GET  /api/qc/rechecks/batch/{id}    某批次复检单（无复检资料返回空）
POST /api/qc/rechecks               发起复检（仅初检退货批次）
POST /api/qc/rechecks/{id}/review   复核收口（人员分离 + 并发只一单生效）
GET  /api/qc/stats/fail-distribution 不合格项分布
GET  /api/stats/dashboard           工段概览
```
