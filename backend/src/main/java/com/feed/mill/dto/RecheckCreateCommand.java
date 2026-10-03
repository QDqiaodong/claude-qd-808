package com.feed.mill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 发起退货批次复检：批次 + 发起人 + 原因，发起后处于待复核状态。
 */
@Getter
@Setter
public class RecheckCreateCommand {

    @NotNull(message = "复检批次不能为空")
    private Long batchId;

    /** 发起人 */
    @NotBlank(message = "发起人不能为空")
    private String initiator;

    /** 发起原因 */
    @NotBlank(message = "发起原因不能为空")
    private String reason;
}
