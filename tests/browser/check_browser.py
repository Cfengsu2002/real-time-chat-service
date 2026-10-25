#!/usr/bin/env python3
"""验证两个运行中的聊天服务实例及其网页界面。"""

import argparse
import json
import os
from pathlib import Path
import re
import secrets
import sys
from urllib.parse import urlsplit
import uuid

from playwright.sync_api import expect, sync_playwright


def options():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--headed", action="store_true", help="Show Chromium while checking the interface")
    parser.add_argument("--timeout", type=int, default=20000, help="UI assertion timeout in milliseconds")
    return parser.parse_args()


def base_url(name, default):
    value = os.environ.get(name, default).rstrip("/")
    parsed = urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.path or parsed.query
            or parsed.fragment or parsed.username or parsed.password):
        raise ValueError("Application URLs must be HTTP(S) origins")
    return value


def session_headers(page):
    token = page.evaluate('JSON.parse(sessionStorage.getItem("relay-room-session")).token')
    return {"Authorization": "Bearer " + token}


def api_data(response, expected_status):
    if response.status != expected_status:
        raise AssertionError(f"HTTP request returned {response.status}; expected {expected_status}")
    payload = response.json()
    if payload.get("success") is not True:
        raise AssertionError("HTTP response did not use the success envelope")
    return payload.get("data")


def send_http(context, base, headers, room_id, content):
    return api_data(context.request.post(
        base + f"/api/rooms/{room_id}/messages", headers=headers,
        data={"clientMessageId": str(uuid.uuid4()), "content": content}, timeout=10000,
    ), 201)


def all_messages(context, base, headers, room_id):
    result, cursor = [], 0
    for _ in range(100):
        data = api_data(context.request.get(
            base + f"/api/rooms/{room_id}/messages?after={cursor}&limit=100", headers=headers, timeout=10000,
        ), 200)
        result.extend(data["messages"])
        if not data["hasMore"]:
            return result
        next_cursor = data["messages"][-1]["id"]
        if next_cursor <= cursor:
            raise AssertionError("History cursor did not advance")
        cursor = next_cursor
    raise AssertionError("History exceeded the acceptance-check page limit")


def attach_observers(page, errors, frames):
    page.on("pageerror", lambda error: errors.append(type(error).__name__))

    def on_socket(socket):
        def on_frame(frame):
            if isinstance(frame, str):
                try:
                    frames.append(json.loads(frame))
                except json.JSONDecodeError:
                    pass
        socket.on("framereceived", on_frame)

    page.on("websocket", on_socket)


def register(page, base, username, password):
    page.goto(base, wait_until="domcontentloaded")
    page.locator("#register-tab").click()
    page.locator("#username").fill(username)
    page.locator("#password").fill(password)
    page.locator("#auth-submit").click()
    expect(page.locator("#workspace-view")).to_be_visible()
    expect(page.locator("#connection-status")).to_have_attribute("data-state", "live")


def select_room(page, room_name):
    page.locator("#refresh-rooms").click()
    page.locator(".room-button").filter(has_text=room_name).click()
    expect(page.locator("#room-title")).to_have_text(room_name)


def saved_messages(page):
    return page.locator("li[data-message-id]")


def assert_unique_ids(page):
    ids = saved_messages(page).evaluate_all("nodes => nodes.map(node => node.dataset.messageId)")
    if len(ids) != len(set(ids)):
        raise AssertionError("The conversation contains duplicate server message IDs")
    if ids != sorted(ids, key=int):
        raise AssertionError("The conversation is not ordered by server message ID")


