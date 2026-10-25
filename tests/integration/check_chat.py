#!/usr/bin/env python3
"""验证共享 PostgreSQL 与 Redis 的双实例 HTTP 和原生 WebSocket 行为。"""

import argparse
import concurrent.futures
import json
import os
import secrets
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from urllib.parse import urlencode, urlsplit, urlunsplit

import requests
from websockets.exceptions import InvalidStatus
from websockets.sync.client import connect


class CheckFailure(Exception):
    """仅携带不含凭据和票据地址的安全错误消息。"""


@dataclass(frozen=True)
class Account:
    username: str
    token: str = field(repr=False)


def require(condition, message):
    if not condition:
        raise CheckFailure(message)


def base_url(value):
    parsed = urlsplit(value)
    require(parsed.scheme in {"http", "https"} and parsed.hostname is not None,
            "Instance URLs must use HTTP or HTTPS")
    require(parsed.username is None and parsed.password is None and not parsed.query and not parsed.fragment,
            "Instance URLs must not contain credentials, queries, or fragments")
    require(parsed.path in {"", "/"}, "Instance URLs must point to the site root")
    return value.rstrip("/")


def http(base, path, *, method="GET", account=None, body=None, status=200):
    headers = {"Accept": "application/json"}
    if account is not None:
        headers["Authorization"] = "Bearer " + account.token
    response = requests.request(method, base + path, json=body, headers=headers, timeout=10)
    require(response.status_code == status,
            f"{method} {path.split('?')[0]} returned {response.status_code}; expected {status}")
    payload = response.json()
    require(payload.get("success") is (status < 400), "API response envelope has an incorrect success flag")
    if status >= 400:
        require("code" in payload and isinstance(payload.get("message"), str), "API error envelope is incomplete")
    return payload.get("data")


def wait_ready(bases, timeout):
    deadline = time.monotonic() + timeout
    pending = set(bases)
    while pending and time.monotonic() < deadline:
        for base in list(pending):
            try:
                response = requests.get(base + "/actuator/health/readiness", timeout=2)
                if response.status_code == 200 and response.json().get("status") == "UP":
                    pending.remove(base)
            except (requests.RequestException, ValueError):
                pass
        if pending:
            time.sleep(0.4)
    require(not pending, "Both instances must become ready before integration checks")
    for base in bases:
        response = requests.get(base + "/actuator/health/liveness", timeout=5)
        require(response.status_code == 200 and response.json().get("status") == "UP", "Liveness probe failed")


def create_account(base, name):
    password = secrets.token_urlsafe(24)
    registered = http(base, "/api/auth/register", method="POST",
                      body={"username": name, "password": password}, status=201)
    logged_in = http(base, "/api/auth/login", method="POST", body={"username": name, "password": password})
    require(registered["user"] == logged_in["user"], "Login returned a different account")
    require(isinstance(logged_in.get("token"), str) and logged_in["token"], "Login did not return a token")
    return Account(name, logged_in["token"])


def create_room(base, account, name):
    return http(base, "/api/rooms", account=account, method="POST",
                body={"name": name, "description": "Integration test room"}, status=201)


def message_path(room):
    return f"/api/rooms/{room['id']}/messages"


def send(base, account, room, content, client_id=None):
    return http(base, message_path(room), method="POST", account=account,
                body={"clientMessageId": client_id or str(uuid.uuid4()), "content": content}, status=201)


def ticket(base, account):
    headers = {"Authorization": "Bearer " + account.token}
    response = requests.post(base + "/api/ws/tickets", headers=headers, timeout=10)
    require(response.status_code == 200, "Authenticated connection ticket request failed")
    require("no-store" in response.headers.get("Cache-Control", ""), "Ticket response must disable caching")
    payload = response.json()
    require(payload.get("success") is True, "Ticket response envelope is invalid")
    data = payload["data"]
    require(isinstance(data.get("ticket"), str) and len(data["ticket"]) == 43, "Ticket shape is invalid")
    return data


def socket_url(base, opaque):
    parsed = urlsplit(base)
    return urlunsplit(("wss" if parsed.scheme == "https" else "ws", parsed.netloc, "/ws",
                       urlencode({"ticket": opaque}), ""))


def open_socket(base, opaque, origin):
    return connect(socket_url(base, opaque), origin=origin, open_timeout=5,
                   close_timeout=2, ping_interval=None, proxy=None)


def reject_socket(base, opaque, origin, status):
    try:
        with open_socket(base, opaque, origin):
            raise CheckFailure("An unauthorized WebSocket handshake was accepted")
    except InvalidStatus as error:
        require(error.response.status_code == status,
                f"Rejected WebSocket handshake returned {error.response.status_code}; expected {status}")


def receive(socket, expected_type):
    value = json.loads(socket.recv(timeout=5))
    require(isinstance(value, dict) and value.get("type") == expected_type,
            "WebSocket returned an unexpected frame type")
    return value


