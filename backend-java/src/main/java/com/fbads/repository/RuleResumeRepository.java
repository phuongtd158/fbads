package com.fbads.repository;

import com.fbads.entity.RuleObjKey;
import com.fbads.entity.RuleResume;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleResumeRepository extends JpaRepository<RuleResume, RuleObjKey> {}
