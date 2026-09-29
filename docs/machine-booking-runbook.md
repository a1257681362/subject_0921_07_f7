# 农机预约排班防撞 上线/值班 Runbook（F7）

把作业站手工排班台账收进系统：新增预约先防撞（重叠 + 18 分钟缓冲，首尾相接豁免），
确认前只临时锁定 35 分钟，过期/取消立即释放。上线按 **先备份 → 再变更 → 后验证**；
**预案先行：一出异常立即切回旧逻辑（LEGACY），排班业务不中断。**

---

## 第一步：备份（不备份不变更）

```bash
scripts/backup-evops-data.sh pre-f7-booking
```

检查点：

- [ ] 记录脚本输出的备份目录路径（形如 `backups/20260929-xxxxxx-pre-f7-booking/`）
- [ ] `<备份目录>` 内 `data/*.mv.db`、`schema.sql.bak`、`application.yml.bak`、`MANIFEST.sha256` 齐全
- [ ] `(cd <备份目录> && sha256sum -c MANIFEST.sha256)` 全部 OK（忽略 MANIFEST 自身一行）

> 备份前确认应用已停（`curl -m 2 localhost:8080/api/health` 无响应），运行中的 H2 文件库拷贝可能不一致。

## 第二步：变更

1. 部署新代码（含 `t_machine_appointment`、`t_machine_appointment_log`、`t_booking_config`
   三张新表的 `schema.sql` 与 booking 模块）。
2. 启动应用：`mvn spring-boot:run`。Spring Boot 自动执行 `schema.sql`，对旧库幂等
   （`CREATE TABLE IF NOT EXISTS`），并在 `t_evops_schema_version` 登记 `f7-machine-appointment`。
3. 默认配置（`application.yml` 的 `booking` 段，可用环境/配置中心覆盖）：
   - `mode: NEW` 新口径；`buffer-minutes: 18`；`lock-ttl-minutes: 35`
   - `scheduling-enabled: true`，`expire-scan-cron: "0 * * * * ?"`（每分钟扫描过期临时锁）

检查点：

- [ ] `curl -s localhost:8080/api/health` 正常
- [ ] `curl -s localhost:8080/api/appointments/config` 返回 `mode=NEW、bufferMinutes=18、lockTtlMinutes=35`
- [ ] H2 中能看到三张新表，`t_evops_schema_version` 含 `f7-machine-appointment`

## 第三步：验证（可重复执行）

用两张同机预约验证防撞与释放（时间换成未来时刻，ISO-8601）：

1. 预约 A `08:00~09:00` → 返回 `status=HELD`，带 `lockDeadline`（约 +35 分钟）。
2. 预约 B 与 A 真正相交（如 `08:30~09:30`）→ 拒绝，消息含"时段重叠"。
3. 预约 C 仅隔 10 分钟（`09:10~10:10`）→ 拒绝，消息含"缓冲"。
4. 预约 D 首尾相接（`09:00~10:00`）→ **成功**（端点相接不算冲突）。
5. 对 A `POST /{id}/confirm`（带返回的 `version`）→ `CONFIRMED`；`GET /{id}` 有两条状态日志。
6. 取消 D → `CANCELLED`，随后再约 D 同时段 → 成功（取消立即释放）。
7. 临时锁过期：新建一笔 HELD 后不确认，把数据库 `lock_deadline` 调到过去（或等 35 分钟），
   `POST /api/appointments/sweep-expired` 返回释放笔数，该单变 `EXPIRED`，同时段可再约。

---

## 值班处置

### 1. 异常：误拦、插不进单、口径有疑问 —— 立即切回旧逻辑

旧逻辑（手工台账基线）**只拦严格时段重叠，不做 18 分钟缓冲**。一个请求即时生效、落库重启不丢：

```bash
curl -s -X POST localhost:8080/api/appointments/config/mode \
  -H 'Content-Type: application/json' \
  -d '{"mode":"LEGACY","requestNo":"REQ-BOOKING-FALLBACK-20260929-01"}'
# 返回 {"success":true,"data":"LEGACY"}
curl -s localhost:8080/api/appointments/config   # 复核 mode=LEGACY
```

切回后：

- 相邻不足 18 分钟的预约不再被缓冲拦截（严格重叠仍拦，避免一车两单）。
- 已存在的 HELD/CONFIRMED 预约不受影响；35 分钟临时锁、取消释放等机制照常工作。
- 排障完成后，确认口径无误再切回：`{"mode":"NEW","requestNo":"..."}`。
- 也可先用 `PARALLEL` 观察：受理仍按新口径，旧口径本会放行的缓冲拦截会在服务日志记
  "并跑口径分歧"，供比对，不影响线上受理结果。

### 2. 临时调整缓冲 / 锁时长

```bash
curl -s -X POST localhost:8080/api/appointments/config/params \
  -H 'Content-Type: application/json' \
  -d '{"bufferMinutes":20,"lockTtlMinutes":45,"requestNo":"REQ-BOOKING-PARAMS-01"}'
```

只对**之后新提交**的预约生效（每单在 `buffer_minutes/lock_ttl_minutes` 列快照当时口径），
存量单不变。缓冲不建议设负；锁时长必须 > 0。

### 3. 手工补跑过期释放

正常每分钟自动扫描。若调度异常或刚做完时钟/数据调整，可手工触发：

```bash
curl -s -X POST localhost:8080/api/appointments/sweep-expired   # data=本轮释放笔数
```

逐笔独立事务、幂等；与"确认"并发时由版本乐观锁裁决，只有一方成功，不会误放已确认单。

### 4. 回滚代码

恢复备份前先停应用；代码回滚后，旧版本不识别三张新表但不影响既有 F1–F6 表，
`t_*` 新表可保留（无外键耦合）。重新部署修复版本后 schema 幂等、无需清表。

---

## 边界口径速查

| 情形 | 新口径 NEW | 旧口径 LEGACY |
| --- | --- | --- |
| 两时段真正相交 | 拦（时段重叠） | 拦（时段重叠） |
| 首尾相接（间隔 0） | 放行 | 放行 |
| 相邻 0 < 间隔 < 18 分钟 | 拦（缓冲不足） | 放行 |
| 相邻间隔 ≥ 18 分钟 | 放行 | 放行 |
| HELD 超 35 分钟未确认 | 自动 EXPIRED 释放 | 同（机制与口径无关） |
| 取消 | 立即 CANCELLED 释放可再约 | 同 |
