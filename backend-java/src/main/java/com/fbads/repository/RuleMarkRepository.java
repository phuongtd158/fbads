package com.fbads.repository;

import com.fbads.entity.RuleMark;
import com.fbads.entity.RuleObjKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleMarkRepository extends JpaRepository<RuleMark, RuleObjKey> {}
