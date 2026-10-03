package com.feed.mill.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.feed.mill.domain.QcRecheck;

/**
 * 退货批次复检单。
 */
public interface QcRecheckRepository extends JpaRepository<QcRecheck, Long> {

    /** 某批次指定状态的最新一张复检单（查进行中用） */
    Optional<QcRecheck> findFirstByBatch_IdAndStatusOrderByIdDesc(Long batchId, String status);

    /** 某批次的全部复检单，新→旧 */
    List<QcRecheck> findByBatch_IdOrderByIdDesc(Long batchId);

    /** 全部复检单，新→旧 */
    List<QcRecheck> findAllByOrderByIdDesc();

    /** 指定状态的复检单，新→旧（概览最终结论口径用） */
    List<QcRecheck> findByStatusOrderByIdDesc(String status);

    long countByBatch_Id(Long batchId);

    /**
     * 并发收口：仅当复检单仍是 PENDING 时才写入最终结论。
     * 返回 0 表示已被其他复核员抢先收口，后到请求必须读取已完成结果，不能覆盖。
     */
    @Modifying
    @Query("update QcRecheck r set r.status = 'DONE', r.reviewer = :reviewer, r.conclusion = :conclusion, "
            + "r.disposition = :disposition, r.remark = :remark, r.reviewedAt = :reviewedAt "
            + "where r.id = :id and r.status = 'PENDING'")
    int closeIfPending(@Param("id") Long id,
                       @Param("reviewer") String reviewer,
                       @Param("conclusion") String conclusion,
                       @Param("disposition") String disposition,
                       @Param("remark") String remark,
                       @Param("reviewedAt") LocalDateTime reviewedAt);
}
