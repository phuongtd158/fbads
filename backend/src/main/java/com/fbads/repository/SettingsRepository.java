package com.fbads.repository;

import com.fbads.entity.AppSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<AppSettings, Integer> {}
