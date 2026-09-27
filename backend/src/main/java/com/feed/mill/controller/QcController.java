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
import com.feed.mill.domain.QcSample;
import com.feed.mill.dto.QcFailDistVO;
import com.feed.mill.dto.QcJudgeCommand;
import com.feed.mill.service.QcService;

import jakarta.validation.Valid;

/**
 * 质检与判定：检验项实测值 vs 标准区间，结论盖章。
 * 检验项是 @SortNatural 有序 SortedSet。
 */
@RestController
@RequestMapping("/api/qc")
public class QcController {

    private final QcService qcService;

    public QcController(QcService qcService) {
        this.qcService = qcService;
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
