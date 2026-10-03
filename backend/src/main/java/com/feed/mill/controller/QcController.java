package com.feed.mill.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.feed.mill.common.ApiResponse;
import com.feed.mill.domain.QcRecheck;
import com.feed.mill.domain.QcSample;
import com.feed.mill.dto.QcFailDistVO;
import com.feed.mill.dto.QcJudgeCommand;
import com.feed.mill.dto.RecheckCreateCommand;
import com.feed.mill.dto.RecheckReviewCommand;
import com.feed.mill.service.QcRecheckService;
import com.feed.mill.service.QcService;

import jakarta.validation.Valid;

/**
 * 质检与判定：检验项实测值 vs 标准区间，结论盖章。
 * 检验项是 @SortNatural 有序 SortedSet。
 * 退货批次复检闭环：仅初检退货批次可发起，复核员与初检判定人分离，最终结论并发收口。
 */
@RestController
@RequestMapping("/api/qc")
public class QcController {

    private final QcService qcService;
    private final QcRecheckService recheckService;

    public QcController(QcService qcService, QcRecheckService recheckService) {
        this.qcService = qcService;
        this.recheckService = recheckService;
    }

    /** 抽样批次列表（含检验项） */
    @GetMapping("/samples")
    public ApiResponse<List<QcSample>> samples() {
        return ApiResponse.success(qcService.samples());
    }

    @GetMapping("/samples/{id}")
    public ApiResponse<QcSample> sample(@PathVariable Long id) {
        return ApiResponse.success(qcService.sample(id));
    }

    /** 判定结论：霉菌毒素超标只能判退货 */
    @PostMapping("/samples/{id}/judge")
    public ApiResponse<QcSample> judge(@PathVariable Long id, @Valid @RequestBody QcJudgeCommand command) {
        return ApiResponse.success("判定已保存", qcService.judge(id, command));
    }

    /** 复检单列表（含复检测定值），新→旧 */
    @GetMapping("/rechecks")
    public ApiResponse<List<QcRecheck>> rechecks() {
        return ApiResponse.success(recheckService.list());
    }

    /** 某批次的复检单，新→旧；历史退货批次无复检资料时返回空列表 */
    @GetMapping("/rechecks/batch/{batchId}")
    public ApiResponse<List<QcRecheck>> rechecksByBatch(@PathVariable Long batchId) {
        return ApiResponse.success(recheckService.listByBatch(batchId));
    }

    /** 发起复检：仅初检结论为退货的批次，保留发起人/原因/待复核状态 */
    @PostMapping("/rechecks")
    public ApiResponse<QcRecheck> createRecheck(@Valid @RequestBody RecheckCreateCommand command) {
        return ApiResponse.success("复检单已发起，待复核", recheckService.create(command));
    }

    /** 复核收口：复核员须不同于初检判定人；并发提交只有一单生效 */
    @PostMapping("/rechecks/{id}/review")
    public ApiResponse<QcRecheck> reviewRecheck(@PathVariable Long id, @Valid @RequestBody RecheckReviewCommand command) {
        return ApiResponse.success("复检结论已生效，批次状态与统计口径已回写", recheckService.review(id, command));
    }

    /** 质检不合格项分布（手写 JPQL） */
    @GetMapping("/stats/fail-distribution")
    public ApiResponse<List<QcFailDistVO>> failDistribution() {
        return ApiResponse.success(qcService.failDistribution());
    }

    /** 结论分布：合格/降级/退货 */
    @GetMapping("/stats/conclusion")
    public ApiResponse<Map<String, Long>> conclusionStats() {
        return ApiResponse.success(qcService.conclusionStats());
    }
}
