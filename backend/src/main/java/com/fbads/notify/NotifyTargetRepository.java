package com.fbads.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotifyTargetRepository extends JpaRepository<NotifyTarget, String> {
    /** Theo thứ tự đã thêm */
    List<NotifyTarget> findAllByOrderBySeqAsc();
}
