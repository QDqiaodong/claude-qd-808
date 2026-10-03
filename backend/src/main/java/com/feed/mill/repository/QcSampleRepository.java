package com.feed.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.feed.mill.domain.QcSample;

/**
 * 质检抽样。{@link #failDistribution()} 是手写 JPQL 聚合：质检不合格项分布。
 */
public interface QcSampleRepository extends JpaRepository<QcSample, Long> {

    /** 某批次最新一张初检抽样单（复检发起时定位初检记录用） */
    Optional<QcSample> findFirstByBatch_IdOrderByIdDesc(Long batchId);

    /**
     * 检验项不合格分布（初检实测口径，复检不改写原始记录，该统计保持原口径）。
     * 返回列：检验项编码、检验项名称、检验次数、不合格次数
     */
    @Query("select i.itemCode, i.itemName, count(i), sum(case when i.qualified = false then 1 else 0 end) "
            + "from QcSample s join s.items i "
            + "group by i.itemCode, i.itemName "
            + "order by sum(case when i.qualified = false then 1 else 0 end) desc, i.itemCode")
    List<Object[]> failDistribution();
}
