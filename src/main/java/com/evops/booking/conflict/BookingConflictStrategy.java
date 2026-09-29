package com.evops.booking.conflict;

import com.evops.booking.entity.MachineAppointment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排班防撞口径：给定农机上当前仍占位（HELD/CONFIRMED）的预约，判定新窗口是否可插入。
 * 新旧两套实现只在"间隔缓冲"上有差异，严格时段重叠两者都拦。
 */
public interface BookingConflictStrategy {

    /** NEW / LEGACY。 */
    String code();

    /**
     * 检查冲突。
     *
     * @param occupied      该农机仍占位的预约（HELD/CONFIRMED，已剔除过期锁）
     * @param start         新预约开始
     * @param end           新预约结束
     * @param bufferMinutes 相邻缓冲分钟（仅新口径使用）
     * @param now           当前时间
     * @return 冲突描述；无冲突返回 null
     */
    ConflictInfo check(List<MachineAppointment> occupied,
                       LocalDateTime start, LocalDateTime end,
                       int bufferMinutes, LocalDateTime now);
}
