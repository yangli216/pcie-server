package com.regionalai.floatingball.server.modules.analytics.dto;

import lombok.Data;

import java.util.List;

@Data
public class FunctionUsageResponseVO {
    private long totalCallCount;
    private long avgDailyCalls;
    private String usageRate;
    private List<FunctionUsageItemVO> ranking;
    private long current;
    private long size;
    private long total;
    private List<FunctionUsageItemVO> records;
    private FunctionUsageTrendVO trend;
}
