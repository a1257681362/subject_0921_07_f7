# 农机排班防撞 上线/值班 Runbook（F7）

作业站的手工排班台账错漏不少，F7 把同一台农机的预约统一收进系统防撞。
本手册含规则口径、上线三步（备份→变更→验证）、**异常一键切回旧手工逻辑的预案**、值班常见处置。

---

## 一、防撞规则（务必先对齐口径）

同一台农机（`machine_id`）的时间区间，按下表判定；不同农机互不相干。

| 两班关系 | 判定 | 例子（已有 10:00–11:00） |
| --- | --- | --- |
| 真重叠（一端结束晚于另一端开始） | 拒绝 | 10:30–11:30、09:00–12:00、10:00–11:00 都拒绝 |
| 首尾相接（一端结束正好是另一端开始，间隔 0） | **允许** | 11:00–12:00、09:00–10:00 允许 |
| 间隔 0～18 分钟之间（不含 0、不含 18） | 拒绝 | 11:10 开始、11:17 开始都拒绝 |
| 间隔恰好 18 分钟及以上 | 允许 | 11:18 开始允许 |

- 缓冲时长 `schedule.buffer-minutes`（默认 **18** 分钟）。
- 待确认预约 `PENDING` 在确认前**临时占位 35 分钟**（`schedule.lock-ttl-minutes`），期间一样防撞；
  超过 35 分钟未确认，定时任务（默认每分钟）或下一次新建/确认时的惰性释放会把它置 `EXPIRED` 放掉，该时段可再约。
- 预约状态：`PENDING`（待确认）→ `CONFIRMED`（已确认，长期占位）；
  `PENDING/CONFIRMED → CANCELLED`（取消，立即释放、可再约）；`PENDING → EXPIRED`（超时自动释放）。
- 记录不物理删除，`CANCELLED/EXPIRED` 留痕但不再参与防撞。

---

## 二、上线三步（备份 → 变更 → 验证）

### 第一步：备份（不备份不变更）

```bash
scripts/backup-evops-data.sh pre-f7-schedule
```

检查点：

- [ ] 输出备份目录路径，`ls <备份目录>` 中 `.mv.db`、`schema.sql.bak`、`application.yml.bak`、`MANIFEST.sha256` 齐全
- [ ] `(cd <备份目录> && sha256sum -c MANIFEST.sha256)` 全部 OK
- [ ] 备份前应用已停（`curl -m 2 localhost:8080/api/health` 无响应）

### 第二步：变更

1. 部署新代码（含新增 `t_machine_schedule`、`t_machine_schedule_config` 的 `schema.sql` 与排班模块）。
2. 启动应用：`mvn spring-boot:run`。schema 对旧库幂等（`CREATE TABLE IF NOT EXISTS`），
   `t_evops_schema_version` 登记 `f7-machine-schedule`。
3. 默认即新逻辑：`schedule.engine-mode=NEW`（缓冲 18 分钟、临时锁 35 分钟、每分钟扫描）。

检查点：

- [ ] 启动日志无建表报错，`/api/health` 正常
- [ ] `GET /api/machine-schedules/config` 返回 `{"engineMode":"NEW"}`
- [ ] `mvn test` 全绿（含 F1–F6 既有用例与 F7 排班用例）

### 第三步：验证

1. 建一台农机，`POST /api/machine-schedules` 约 `10:00–11:00` → `status=PENDING`、`lockUntil`≈当前+35 分钟。
2. 再约 `10:30–11:30` → `success=false`，提示"时间重叠"。
3. 约 `11:00–12:00`（首尾相接）→ 成功；约 `11:10–12:00` → 拒绝（缓冲不足）；约 `11:18–12:00` → 成功。
4. `POST /{id}/confirm` → `CONFIRMED`、`lockUntil` 清空。
5. 对一条预约 `POST /{id}/cancel` 后，原时段可立即再约。
6. 把某条 PENDING 的 `lock_until` 改到过去（或等 35 分钟）→ `POST /api/machine-schedules/release-expired`
   返回 `released>=1`，该条置 `EXPIRED`，原时段可再约。

---

## 三、预案：一出异常，立即切回旧逻辑（最重要）

新逻辑（NEW）一旦现场不好用（误拦、口径争议、系统卡顿等），**不要硬扛**，一键切回旧手工台账口径：

```bash
curl -X POST http://localhost:8080/api/machine-schedules/config/engine-mode \
  -H 'Content-Type: application/json' \
  -d '{"engineMode":"LEGACY","requestNo":"REQ-SCH-FALLBACK-<日期>-<序号>"}'
```

- 切到 **LEGACY 后**：新建预约**不再做任何重叠/缓冲拦截**，登记即 `CONFIRMED`（`source=LEGACY`），
  与过去手工台账"只登记、不防撞"一致；现场恢复人工口头协调。
- 切换结果落 `t_machine_schedule_config`，**重启不丢**；查当前模式 `GET /api/machine-schedules/config`。
- 切回期间已有的 PENDING 仍会到期自动释放（不影响）；取消、查询、确认接口照常可用。
- 同一 `requestNo` 不可重复使用；模式切换有请求号台账留痕，谁在何时切的可追溯。

异常排除后切回新逻辑：

```bash
curl -X POST http://localhost:8080/api/machine-schedules/config/engine-mode \
  -H 'Content-Type: application/json' \
  -d '{"engineMode":"NEW","requestNo":"REQ-SCH-RESTORE-<日期>-<序号>"}'
```

> 注意：LEGACY 期间可能放进了时间重叠的预约。切回 NEW 不会回改旧数据，但下一次在冲突时段新建会被拦；
> 值班应先 `GET /api/machine-schedules?machineId=&date=` 人工梳理 LEGACY 期间的重叠，再恢复 NEW。

---

## 四、值班常见处置

| 情况 | 处置 |
| --- | --- |
| 机手误约/临时不来 | `POST /{id}/cancel`（填 `cancelReason`），立即释放，别人可马上约 |
| 预约一直没人确认 | 不用管，35 分钟后自动 `EXPIRED`；想立刻清理可 `POST /release-expired` |
| 预约恰好卡住想确认却已过期 | 该条已 `EXPIRED` 不能确认，按原时段重新约一条即可 |
| 想调缓冲/锁定时长 | 改 `application.yml` 的 `schedule.buffer-minutes` / `lock-ttl-minutes` 后重启（当前未开在线调整，仅模式可在线切） |
| 完全停掉定时扫描 | 置 `schedule.scheduling-enabled=false` 重启；新建/确认前的惰性释放仍兜底 |
| 多实例部署 | 当前防撞按 JVM 内单机分段锁；多实例需改数据库排他区间约束/JDBC 锁，勿直接多开 |

接口一览：`POST /api/machine-schedules`（新建）、`POST /{id}/confirm`、`POST /{id}/cancel`、
`POST /release-expired`、`GET /{id}`、`GET /api/machine-schedules?machineId=&date=&status=`、
`GET/POST /api/machine-schedules/config(/engine-mode)`。
