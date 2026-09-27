package com.fbads.repository;

import com.fbads.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleRepository extends JpaRepository<Rule, String> {
    List<Rule> findAllByOrderBySeqAsc();

    List<Rule> findByEnabledTrueOrderBySeqAsc();
}
