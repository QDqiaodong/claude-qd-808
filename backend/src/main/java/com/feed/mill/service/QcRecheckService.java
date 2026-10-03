package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.Batch;
import com.feed.mill.domain.QcItem;
import com.feed.mill.domain.QcRecheck;
import com.feed.mill.domain.QcRecheckItem;
import com.feed.mill.domain.QcSample;
import com.feed.mill.dto.RecheckCreateCommand;
import com.feed.mill.dto.RecheckReviewCommand;
import com.feed.mill.repository.BatchRepository;
import com.feed.mill.repository.QcRecheckRepository;
import com.feed.mill.repository.QcSampleRepository;

/**
 * 退货批次复检放行闭环。
 * <ul>
 *   <li>只有初检结论为退货的批次可以发起复检，发起人、原因、待复核状态全程留痕；</li>
 *   <li>复检必须由不同于初检判定人的复核员执行，新检验值落在复检单上，原始检验结果与初检处置不改写；</li>
 *   <li>霉菌毒素仍超标必须继续退货；全部恢复标准区间才允许放行；介于两者之间判降级并须明确去向；</li>
 *   <li>同一批次同一时刻只有一张进行中的复检单（数据库 active_key 唯一索引兜底），
 *       最终结论用条件更新原子收口，后到请求只能看到已完成结果，不能覆盖。</li>
 * </ul>
 */
@Service
public class QcRecheckService {

    /** 复检结论：放行 */
    public static final String RELEASE = "RELEASE";
    /** 复检结论：降级 */
    public static final String DOWNGRADE = "DOWNGRADE";
    /** 复检结论：继续退货 */
    public static final String REJECT = "REJECT";

    /** 复检单状态：待复核（进行中） */
    public static final String STATUS_PENDING = "PENDING";
    /** 复检单状态：已收口 */
    public static final String STATUS_DONE = "DONE";

    private static final Map<String, String> CONCLUSION_NAME = Map.of(
            RELEASE, "放行", DOWNGRADE, "降级", REJECT, "继续退货");

    private static final Map<String, String> INITIAL_CONCLUSION_NAME = Map.of(
            "PASS", "合格", "DOWNGRADE", "降级", "REJECT", "退货");

    private final QcRecheckRepository recheckRepository;
    private final QcSampleRepository qcSampleRepository;
    private final BatchRepository batchRepository;
    private final TransactionTemplate requiresNewTx;

