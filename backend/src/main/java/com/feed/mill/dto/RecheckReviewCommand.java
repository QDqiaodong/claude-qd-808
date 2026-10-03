package com.feed.mill.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 复检复核收口：复核员 + 新检验值 + 最终结论。
 * 霉菌毒素仍超标必须继续退货；全部恢复标准区间才允许放行；介于两者之间判降级并须明确去向。
 */
@Getter
@Setter
public class RecheckReviewCommand {

    /** 复核员（必须不同于初检判定人） */
    @NotBlank(message = "复核员不能为空")
    private String reviewer;

    /** 最终结论 RELEASE/DOWNGRADE/REJECT */
    @NotBlank(message = "复检结论不能为空")
    private String conclusion;

    /** 最终去向（降级时必填，须明确） */
    private String disposition;

    private String remark;

    /** 复检测定值：须覆盖初检全部检验项 */
    @Valid
    @NotEmpty(message = "复检检验值不能为空")
    private List<Item> items;

    @Getter
    @Setter
    public static class Item {

        @NotBlank(message = "检验项编码不能为空")
        private String itemCode;

        @NotNull(message = "复检测定值不能为空")
        private BigDecimal measuredValue;
    }
}
