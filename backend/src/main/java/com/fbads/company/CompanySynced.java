package com.fbads.company;

import java.util.List;

public record CompanySynced(int synced, List<CompanyReport> reports) {}
