package com.fbads.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyReportRepository extends JpaRepository<CompanyReport, String> {
    /** Mới nhất trước */
    List<CompanyReport> findAllByOrderBySeqDesc();

    Optional<CompanyReport> findByTeamIdAndDateAndSlot(String teamId, String date, int slot);

    List<CompanyReport> findByStatus(String status);

    List<CompanyReport> findByDateGreaterThanEqual(String date);
}
