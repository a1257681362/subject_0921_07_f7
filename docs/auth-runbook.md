# 无状态登录（JWT）上线 Runbook（F6）

沿用合作社统一上线顺序：**先备份 → 再变更 → 后验证，任一步检查点不通过即停止并回滚。**

适用范围：F6 无状态会话改造（Shiro 过滤链接入 JWT 签发/校验、双令牌、刷新即轮换、登出黑名单、过期自动清理）。

---

## 第一步：备份（不备份不变更）

```bash
scripts/backup-evops-data.sh pre-f6-jwt-auth
```

检查点：

- [ ] 脚本最后一行输出备份目录路径，值班记录该路径
- [ ] 备份目录内 `.mv.db`、`schema.sql.bak`、`application.yml.bak`、`MANIFEST.sha256` 齐全
- [ ] `(cd <备份目录> && sha256sum -c MANIFEST.sha256)` 全部 OK

> 备份前确认应用已停止（`curl -m 2 localhost:8080/api/health` 无响应）。

## 第二步：变更

1. 部署新代码（含 jjwt 依赖、`com.evops.auth` 模块、更新后的 `ShiroConfig`、`schema.sql` 中
   `t_refresh_token`、`t_token_blacklist` 两张新表、`@EnableScheduling`）。
2. **生产必须设置环境变量**（不设则用开发默认值，不允许上线）：
   - `AUTH_JWT_SECRET`：Base64 签名密钥（≥32 字节明文）。生成：
     `python3 -c "import base64,os;print(base64.b64encode(os.urandom(32)).decode())"`
   - `AUTH_USERNAME`、`AUTH_PASSWORD`：登录账号密码（默认 bootstrap / bootstrap123）
3. 启动应用：`mvn spring-boot:run`。`schema.sql` 对旧库幂等：两张表不存在才建，
   `t_evops_schema_version` 登记 `f6-stateless-jwt-auth`。

检查点：

- [ ] 启动日志无 `SQLException`、无建表报错，无 `auth.jwt.secret` 相关启动失败
- [ ] `curl localhost:8080/api/health` 无需令牌返回 `{"success":true,...}`
- [ ] 不带令牌访问业务接口（如 `curl localhost:8080/api/machines`）返回 **HTTP 401**
      且 `success=false`、`message=未携带登录令牌，请先登录`
- [ ] 既有功能回归：`mvn test` 全绿（既有 F1–F5 用例在 test profile 下鉴权关闭，不受影响；
      新增认证全链路用例 `AuthFlowTest` 通过）

## 第三步：验证（全过才算完成）

设 `BASE=http://localhost:8080`，按序执行：

1. **登录领双令牌**：`POST /api/auth/login`（正确账号密码）→ `success=true`，
   data 同时含 `accessToken`、`refreshToken`、两个 `expiresAt`；令牌解码后
   payload 只有 sub/jti/typ/roles/iat/exp，**无密码等敏感明文**。
2. **凭证访问**：带 `Authorization: Bearer <accessToken>` `GET /api/machines` → 200 正常业务返回。
3. **无令牌拒绝**：去掉请求头 → **401** `未携带登录令牌`。
4. **刷新令牌不能办业务**：拿 `refreshToken` 当 Bearer 访问 `/api/machines` → **401**
   `令牌类型错误…`。
5. **刷新即轮换（一次性）**：`POST /api/auth/refresh` 带旧 refreshToken → 返回**全新一对**；
   立刻用**旧** refreshToken 再刷一次 → `success=false`，message 含 `刷新令牌已使用`。
6. **登出黑名单**：`POST /api/auth/logout`（带 accessToken，body 带 refreshToken）→ success；
   再用同一 accessToken 访问业务 → **401** `登录状态已登出`；
   用登出时的 refreshToken 换新 → `刷新令牌无效/已…`，拿不到新令牌。
7. **过期自动清理**：黑名单与刷新台账中 `expires_at < now()` 的登记由定时任务
   （默认每 10 分钟，`auth.jwt.blacklist-cleanup-cron`）自动删除；日志可见
   `黑名单过期登记自动清理：N 条` / `过期刷新令牌登记自动清理：N 条`（有过期数据时）。
8. **错密码拒绝**：错误密码登录 → `success=false`，message 含 `用户名或密码不正确`。

## 回滚

1. 停止应用，用备份恢复 `data/*.mv.db`、`schema.sql`、`application.yml`。
2. 回退代码到 F5 版本并启动；旧版本不识别两张新表（保留无害），过滤链恢复全放行口径，
   按回退后版本的安全要求另行评估。
3. 如仅需临时放开（仅限内网应急）：可在配置设 `auth.enabled=false` 重启，业务接口全部放行；
   **恢复后必须立即置回 true**，该开关不得作为长期状态。

## 关键配置

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `AUTH_JWT_SECRET` | 仅开发默认值 | Base64、解码 ≥32 字节；轮换即全员重新登录 |
| `AUTH_USERNAME` / `AUTH_PASSWORD` | bootstrap / bootstrap123 | 起步内置账号 |
| `auth.jwt.access-ttl-minutes` | 120 | 访问令牌有效期（分钟） |
| `auth.jwt.refresh-ttl-hours` | 168 | 刷新令牌有效期（小时，7 天） |
| `auth.jwt.blacklist-cleanup-cron` | `0 */10 * * * ?` | 黑名单/过期刷新令牌清理频率 |
| `auth.enabled` | true | 鉴权总开关，仅测试/应急使用 |

新增表：`t_refresh_token`、`t_token_blacklist`。机手侧操作见
[auth-guide.md](auth-guide.md)（贴工位）。
