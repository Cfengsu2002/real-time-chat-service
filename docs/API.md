# API 与 WebSocket 协议

基础地址为应用所在域名，例如 `http://localhost:8080`。请求和响应采用 UTF-8 JSON，时间采用 ISO 8601 UTC 格式。除注册、登录、静态网页和健康检查外，HTTP API 需要：

```http
Authorization: Bearer <JWT>
Content-Type: application/json
```

## 统一响应

成功响应的 `data` 由对应接口定义：

```json
{"success":true,"message":"success","data":{}}
```

失败响应使用 HTTP 状态码和业务错误码，不返回堆栈、密码或密钥：

```json
{"success":false,"code":"validation_failed","message":"Invalid request"}
```

最外层值为 `null` 的响应字段省略。Actuator 的 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness` 使用框架健康响应，例如 `{"status":"UP"}`，不属于业务 API envelope。

## 账号

### `POST /api/auth/register`

注册并签发 JWT，成功返回 `201`。用户名使用 3–24 个 ASCII 字母、数字或下划线，存储时统一为小写，不区分大小写；密码 8–72 个字符且 UTF-8 编码不超过 72 字节。

```json
{"username":"alice","password":"a-strong-demo-password"}
```

响应 `data`：

```json
{
  "token":"<JWT>",
  "user":{"id":1,"username":"alice"},
  "expiresAt":"2026-10-24T12:00:00Z"
}
```

### `POST /api/auth/login`

请求与注册相同，成功返回 `200`，`data` 与注册相同。默认 JWT 有效期为 24 小时；可通过 `JWT_TTL_SECONDS` 调整为 60–604800 秒。账号或密码错误返回 `401 invalid_credentials`。

### `GET /api/auth/me`

成功返回 `200`，`data` 为 `{"id":1,"username":"alice"}`。

### `POST /api/auth/ws-ticket`

使用有效 JWT 兑换一次性 WebSocket 连接票据，无请求体。成功返回 `200`：

```json
{"success":true,"message":"success","data":{"ticket":"<opaque-ticket>","expiresIn":30}}
```

票据有效期最长 30 秒，JWT 剩余有效期不足 30 秒时会相应缩短。票据只能消费一次，重连需重新签发。长期 JWT 不放入 WebSocket URL。生产须使用 HTTPS/WSS，并避免在代理日志中保留查询参数。

## 房间

### `GET /api/rooms`

返回所有公开房间，`data` 是数组：

```json
[
  {"id":1,"name":"general","description":"A place for everyone.","createdAt":"2026-10-23T12:00:00Z"}
]
```

### `POST /api/rooms`

创建公开房间，成功返回 `201`，`data` 为单个房间对象。名称 3–48 个字符，仅允许小写字母、数字和单个连接符，不能以连接符开始或结尾；名称唯一。描述可省略，最长 160 个字符。

```json
{"name":"product-chat","description":"讨论产品与设计"}
```

所有已登录账号均可列出房间、创建房间、读取与发送消息，MVP 没有私有房间或成员权限。

## 消息

消息对象字段：

```json
{
  "id":101,
  "roomId":1,
  "content":"大家好！",
  "sender":{"id":1,"username":"alice"},
  "createdAt":"2026-10-23T12:00:00Z",
  "clientMessageId":"38411d09-7340-4bd7-9686-03de8ebbcdd7"
}
```

### `GET /api/rooms/{roomId}/messages?limit=50&before=101`

`limit` 默认为 50，范围 1–100。`before` 可省略；设置时返回 ID 小于该值的较早消息。`data.items` 按消息 ID **升序**返回，便于按时间显示：

```json
{
  "items":["<message objects>"],
  "hasMore":true,
  "nextBefore":51
}
```

如果 `hasMore` 为 `true`，将 `nextBefore` 用作下一次请求的 `before`，继续加载更早历史；若没有更早数据，`nextBefore` 为 `null`。重新连接后应重新获取最近历史，并按消息 ID 去重。接口使用游标分页，未提供基于页码的 `OFFSET` 查询。

### `POST /api/rooms/{roomId}/messages`

消息经 HTTP 发送，WebSocket 用于接收事件与切换订阅。成功返回 `200`，`data` 为持久化后的消息对象：

```json
{"content":"大家好！","clientMessageId":"38411d09-7340-4bd7-9686-03de8ebbcdd7"}
```

`content` 必填且不能只有空白，最多 2000 个字符，存储前会去除两端空白。`clientMessageId` 必须为 UUID，每条新消息生成一个；网络失败重试时复用原 UUID。相同发送者、UUID、房间和正文的重试返回原消息，不重复存储；复用 UUID 发送不同内容或发到不同房间返回 `409 idempotency_conflict`。

数据库提交后才发布实时事件。Redis Pub/Sub 不提供离线积压，也没有事务 outbox；如果持久化成功而实时广播失败，应通过历史接口恢复消息，不能把实时连接作为可靠队列。

## WebSocket

连接地址：`ws://localhost:8080/ws?ticket=<ticket>`；HTTPS 页面使用 `wss://`。先通过 HTTP 获取一次性票据，每次重连兑换新票据。握手必须携带与目标服务同源的 `Origin`，例如 `Origin: http://localhost:8080`；缺少或跨源时返回 `403`。WebSocket 不支持在 URL 中直接传入 JWT。

连接后等待服务端 `READY`，再发送房间订阅 `JOIN`。服务端回复 `JOINED` 后开始接收该房间的 `MESSAGE`。每个连接当前订阅一个房间；发送新的 `JOIN` 切换房间。实时事件不替代历史接口，切换或重连后应补读历史并按消息 ID 去重。

具体消息格式如下，`MESSAGE.data` 与 HTTP 消息对象一致：

```json
{"type":"JOIN","roomId":1}
```

```json
{"type":"READY","userId":1}
```

```json
{"type":"JOINED","roomId":1}
```

```json
{"type":"MESSAGE","data":{"id":101,"roomId":1,"content":"大家好！","sender":{"id":1,"username":"alice"},"createdAt":"2026-10-23T12:00:00Z","clientMessageId":"38411d09-7340-4bd7-9686-03de8ebbcdd7"}}
```

```json
{"type":"ERROR","code":"validation_failed","message":"roomId must be a positive integer"}
```

WebSocket 错误还包括 `unsupported_event`、`room_not_found`、`invalid_json`、`service_unavailable`。JWT 到期时服务端以 `1008` 关闭连接，客户端应重新登录并取得新票据；无效或重复使用的票据在握手阶段返回 `401`。`JOIN.roomId` 必须为 JSON 正整数。

## 常见错误

| HTTP 状态 | 业务码示例 | 含义 |
| --- | --- | --- |
| `400` | `validation_failed`、`invalid_request`、`invalid_pagination` | 字段或分页参数错误 |
| `401` | `unauthorized`、`invalid_token`、`invalid_credentials` | 未登录、令牌过期或凭据错误 |
| `403` | `forbidden` | 请求被拒绝 |
| `404` | `room_not_found` | 房间不存在 |
| `409` | `username_taken`、`room_exists`、`idempotency_conflict`、`conflict` | 唯一约束或幂等冲突 |
| `503` | `service_unavailable` | 实时依赖暂不可用 |
| `500` | `internal_error` | 内部错误；响应不暴露实现细节 |
