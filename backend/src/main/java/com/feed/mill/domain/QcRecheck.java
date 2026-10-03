package com.feed.mill.domain;

import java.time.LocalDateTime;
import java.util.SortedSet;
import java.util.TreeSet;

import org.hibernate.annotations.SortNatural;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 退货批次复检单：仅初检结论为退货的批次可发起。
 * 原始检验结果与初检处置保留在 {@link QcSample} 中不改写，复检测定值落在 {@link QcRecheckItem}。
 * 数据库 active_key 生成列 + 唯一索引保证：同一批次同一时刻只有一张 PENDING（进行中）复检单。
 */
@Entity
@Table(name = "qc_recheck")
@Getter
@Setter
public class QcRecheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recheck_no", nullable = false, unique = true, length = 40)
    private String recheckNo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    /** 初检抽样单（快照引用，原记录只读不改写） */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "sample_id", nullable = false)
    private QcSample sample;

    /** 发起人 */
    @Column(name = "initiator", nullable = false, length = 32)
    private String initiator;

    /** 发起原因 */
    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    /** 状态 PENDING 待复核 / DONE 已收口 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 复核员（必须不同于初检判定人） */
    @Column(name = "reviewer", length = 32)
    private String reviewer;

    /** 最终结论 RELEASE 放行 / DOWNGRADE 降级 / REJECT 继续退货 */
    @Column(name = "conclusion", length = 16)
    private String conclusion;

    /** 最终去向 */
    @Column(name = "disposition", length = 64)
    private String disposition;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 有序复检检验项集合 */
    @OneToMany(mappedBy = "recheck", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @SortNatural
    private SortedSet<QcRecheckItem> items = new TreeSet<>();
}
