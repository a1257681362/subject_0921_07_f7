# 无状态登录（JWT 双令牌）操作指引（F6）

> 这页贴工位旁。机手不用培训：**先登录拿"通行证" → 每次操作带上通行证 → 通行证过期就用"续期条"换新 → 下班点登出。**

## 一、这两样是什么

| 令牌（就是一长串字符） | 通俗叫法 | 有效期 | 用途 |
| --- | --- | --- | --- |
| `accessToken`（访问令牌） | 通行证 | 默认 2 小时 | 每次操作系统接口都带上，**不能**用它换新 |
| `refreshToken`（刷新令牌） | 续期条 | 默认 7 天 | 只用来换新通行证，**不能**拿去办业务 |

规矩：

- **续期条一次性**：每换一次，旧续期条当场作废，系统发一对全新的通行证+续期条（这叫"刷新即轮换"）。换新后请用新返回的两个令牌，旧的删掉。
- **下班登出**：点登出后，通行证立即作废（进黑名单），即使没到 2 小时也不能再用。
- 令牌里**没有密码、没有手机号**，只有工号和角色，丢了不会暴露敏感信息。
- 黑名单和过期续期条系统**每 10 分钟自动清理**，不用人工处理。

## 二、第一步：登录（领通行证和续期条）

```bash
curl -X POST http://服务器:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bootstrap","password":"bootstrap123"}'
```

返回（重点抄下两个 token）：

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi....（通行证）",
    "refreshToken": "eyJhbGciOi....（续期条）",
    "tokenType": "Bearer",
    "accessExpiresAt": "2026-09-29 12:00:00",
    "refreshExpiresAt": "2026-10-06 10:00:00"
  }
}
```

> Linux 下可一键存进变量：`TOKEN=$(curl -s -X POST .../api/auth/login -H "Content-Type: application/json" -d '{"username":"bootstrap","password":"bootstrap123"}')`，再用 `echo $TOKEN` 查看。

## 三、第二步：办业务（通行证带着）

在请求头加 `Authorization: Bearer <通行证>`。以查农机列表为例：

```bash
curl http://服务器:8080/api/machines \
  -H "Authorization: Bearer eyJhbGciOi....（通行证）"
```

系统里作业单、农机、勾稽、结算、照片等所有业务接口都要带这个头。

## 四、第三步：通行证过期了？用续期条换新

当办业务返回 `访问令牌已过期`，不用重新输密码，拿**续期条**换新（会发一对全新的）：

```bash
curl -X POST http://服务器:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"eyJhbGciOi....（旧续期条）"}'
```

- 返回结构和登录一样，是**新的** accessToken + refreshToken。
- **旧续期条立即作废**，再拿它换第二次会报 `刷新令牌已使用，请重新登录`——这是正常的防盗用保护，不是故障。
- 续期条也过期了（超过 7 天）会报 `刷新令牌已过期，请重新登录`，回第二步重新登录即可。

## 五、第四步：下班登出

带上通行证，续期条也一起交回作废：

```bash
curl -X POST http://服务器:8080/api/auth/logout \
  -H "Authorization: Bearer eyJhbGciOi....（通行证）" \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"eyJhbGciOi....（续期条）"}'
```

返回 `{"success":true,...}` 即登出完成。之后该通行证再用会报 `登录状态已登出，请重新登录`。

## 六、常见提示对照（都不是系统故障）

| 看到的提示 | 含义 | 怎么办 |
| --- | --- | --- |
| `未携带登录令牌，请先登录` | 请求头没带通行证 | 加 `Authorization: Bearer <通行证>` |
| `访问令牌已过期…` | 通行证过了 2 小时 | 用第四步换新 |
| `登录令牌无效，请重新登录` | 令牌抄错/被改过 | 重新登录领取 |
| `刷新令牌已使用，请重新登录` | 续期条用过第二次 | 用最新一次换新返回的续期条；找不到就重新登录 |
| `刷新令牌已过期，请重新登录` | 超过 7 天没登录 | 重新登录 |
| `登录状态已登出，请重新登录` | 已登出，令牌作废 | 重新登录 |
| `用户名或密码不正确` | 账号或密码错 | 核对后重试 |

所有鉴权失败都是 **HTTP 401** + `{"success":false,"message":"…"}`，按 message 对照本表处理。

## 七、管理员注意（上线前必做）

1. **改默认密码/密钥**：生产启动必须设置环境变量，不要用默认值：
   - `AUTH_USERNAME`、`AUTH_PASSWORD`：登录账号（默认 bootstrap / bootstrap123）
   - `AUTH_JWT_SECRET`：签名密钥，**Base64 编码、解码后至少 32 字节**。生成示例：
     `python3 -c "import base64,os;print(base64.b64encode(os.urandom(32)).decode())"`
   - 更换密钥后所有旧令牌失效，全员需重新登录（属预期）。
2. 有效期可在 `application.yml` 调整：`auth.jwt.access-ttl-minutes`、`auth.jwt.refresh-ttl-hours`。
3. 健康检查 `GET /api/health` 不需要登录，供探活使用。
4. 上线步骤（备份→变更→验证）与回滚见下页"上线 Runbook"小节。
