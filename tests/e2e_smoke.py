"""使用真实 HTTP、WebSocket、PostgreSQL 和 Redis 验证跨实例聊天。"""

import asyncio
import json
import os
import secrets
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus


BASE_A = os.getenv("CHAT_BASE_A", "http://localhost:8080").rstrip("/")
BASE_B = os.getenv("CHAT_BASE_B", BASE_A).rstrip("/")


def request(base, path, method="GET", data=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(base + path, body, headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        with error:
            return error.code, json.load(error)


async def event(socket, expected_type):
    async with asyncio.timeout(10):
        while True:
            payload = json.loads(await socket.recv())
            if payload.get("type") == expected_type:
                return payload
            if payload.get("type") == "ERROR":
                raise AssertionError(payload)


def websocket_url(base, ticket):
    return base.replace("http://", "ws://", 1).replace("https://", "wss://", 1) + "/ws?ticket=" + urllib.parse.quote(ticket)


class ChatEndToEndTest(unittest.IsolatedAsyncioTestCase):
    async def test_real_chat_and_cross_instance_fanout(self):
        status, result = request(BASE_A, "/api/rooms")
        self.assertEqual(401, status)
        self.assertFalse(result["success"])

        suffix = uuid.uuid4().hex[:12]
        username = "smoke_" + suffix
        password = secrets.token_urlsafe(20)
        status, auth = request(BASE_A, "/api/auth/register", "POST", {"username": username, "password": password})
        self.assertIn(status, (200, 201))
        self.assertTrue(auth["success"])
        token = auth["data"]["token"]

        status, login = request(BASE_B, "/api/auth/login", "POST", {"username": username, "password": password})
        self.assertEqual(200, status)
        token_b = login["data"]["token"]
        status, me = request(BASE_B, "/api/auth/me", token=token_b)
        self.assertEqual(username, me["data"]["username"])
        status, _ = request(BASE_A, "/api/auth/login", "POST", {"username": username, "password": "wrong-password"})
        self.assertEqual(401, status)
        status, _ = request(BASE_A, "/api/auth/register", "POST", {"username": username, "password": password})
        self.assertEqual(409, status)

        status, room = request(BASE_A, "/api/rooms", "POST", {"name": "smoke-" + suffix, "description": "自动化双实例联调"}, token)
        self.assertIn(status, (200, 201))
        room_id = room["data"]["id"]

        def ticket(base, bearer):
            code, payload = request(base, "/api/auth/ws-ticket", "POST", {}, bearer)
            self.assertEqual(200, code)
            return payload["data"]["ticket"]

        ticket_a = ticket(BASE_A, token)
        ticket_b = ticket(BASE_A, token_b)
        async with connect(websocket_url(BASE_A, ticket_a), origin=BASE_A) as first:
            await event(first, "READY")
            async with connect(websocket_url(BASE_B, ticket_b), origin=BASE_B) as second:
                await event(second, "READY")
                for socket in (first, second):
                    await socket.send(json.dumps({"type": "JOIN", "roomId": room_id}))
                    await event(socket, "JOINED")
                with self.assertRaises(InvalidStatus):
                    async with connect(websocket_url(BASE_A, ticket_a), origin=BASE_A):
                        self.fail("已使用的票据不应被接受")

                message_ids = []
                for index in range(5):
                    client_id = str(uuid.uuid4())
                    content = f"跨实例消息 {index} <script>text only</script>"
                    code, sent = request(BASE_A, f"/api/rooms/{room_id}/messages", "POST", {"content": content, "clientMessageId": client_id}, token)
                    self.assertIn(code, (200, 201))
                    message = sent["data"]
                    message_ids.append(message["id"])
                    delivered_a, delivered_b = await asyncio.gather(event(first, "MESSAGE"), event(second, "MESSAGE"))
                    for delivered in (delivered_a, delivered_b):
                        self.assertEqual(message["id"], delivered["data"]["id"])
                        self.assertEqual(content, delivered["data"]["content"])
                    if index == 0:
                        code, retried = request(BASE_B, f"/api/rooms/{room_id}/messages", "POST", {"content": content, "clientMessageId": client_id}, token_b)
                        self.assertIn(code, (200, 201))
                        self.assertEqual(message["id"], retried["data"]["id"])

        status, newest = request(BASE_B, f"/api/rooms/{room_id}/messages?limit=2", token=token_b)
        self.assertEqual(message_ids[-2:], [message["id"] for message in newest["data"]["items"]])
        self.assertTrue(newest["data"]["hasMore"])
        before = newest["data"]["nextBefore"]
        status, older = request(BASE_B, f"/api/rooms/{room_id}/messages?limit=2&before={before}", token=token_b)
        self.assertEqual(message_ids[1:3], [message["id"] for message in older["data"]["items"]])
        status, all_messages = request(BASE_B, f"/api/rooms/{room_id}/messages", token=token_b)
        self.assertEqual(5, len(all_messages["data"]["items"]))
        status, _ = request(BASE_A, f"/api/rooms/{room_id}/messages", "POST", {"content": "x" * 2001, "clientMessageId": str(uuid.uuid4())}, token)
        self.assertEqual(400, status)
        with self.assertRaises(InvalidStatus):
            async with connect(websocket_url(BASE_A, ticket(BASE_A, token)), origin="https://untrusted.invalid"):
                self.fail("跨源握手不应被接受")


if __name__ == "__main__":
    unittest.main(verbosity=2)
