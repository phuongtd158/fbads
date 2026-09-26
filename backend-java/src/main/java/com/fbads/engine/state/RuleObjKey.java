package com.fbads.engine.state;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Khoá ghép rule + camp/nhóm QC (record làm @EmbeddedId: bất biến, equals/hashCode có sẵn). */
@Embeddable
public record RuleObjKey(@Column(name = "rule_id") String ruleId, @Column(name = "obj_id") String objId) {}
