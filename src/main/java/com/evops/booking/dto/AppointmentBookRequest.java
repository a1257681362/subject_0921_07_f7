package com.evops.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 新增预约：指定农机 + 作业时段。提交后先 HELD 临时锁定（默认 35 分钟），
 * 防撞按当前口径（新：重叠 + 18 分钟缓冲；首尾相接豁免）。
 */
@Data
public class AppointmentBookRequest {
    @NotNull(message = "农机不能为空")
    private Long machineId;
    private String plotName;
    @NotBlank(message = "机手工号不能为空")
    private String operatorCode;
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;
    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;
    private String remark;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
