package com.fbads.settings;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingsRepository extends JpaRepository<AppSettings, Integer> {}
