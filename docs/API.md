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

All signed-in users can list and create public rooms. Messages and real-time subscriptions are part of the next stage.