def run(args):
    first = base_url("RELAY_BASE_URL", "http://localhost:18080")
    second = base_url("RELAY_SECOND_URL", "http://localhost:18081")
    if first == second:
        raise ValueError("Two distinct application origins are required")
    artifacts = Path(os.environ.get("ARTIFACT_DIR", "artifacts/browser")).resolve()
    artifacts.mkdir(parents=True, exist_ok=True)
    suffix = uuid.uuid4().hex[:10]
    sender, reader = "sender_" + suffix, "reader_" + suffix
    room_name = "browser-" + suffix
    password = secrets.token_urlsafe(20)
    errors, received, second_received = [], [], []
    stage, pages = "browser startup", []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=not args.headed)
        try:
            first_context = browser.new_context(viewport={"width": 1440, "height": 960}, reduced_motion="reduce")
            second_context = browser.new_context(viewport={"width": 1440, "height": 960}, reduced_motion="reduce")
            a, b = first_context.new_page(), second_context.new_page()
            pages.extend([a, b])
            for page, frames in ((a, received), (b, second_received)):
                page.set_default_timeout(args.timeout)
                attach_observers(page, errors, frames)
            expect.set_options(timeout=args.timeout)
            for context, base in ((first_context, first), (second_context, second)):
                if context.request.get(base + "/actuator/health/readiness", timeout=10000).status != 200:
                    raise AssertionError("An application instance is not ready")
                if context.request.get(base + "/api/rooms", timeout=10000).status != 401:
                    raise AssertionError("Room API allowed an unauthenticated request")

            stage = "account registration and room creation"
            register(a, first, sender, password)
            register(b, second, reader, password)
            a.locator("#new-room").click()
            a.locator("#room-name").fill(room_name)
            a.locator("#room-description-input").fill("A shared place for good conversations.")
            a.locator("#create-room-submit").click()
            expect(a.locator("#room-title")).to_have_text(room_name)
            select_room(b, room_name)
            headers = session_headers(a)
            rooms = api_data(first_context.request.get(first + "/api/rooms", headers=headers), 200)
            room_id = next(room["id"] for room in rooms if room["name"] == room_name)
            print("PASS: accounts and a shared public room across two instances", flush=True)

            stage = "cross-instance live delivery and literal-text rendering"
            content = "Hello from another instance 👋 <img src=x onerror=window.pwned=1>"
            a.locator("#message-input").fill(content)
            a.locator("#send-message").click()
            expect(b.locator(".message-content").filter(has_text=content)).to_have_count(1)
            if b.locator("#message-list img").count() or b.evaluate("window.pwned") is not None:
                raise AssertionError("Message markup was rendered as HTML")
            if not any(frame.get("type") == "message" and frame.get("message", {}).get("content") == content
                       for frame in second_received):
                raise AssertionError("No actual WebSocket message frame was observed")
            print("PASS: live WebSocket delivery and safe plain-text messages", flush=True)

            stage = "stored history pagination and session restoration"
            for index in range(55):
                send_http(first_context, first, headers, room_id, f"History message {index:02d}")
            b.reload(wait_until="domcontentloaded")
            expect(b.locator("#workspace-view")).to_be_visible()
            select_room(b, room_name)
            expect(saved_messages(b)).to_have_count(50)
            expect(b.locator("#load-older")).to_be_visible()
            b.locator("#load-older").click()
            expect(saved_messages(b)).to_have_count(56)
            expect(b.locator("#load-older")).to_be_hidden()
            assert_unique_ids(b)
            print("PASS: refresh restores the account and older stored messages remain accessible", flush=True)

            stage = "offline reconnect and multi-page history recovery"
            second_context.set_offline(True)
            expect(b.locator("#connection-status")).to_have_attribute("data-state", "offline")
            for index in range(105):
                send_http(first_context, first, headers, room_id, f"While disconnected {index:03d}")
            second_context.set_offline(False)
            expect(b.locator("#connection-status")).to_have_attribute("data-state", "live")
            expect(b.locator(".message-content").filter(has_text="While disconnected 104")).to_have_count(1)
            expect(saved_messages(b)).to_have_count(161)
            assert_unique_ids(b)
            print("PASS: a new connection recovers 105 missed messages over multiple pages without duplicates", flush=True)

            stage = "lost-response retry with the same client message ID"
            retry_context = browser.new_context(viewport={"width": 1200, "height": 900}, reduced_motion="reduce")
            c = retry_context.new_page()
            c.set_default_timeout(args.timeout)
            pages.append(c)
            attach_observers(c, errors, received)

            def omit_live_message_echo(socket):
                # 连接真实服务器，仅丢弃消息通知，确保丢失 HTTP 响应后显示重试状态。
                server = socket.connect_to_server()

                def forward_control(frame):
                    try:
                        payload = json.loads(frame) if isinstance(frame, str) else {}
                    except json.JSONDecodeError:
                        payload = {}
                    if payload.get("type") != "message":
                        socket.send(frame)

                server.on_message(forward_control)

            c.route_web_socket(lambda url: urlsplit(url).path == "/ws", omit_live_message_echo)
            c.goto(second, wait_until="domcontentloaded")
            session = api_data(retry_context.request.post(
                second + "/api/auth/login", data={"username": sender, "password": password}, timeout=10000,
            ), 200)
            c.evaluate("session => sessionStorage.setItem('relay-room-session', JSON.stringify(session))", session)
            c.reload(wait_until="domcontentloaded")
            expect(c.locator("#workspace-view")).to_be_visible()
            select_room(c, room_name)
            expect(saved_messages(c)).to_have_count(50)
            expect(c.locator("#history-state")).not_to_contain_text(re.compile("Loading|Checking"))
            attempts, responses = [], []

            def interrupt_once(route):
                if route.request.method != "POST":
                    route.continue_()
                    return
                attempts.append(route.request.post_data_json)
                response = route.fetch()
                responses.append(api_data(response, 201))
                if len(attempts) == 1:
                    # 请求已在数据库提交，再模拟浏览器没有收到响应。
                    route.abort("failed")
                else:
                    route.fulfill(response=response)

            c.route("**/api/rooms/*/messages", interrupt_once)
            c.locator("#message-input").fill("An interrupted send should keep one message.")
            c.locator("#send-message").click()
            expect(c.locator(".retry-message")).to_be_visible()
            c.locator(".retry-message").click()
            expect(c.locator(".retry-message")).to_be_hidden()
            expect(c.locator("#message-input")).to_have_value("")
            if len(attempts) != 2 or attempts[0] != attempts[1] or responses[0] != responses[1]:
                raise AssertionError("Retry did not preserve the client ID, content, and saved response")
            stored = all_messages(first_context, first, headers, room_id)
            if sum(message["clientMessageId"] == attempts[0]["clientMessageId"] for message in stored) != 1:
                raise AssertionError("An uncertain retry created more than one stored message")
            print("PASS: an accepted send with a lost response retries the same UUID and stores one row", flush=True)

            stage = "mobile navigation, sending, and sign-out/sign-in"
            b.locator("#chat-history").evaluate("element => element.scrollTop = element.scrollHeight")
            b.screenshot(path=str(artifacts / "desktop.png"), full_page=True)
            b.set_viewport_size({"width": 390, "height": 844})
            if b.evaluate("document.documentElement.scrollWidth > innerWidth"):
                raise AssertionError("The mobile layout overflows horizontally")
            b.locator("#message-input").fill("A mobile reply")
            b.locator("#send-message").click()
            expect(a.locator(".message-content").filter(has_text="A mobile reply")).to_have_count(1)
            expect(b.locator("#message-input")).to_have_value("")
            b.screenshot(path=str(artifacts / "mobile.png"), full_page=True)
            b.locator("#open-sidebar").click()
            expect(b.locator("#sidebar")).to_have_class("sidebar open")
            b.keyboard.press("Escape")
            expect(b.locator("#sidebar")).to_have_class("sidebar")
            b.locator("#open-sidebar").click()
            b.locator("#logout").click()
            expect(b.locator("#welcome-view")).to_be_visible()
            if b.evaluate("sessionStorage.getItem('relay-room-session')") is not None:
                raise AssertionError("Sign-out left the account in session storage")
            b.locator("#username").fill(reader)
            b.locator("#password").fill(password)
            b.locator("#auth-submit").click()
            expect(b.locator("#workspace-view")).to_be_visible()
            expect(b.locator("#connection-status")).to_have_attribute("data-state", "live")
            if errors:
                raise AssertionError("The browser reported a JavaScript page error")
            print("PASS: mobile layout, message sending, navigation, sign-out/sign-in; no JavaScript errors", flush=True)
            print("PASS: browser acceptance checks complete", flush=True)
        except Exception as error:
            for number, page in enumerate(pages):
                try:
                    page.screenshot(path=str(artifacts / f"failure-{number + 1}.png"), full_page=True,
                                    mask=[page.locator('input[type="password"]')])
                except Exception:
                    pass
            # 失败消息只记录阶段与异常类型，避免工具异常回显登录凭据或连接票据。
            raise RuntimeError(f"Browser acceptance failed during {stage}: {type(error).__name__}") from None
        finally:
            browser.close()


def main():
    try:
        run(options())
    except Exception as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