def subscribe(socket, room):
    socket.send(json.dumps({"type": "subscribe", "roomId": room["id"]}))
    acknowledgement = receive(socket, "subscribed")
    require(acknowledgement.get("roomId") == room["id"], "Subscription acknowledged the wrong room")


def no_frame(socket):
    try:
        socket.recv(timeout=0.4)
    except TimeoutError:
        return
    raise CheckFailure("WebSocket received an unexpected duplicate or cross-room frame")


def page(base, account, room, **parameters):
    path = message_path(room)
    if parameters:
        path += "?" + urlencode(parameters)
    result = http(base, path, account=account)
    require(isinstance(result.get("messages"), list) and isinstance(result.get("hasMore"), bool),
            "History response shape is invalid")
    ids = [message["id"] for message in result["messages"]]
    require(ids == sorted(set(ids)), "History page IDs must be unique and ascending")
    return result


def collect_after(base, account, room, cursor=0, limit=3):
    found = []
    for _ in range(200):
        result = page(base, account, room, after=cursor, limit=limit)
        batch = result["messages"]
        require(all(message["id"] > cursor for message in batch), "After cursor did not advance")
        found.extend(batch)
        if not result["hasMore"]:
            return found
        require(bool(batch), "History pagination made no progress")
        cursor = batch[-1]["id"]
    raise CheckFailure("History pagination exceeded the bounded test limit")


def passed(message):
    print("PASS: " + message, flush=True)


