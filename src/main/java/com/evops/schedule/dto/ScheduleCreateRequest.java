package com.evops.schedule.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 新增农机排班预约。时间格式 yyyy-MM-dd HH:mm:ss（也兼容 ISO）。 */
@Data
public class ScheduleCreateRequest {
    @NotBlank(message = "预约单号不能为空")
    private String scheduleNo;
    @NotNull(message = "农机不能为空")
    private Long machineId;
    private String workOrderNo;
    private String operatorCode;
    private String plotName;
    @NotBlank(message = "开始时间不能为空")
    private String startTime;
    @NotBlank(message = "结束时间不能为空")
    private String endTime;
    private String remark;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
