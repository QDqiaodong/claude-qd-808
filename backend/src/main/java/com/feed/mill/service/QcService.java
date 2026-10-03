package com.feed.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.feed.mill.common.BizException;
import com.feed.mill.domain.QcItem;
import com.feed.mill.domain.QcRecheck;
import com.feed.mill.domain.QcSample;
import com.feed.mill.dto.QcFailDistVO;
import com.feed.mill.dto.QcJudgeCommand;
import com.feed.mill.repository.QcRecheckRepository;
import com.feed.mill.repository.QcSampleRepository;

/**
 * 质检与判定：实测值 vs 标准区间，霉菌毒素超标必须判退货，不允许人工改判合格。
 * 检验项集合是 {@code @SortNatural} 的 SortedSet，按检验次序输出。
 */
@Service
public class QcService {

    /** 霉菌毒素检验项编码 */
    public static final String MYCOTOXIN = "MYCOTOXIN";

    private static final Map<String, String> DEFAULT_DISPOSITION = Map.of(
            "PASS", "成品仓入库",
            "DOWNGRADE", "降级销售",
            "REJECT", "回机处理");

    private final QcSampleRepository qcSampleRepository;
    private final QcRecheckRepository qcRecheckRepository;

    public QcService(QcSampleRepository qcSampleRepository, QcRecheckRepository qcRecheckRepository) {
        this.qcSampleRepository = qcSampleRepository;
        this.qcRecheckRepository = qcRecheckRepository;
    }

    @Transactional(readOnly = true)
    public List<QcSample> samples() {
        return qcSampleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public QcSample sample(Long id) {
        return qcSampleRepository.findById(id)
                .orElseThrow(() -> new BizException("质检抽样单不存在：id=" + id));
    }

    /**
     * 判定结论。
     * 规则一：霉菌毒素超标必须判退货，人工改判合格一律拦截。
     * 规则二：存在其它不合格项时不允许直接判合格。
     */
    @Transactional
    public QcSample judge(Long sampleId, QcJudgeCommand command) {
        QcSample sample = sample(sampleId);
        String conclusion = command.getConclusion() == null ? "" : command.getConclusion().toUpperCase();
        if (!conclusion.equals("PASS") && !conclusion.equals("DOWNGRADE") && !conclusion.equals("REJECT")) {
            throw new BizException("判定结论只能是 合格(PASS) / 降级(DOWNGRADE) / 退货(REJECT)");
        }

        List<QcItem> failed = new ArrayList<>();
        QcItem mycotoxin = null;
        for (QcItem item : sample.getItems()) {
            if (Boolean.FALSE.equals(item.getQualified())) {
                failed.add(item);
                if (MYCOTOXIN.equals(item.getItemCode())) {
                    mycotoxin = item;
                }
            }
        }

        if (mycotoxin != null && !"REJECT".equals(conclusion)) {
            throw new BizException("霉菌毒素实测 " + strip(mycotoxin.getMeasuredValue()) + mycotoxin.getUnit()
                    + " 超出标准上限 " + strip(mycotoxin.getStdUpper()) + mycotoxin.getUnit()
                    + "，按规程必须判退货，不允许人工改判为合格或降级");
        }
        if ("PASS".equals(conclusion) && !failed.isEmpty()) {
            QcItem first = failed.get(0);
            throw new BizException(first.getItemName() + "实测 " + strip(first.getMeasuredValue()) + first.getUnit()
                    + " 不在标准区间 [" + strip(first.getStdLower()) + ", " + strip(first.getStdUpper()) + "]"
                    + first.getUnit() + "，不能判合格，请选择降级或退货");
        }
        if (!"PASS".equals(conclusion) && failed.isEmpty()) {
            throw new BizException("各检验项均在标准区间内，无需判为降级或退货");
        }

        sample.setConclusion(conclusion);
        sample.setDisposition(command.getDisposition() == null || command.getDisposition().isBlank()
                ? DEFAULT_DISPOSITION.getOrDefault(conclusion, "待处理")
                : command.getDisposition());
        if (command.getRemark() != null) {
            sample.setRemark(command.getRemark());
        }
        return qcSampleRepository.save(sample);
    }

    /** 质检不合格项分布（手写 JPQL） */
    @Transactional(readOnly = true)
    public List<QcFailDistVO> failDistribution() {
        List<QcFailDistVO> result = new ArrayList<>();
        for (Object[] row : qcSampleRepository.failDistribution()) {
            QcFailDistVO vo = new QcFailDistVO();
            vo.setItemCode((String) row[0]);
            vo.setItemName((String) row[1]);
            vo.setCheckCount(toLong(row[2]));
            vo.setFailCount(toLong(row[3]));
            BigDecimal rate = vo.getCheckCount() == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(vo.getFailCount())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(vo.getCheckCount()), 2, RoundingMode.HALF_UP);
            vo.setFailRate(rate);
            result.add(vo);
        }
        return result;
    }

    /**
     * 结论分布：按每批最终有效结论统计 —— 复检已收口的退货批次以复检结论为准
     * （放行 RELEASE 计入合格 PASS），未发起或未收口的批次仍按初检原口径。
     * 初检记录本身不改写，口径通过复检单叠加得到，批次状态 / 质检卡片 / 概览三者一致。
     */
    @Transactional(readOnly = true)
    public Map<String, Long> conclusionStats() {
        Map<Long, String> finalByBatch = new HashMap<>();
        for (QcRecheck recheck : qcRecheckRepository.findByStatusOrderByIdDesc(QcRecheckService.STATUS_DONE)) {
            String conclusion = QcRecheckService.RELEASE.equals(recheck.getConclusion())
                    ? "PASS" : recheck.getConclusion();
            finalByBatch.putIfAbsent(recheck.getBatch().getId(), conclusion);
        }
        Map<String, Long> stats = new LinkedHashMap<>();
        for (QcSample sample : qcSampleRepository.findAll()) {
            String effective = finalByBatch.getOrDefault(sample.getBatch().getId(), sample.getConclusion());
            if (effective != null) {
                stats.merge(effective, 1L, Long::sum);
            }
        }
        return stats;
    }

    private static String strip(BigDecimal value) {
        return value == null ? "-" : value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static Long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
