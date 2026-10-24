# API

All `/api` responses use one of these JSON envelopes:

```json
{"success":true,"message":"success","data":{}}
```

```json
{"success":false,"code":"validation_failed","message":"Invalid request"}
```

Protected routes require `Authorization: Bearer <token>`. Registration and login return the token, its expiry time, and the signed-in user. Dates use ISO 8601.

## Accounts

### `POST /api/auth/register`

Create an account. Returns `201`.

```json
{"username":"alex","password":"a-strong-password"}
```

The username is 3–24 ASCII letters, numbers, or underscores and is stored in lowercase. Passwords must contain at least 8 characters and at most 72 UTF-8 bytes. Existing usernames return `409`.

The response data contains:

```json
{"token":"<jwt>","user":{"id":1,"username":"alex"},"expiresAt":"2026-10-24T18:00:00Z"}
```

### `POST /api/auth/login`

Accepts the same fields as registration. Returns `200` with the same data structure. Incorrect credentials return `401`.

### `GET /api/auth/me`

Returns the authenticated user as `{ "id": 1, "username": "alex" }`. Missing, invalid, or expired tokens return `401`.

## Rooms

### `GET /api/rooms`

Returns an array of public rooms. Requires authentication.

```json
[{"id":1,"name":"general","description":"A place for everyone.","createdAt":"2026-10-23T18:00:00Z"}]
```

### `POST /api/rooms`

Create a public room. Requires authentication. Returns `201` with the new room.

```json
{"name":"product-lounge","description":"Product ideas and design discussions."}
```

Names are 3–48 lowercase letters or digits, optionally separated by single hyphens. Descriptions are optional and limited to 160 characters. Duplicate names return `409`.

All signed-in users can list and create public rooms, read their history, and send messages.

## Messages

### `POST /api/rooms/{roomId}/messages`

Send a message to a public room. Requires authentication. Returns `201` with the saved message, including on a matching retry.

```json
{"clientMessageId":"9d19ff94-aefa-46da-aa93-5aa109fe9b9b","content":"Hello, everyone!"}
```

`clientMessageId` must be a UUID generated once per intended message. Keep it unchanged when retrying an uncertain request. Content is trimmed and must contain 1–2,000 characters, without NUL characters. The same user and client ID return the original message if room and content match; different content or a different room returns `409`.

```json
{"id":42,"roomId":1,"userId":1,"username":"alex","clientMessageId":"9d19ff94-aefa-46da-aa93-5aa109fe9b9b","content":"Hello, everyone!","createdAt":"2026-10-24T14:00:00Z"}
```

Messages are plain text. Clients must render content as text, rather than HTML. Missing rooms return `404`.

### `GET /api/rooms/{roomId}/messages`

Requires authentication. Response data is `{ "messages": [...], "hasMore": false }`. Every page is ordered by increasing message ID.

| Parameter | Meaning |
| --- | --- |
| `limit` | Page size, 1–100; defaults to 50. |
| `before` | Return the newest messages with IDs smaller than this positive ID. Use the first ID in the page to load older history. |
| `after` | Return the oldest messages with IDs greater than this nonnegative ID. Use `0` to start at the beginning, or the last ID in the page to continue catching up. |

Omit both cursors to get the latest page. `before` and `after` are mutually exclusive. While `hasMore` is true, repeat the request with the next cursor. Merge pages and live messages by server message ID. Message creation is serialized per room so cursor order follows committed writes within that room.

## WebSocket

### `POST /api/ws/tickets`

Requires the normal Bearer token. Returns a short-lived, single-use connection ticket in `data`:

```json
{"ticket":"<opaque-ticket>","expiresAt":"2026-10-24T14:00:30Z"}
```

Connect to `ws://localhost:8080/ws?ticket=<ticket>` (use `wss://` with HTTPS). The handshake requires an allowed `Origin` header. Missing, expired, or reused tickets are rejected. A ticket cannot outlive its JWT, and an open socket closes when that JWT expires. Obtain a fresh ticket for every connection attempt; never put the JWT in the WebSocket URL.

### Subscribe and receive

The client selects one public room per connection:

```json
{"type":"subscribe","roomId":1}
```

The server acknowledges the subscription:

```json
{"type":"subscribed","roomId":1}
```

Each live notification contains the saved message in the same shape as the HTTP response:

```json
{"type":"message","message":{"id":42,"roomId":1,"userId":1,"username":"alex","clientMessageId":"9d19ff94-aefa-46da-aa93-5aa109fe9b9b","content":"Hello, everyone!","createdAt":"2026-10-24T14:00:00Z"}}
```

A new subscription replaces the previous room. Send messages through the HTTP endpoint. `{ "type": "ping" }` receives `{ "type": "pong" }`. Protocol errors use `{ "type": "error", "code": "...", "message": "..." }`.

After the subscription acknowledgement, recover messages through the history endpoint and deduplicate by message ID. On reconnect, use a fresh ticket, subscribe again, then paginate `after` the last recovered ID. Redis Pub/Sub does not retain events; the stored history is the source of truth. The web interface also refreshes history periodically while visible.