    public QcRecheckService(QcRecheckRepository recheckRepository,
                            QcSampleRepository qcSampleRepository,
                            BatchRepository batchRepository,
                            PlatformTransactionManager transactionManager) {
        this.recheckRepository = recheckRepository;
        this.qcSampleRepository = qcSampleRepository;
        this.batchRepository = batchRepository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public List<QcRecheck> list() {
        return recheckRepository.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<QcRecheck> listByBatch(Long batchId) {
        return recheckRepository.findByBatch_IdOrderByIdDesc(batchId);
    }

    /**
     * 发起复检：仅初检结论为退货（REJECT）的批次可进入，其他批次一律拦截。
     * 发起后状态为待复核（PENDING），保留发起人与原因。
     */
    @Transactional
    public QcRecheck create(RecheckCreateCommand command) {
        Batch batch = batchRepository.findById(command.getBatchId())
                .orElseThrow(() -> new BizException("生产批次不存在：id=" + command.getBatchId()));
        QcSample sample = qcSampleRepository.findFirstByBatch_IdOrderByIdDesc(batch.getId())
                .orElseThrow(() -> new BizException("批次 " + batch.getBatchNo() + " 还没有初检记录，不能发起复检"));
        if (!REJECT.equals(sample.getConclusion())) {
            String conclusion = sample.getConclusion() == null ? "未判定"
                    : INITIAL_CONCLUSION_NAME.getOrDefault(sample.getConclusion(), sample.getConclusion());
            throw new BizException("只有初检结论为退货的批次才能发起复检，批次 " + batch.getBatchNo()
                    + " 初检结论为「" + conclusion + "」，不能进入复检流程");
        }
        recheckRepository.findFirstByBatch_IdAndStatusOrderByIdDesc(batch.getId(), STATUS_PENDING)
                .ifPresent(existing -> {
                    throw new BizException("批次 " + batch.getBatchNo() + " 已存在进行中的复检单 "
                            + existing.getRecheckNo() + "（待复核），不能重复发起");
                });

        QcRecheck recheck = new QcRecheck();
        recheck.setRecheckNo("RC-" + batch.getBatchNo() + "-" + (recheckRepository.countByBatch_Id(batch.getId()) + 1));
        recheck.setBatch(batch);
        recheck.setSample(sample);
        recheck.setInitiator(command.getInitiator().trim());
        recheck.setReason(command.getReason().trim());
        recheck.setStatus(STATUS_PENDING);
        recheck.setCreatedAt(LocalDateTime.now());
        try {
            // 并发兜底：active_key 生成列唯一索引，保证同一批次同一时刻只有一张进行中的复检单
            return recheckRepository.saveAndFlush(recheck);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("批次 " + batch.getBatchNo() + " 已存在进行中的复检单，请刷新后查看");
        }
    }

    /**
     * 复核收口：录入新检验值并给出最终结论。
     * 先用条件更新原子占位（仍 PENDING 才允许收口），再落复检检验值，
     * 并发时只有一单生效，后到请求抛出已完成结果且整体回滚，不会覆盖。
     */
    @Transactional
    public QcRecheck review(Long recheckId, RecheckReviewCommand command) {
        QcRecheck recheck = recheckRepository.findById(recheckId)
                .orElseThrow(() -> new BizException("复检单不存在：id=" + recheckId));
        if (!STATUS_PENDING.equals(recheck.getStatus())) {
            throw alreadyClosed(recheck);
        }

        QcSample sample = recheck.getSample();
        String reviewer = command.getReviewer().trim();
        if (reviewer.equals(sample.getSampler())) {
            throw new BizException("复核员「" + reviewer + "」与初检判定人为同一人，复检必须由不同复核员执行");
        }

        String conclusion = command.getConclusion() == null ? "" : command.getConclusion().toUpperCase();
        if (!CONCLUSION_NAME.containsKey(conclusion)) {
            throw new BizException("复检结论只能是 放行(RELEASE) / 降级(DOWNGRADE) / 继续退货(REJECT)");
        }

        // 复检测定值逐项核对初检检验项，标准区间快照自初检，原始记录不改写
        Map<String, BigDecimal> measured = new HashMap<>();
        for (RecheckReviewCommand.Item input : command.getItems()) {
            measured.put(input.getItemCode(), input.getMeasuredValue());
        }
        List<QcRecheckItem> items = new ArrayList<>();
        QcRecheckItem mycotoxinFail = null;
        List<QcRecheckItem> otherFails = new ArrayList<>();
        for (QcItem origin : sample.getItems()) {
            BigDecimal value = measured.get(origin.getItemCode());
            if (value == null) {
                throw new BizException("复检检验值不完整：缺少「" + origin.getItemName() + "」的测定值");
            }
            QcRecheckItem item = new QcRecheckItem();
            item.setRecheck(recheck);
            item.setItemCode(origin.getItemCode());
            item.setItemName(origin.getItemName());
            item.setCheckOrder(origin.getCheckOrder());
            item.setUnit(origin.getUnit());
            item.setMeasuredValue(value);
            item.setStdLower(origin.getStdLower());
            item.setStdUpper(origin.getStdUpper());
            item.setQualified(within(value, origin.getStdLower(), origin.getStdUpper()));
            items.add(item);
            if (Boolean.FALSE.equals(item.getQualified())) {
                if (QcService.MYCOTOXIN.equals(item.getItemCode())) {
                    mycotoxinFail = item;
                } else {
                    otherFails.add(item);
                }
            }
        }

        // 硬性规则：霉菌毒素仍超标必须继续退货；全部恢复才允许放行；介于两者之间判降级并明确去向
        if (mycotoxinFail != null && !REJECT.equals(conclusion)) {
            throw new BizException("霉菌毒素复检测定 " + strip(mycotoxinFail.getMeasuredValue()) + mycotoxinFail.getUnit()
                    + " 仍超标准上限 " + strip(mycotoxinFail.getStdUpper()) + mycotoxinFail.getUnit()
                    + "，按规程必须继续退货，不允许放行或降级");
        }
        if (mycotoxinFail == null && otherFails.isEmpty() && !RELEASE.equals(conclusion)) {
            throw new BizException("全部检验项已恢复到标准区间，应判放行，不能判为降级或继续退货");
        }
        if (mycotoxinFail == null && !otherFails.isEmpty() && !DOWNGRADE.equals(conclusion)) {
            throw new BizException("霉菌毒素已达标，但仍有 " + otherFails.size() + " 项（"
                    + otherFails.get(0).getItemName() + " 等）超出标准区间，不允许直接放行，应判降级并明确去向");
        }
        String disposition = command.getDisposition();
        if (DOWNGRADE.equals(conclusion) && (disposition == null || disposition.isBlank())) {
            throw new BizException("降级放行必须填写明确去向（如：降级销售 / 转作他用），不允许留空");
        }
        if (disposition == null || disposition.isBlank()) {
            disposition = RELEASE.equals(conclusion) ? "成品仓入库" : "回机处理";
        }

        // 并发收口：条件更新原子占位，只有仍是待复核的单子才允许写入最终结论
        LocalDateTime now = LocalDateTime.now();
        int closed = recheckRepository.closeIfPending(recheckId, reviewer, conclusion, disposition,
                command.getRemark(), now);
        if (closed == 0) {
            throw alreadyClosed(reloadCommitted(recheckId));
        }

        // 占位成功：落复检检验值（新行，不触碰初检记录），同步内存实体与批量更新后的字段
        recheck.getItems().addAll(items);
        recheck.setStatus(STATUS_DONE);
        recheck.setReviewer(reviewer);
        recheck.setConclusion(conclusion);
        recheck.setDisposition(disposition);
        recheck.setRemark(command.getRemark());
        recheck.setReviewedAt(now);

        // 回写批次状态：复检收口即终态（放行/降级/继续退货），概览在制口径随之一致
        Batch batch = recheck.getBatch();
        batch.setStatus("DONE");
        batchRepository.save(batch);
        return recheck;
    }

    /** 复检单已被收口：后到请求必须看到已完成结果，不能覆盖 */
    private BizException alreadyClosed(QcRecheck done) {
        return new BizException("复检单 " + done.getRecheckNo() + " 已由 " + done.getReviewer()
                + " 复核收口，最终结论：" + CONCLUSION_NAME.getOrDefault(done.getConclusion(), String.valueOf(done.getConclusion()))
                + "（去向：" + done.getDisposition() + "），后到请求不能覆盖已完成结果");
    }

    /** 并发场景下当前事务的快照仍读到旧状态，用独立新事务读取已提交的复核结果 */
    private QcRecheck reloadCommitted(Long recheckId) {
        QcRecheck committed = requiresNewTx.execute(tx -> recheckRepository.findById(recheckId).orElse(null));
        return committed == null
                ? recheckRepository.findById(recheckId).orElseThrow(() -> new BizException("复检单不存在：id=" + recheckId))
                : committed;
    }

    private static boolean within(BigDecimal value, BigDecimal lower, BigDecimal upper) {
        if (lower != null && value.compareTo(lower) < 0) {
            return false;
        }
        return upper == null || value.compareTo(upper) <= 0;
    }

    private static String strip(BigDecimal value) {
        return value == null ? "-" : value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
