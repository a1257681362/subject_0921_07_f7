package com.evops.schedule.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 防撞模式切换：NEW 系统防撞 / LEGACY 旧手工台账逻辑（异常时一键切回）。 */
@Data
public class ScheduleModeUpdateRequest {
    @NotBlank(message = "防撞模式不能为空")
    private String engineMode;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
