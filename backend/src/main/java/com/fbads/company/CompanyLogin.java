package com.fbads.company;

import java.util.List;
import java.util.Map;

/** Đăng nhập thử: người dùng trên hệ thống công ty + các Team */
public record CompanyLogin(Map<String, String> user, List<CompanyApi.Team> teams) {}