def run(first, second, wait_timeout):
    started = time.monotonic()
    wait_ready((first, second), wait_timeout)
    for base in (first, second):
        http(base, "/api/rooms", status=401)
        http(base, "/api/ws/tickets", method="POST", status=401)
    suffix = uuid.uuid4().hex[:10]
    sender = create_account(first, "sender_" + suffix)
    reader = create_account(second, "reader_" + suffix)
    require(http(second, "/api/auth/me", account=sender)["username"] == sender.username,
            "JWT issued on one instance did not authenticate on the other")
    passed("two healthy instances, protected routes, registration, login, and shared JWT authentication")

    room = create_room(first, sender, "integration-" + suffix)
    other_room = create_room(second, reader, "isolation-" + suffix)
    require(any(item["id"] == room["id"] for item in http(second, "/api/rooms", account=reader)),
            "A room created on the first instance is missing from the second")
    require(page(second, reader, room)["messages"] == [], "A new room must have empty history")
    passed("PostgreSQL-backed room visibility across instances")

    expiring = ticket(first, sender)
    one_use = ticket(first, sender)["ticket"]
    reject_socket(second, one_use, "https://untrusted.example", 403)
    reject_socket(second, one_use, None, 403)
    with open_socket(second, one_use, first) as socket:
        reject_socket(first, one_use, first, 401)
        socket.send(json.dumps({"type": "ping"}))
        receive(socket, "pong")
    reject_socket(second, "invalid", first, 401)
    passed("cross-instance tickets, Origin rejection before consumption, replay rejection, and ping/pong")

    concurrent_ticket = ticket(first, sender)["ticket"]
    barrier = threading.Barrier(2)

    def consume(base):
        barrier.wait(timeout=5)
        try:
            with open_socket(base, concurrent_ticket, first):
                return 101
        except InvalidStatus as error:
            return error.response.status_code

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(consume, (first, second)))
    require(sorted(outcomes) == [101, 401], "A ticket must be consumed atomically across application instances")
    passed("simultaneous ticket use across both instances succeeds exactly once")

    with open_socket(second, ticket(first, reader)["ticket"], first) as socket:
        subscribe(socket, room)
        client_id = str(uuid.uuid4())
        original = send(first, sender, room, "  Hello across instances 中文 👋  ", client_id)
        delivered = receive(socket, "message")["message"]
        require(delivered == original and original["content"] == "Hello across instances 中文 👋",
                "Redis-delivered message differs from the committed HTTP response")
        retried = send(second, sender, room, original["content"], client_id)
        require(retried == original, "A cross-instance retry must return the exact saved response")
        no_frame(socket)
        require(len(page(second, reader, room)["messages"]) == 1, "A retried send created a duplicate row")
        http(second, message_path(room), method="POST", account=sender,
             body={"clientMessageId": client_id, "content": "different"}, status=409)
        http(first, message_path(other_room), method="POST", account=sender,
             body={"clientMessageId": client_id, "content": original["content"]}, status=409)
        passed("Redis cross-instance delivery and PostgreSQL idempotent retries with stable timestamps")
        subscribe(socket, other_room)
        send(first, sender, room, "This message belongs to the previous room")
        no_frame(socket)
        isolated = send(first, sender, other_room, "Only the current subscription receives this")
        require(receive(socket, "message")["message"]["id"] == isolated["id"],
                "Current room did not receive its own message")
        socket.send(json.dumps({"type": "subscribe", "roomId": 9223372036854775807}))
        require(receive(socket, "error").get("code") == "room_not_found", "Missing WebSocket room was not rejected")
        socket.send("not JSON")
        require(receive(socket, "error").get("code") == "invalid_frame", "Invalid WebSocket JSON was not rejected")
    passed("room subscription replacement, delivery isolation, and protocol validation")

    history_room = create_room(first, sender, "history-" + suffix)
    expected = [send(first if index % 2 == 0 else second, sender, history_room, f"History entry {index}")
                for index in range(11)]
    latest = page(second, reader, history_room, limit=4)
    require(latest["messages"] == expected[-4:] and latest["hasMore"], "Latest history page is incorrect")
    older = page(first, reader, history_room, before=expected[-4]["id"], limit=4)
    require(older["messages"] == expected[-8:-4] and older["hasMore"], "Older history page is incorrect")
    oldest = page(second, reader, history_room, before=expected[-8]["id"], limit=4)
    require(oldest["messages"] == expected[:3] and not oldest["hasMore"], "Oldest history page is incorrect")
    require(collect_after(second, reader, history_room) == expected, "Multi-page history recovery lost or duplicated messages")
    require(page(first, reader, history_room, after=expected[-1]["id"])["messages"] == [],
            "After cursor past the newest message must be empty")
    passed("latest/before/after pagination, committed ordering, and multi-page history recovery")

    for content in ("", "  \n\t ", "x" * 2001, "hello\u0000world"):
        http(first, message_path(room), method="POST", account=sender,
             body={"clientMessageId": str(uuid.uuid4()), "content": content}, status=400)
    for body in ({"content": "missing ID"}, {"clientMessageId": "bad", "content": "hello"},
                 {"clientMessageId": str(uuid.uuid4()), "content": "hello", "userId": 999}):
        http(second, message_path(room), method="POST", account=sender, body=body, status=400)
    for query in ("?after=-1", "?before=0", "?before=1&after=0", "?limit=0", "?limit=101", "?after=no"):
        http(second, message_path(room) + query, account=reader, status=400)
    missing = {"id": 9223372036854775807}
    http(first, message_path(missing), account=reader, status=404)
    http(second, message_path(missing), method="POST", account=sender,
         body={"clientMessageId": str(uuid.uuid4()), "content": "missing room"}, status=404)
    passed("message and cursor validation, standard errors, and missing-room rejection")

    retry_room = create_room(second, sender, "retry-" + suffix)
    shared_id = str(uuid.uuid4())
    start = threading.Barrier(4)

    def concurrent_retry(index):
        start.wait(timeout=5)
        return send(first if index % 2 == 0 else second, sender, retry_room, "One intended message", shared_id)

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(concurrent_retry, range(4)))
    require(all(item == results[0] for item in results), "Concurrent retries returned different messages")
    require(page(first, reader, retry_room)["messages"] == [results[0]], "Concurrent retries created duplicate database rows")
    passed("concurrent cross-instance retries create one PostgreSQL message")

    ordered_room = create_room(first, sender, "ordering-" + suffix)
    recovered = []
    cursor = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        writes = [pool.submit(send, first if index % 2 == 0 else second, sender, ordered_room,
                              f"Concurrent stored message {index}") for index in range(16)]
        while not all(item.done() for item in writes):
            batch = page(second, reader, ordered_room, after=cursor, limit=3)["messages"]
            recovered.extend(batch)
            if batch:
                cursor = batch[-1]["id"]
            time.sleep(0.01)
        expected_concurrent = sorted((item.result() for item in writes), key=lambda item: item["id"])
    recovered.extend(collect_after(first, reader, ordered_room, cursor=cursor, limit=3))
    require(recovered == expected_concurrent, "A recovery cursor skipped or duplicated a concurrently committed message")
    passed("history recovery remains complete while both instances commit messages concurrently")

    expires_at = datetime.fromisoformat(expiring["expiresAt"].replace("Z", "+00:00"))
    remaining = (expires_at - datetime.now(timezone.utc)).total_seconds() + 0.25
    if remaining > 0:
        require(remaining <= 31, "Connection ticket expiry exceeds the expected bounded lifetime")
        time.sleep(remaining)
    reject_socket(second, expiring["ticket"], first, 401)
    passed("an unused expired Redis ticket cannot establish a connection")
    print(f"PASS: all 11 integration groups completed against two application instances in {time.monotonic() - started:.1f}s", flush=True)


def main():
    parser = argparse.ArgumentParser(description="Check HTTP and native WebSocket behavior across two instances.")
    parser.add_argument("--wait-timeout", type=float, default=90, help="Seconds to wait for both readiness probes")
    options = parser.parse_args()
    try:
        require(0 < options.wait_timeout <= 300, "Wait timeout must be between 0 and 300 seconds")
        first = base_url(os.getenv("RELAY_BASE_URL", "http://localhost:18080"))
        second = base_url(os.getenv("RELAY_SECOND_URL", "http://localhost:18081"))
        require(first != second, "Integration checks require two distinct application URLs")
        run(first, second, options.wait_timeout)
    except CheckFailure as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1
    except Exception as error:
        # 不输出异常正文或堆栈，避免网络库回显票据 URL、凭据或请求上下文。
        print(f"FAIL: integration checks stopped ({type(error).__name__}); inspect service health and configuration", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
