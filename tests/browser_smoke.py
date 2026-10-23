"""验证真实网页双用户聊天、断线补读、分页及移动端导航。"""

import os
import secrets
import uuid
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

base=os.getenv('CHAT_BROWSER_BASE', 'http://localhost:8080').rstrip('/')
output=Path(os.getenv('CHAT_BROWSER_ARTIFACTS', '.local/browser-smoke'))
output.mkdir(parents=True, exist_ok=True)
suffix=uuid.uuid4().hex[:7]
password=secrets.token_urlsafe(18)
room='review-'+suffix
errors=[]
with sync_playwright() as p:
    browser=p.chromium.launch()
    contexts=[browser.new_context(viewport={'width':1440,'height':1000}, reduced_motion='reduce') for _ in range(2)]
    pages=[c.new_page() for c in contexts]
    for index,page in enumerate(pages):
        page.on('pageerror',lambda error:errors.append(str(error)))
        page.goto(base)
        page.locator('#register-tab').click()
        page.locator('#username').fill(('alex_' if index==0 else 'jamie_')+suffix)
        page.locator('#password').fill(password)
        page.locator('#auth-submit').click()
        expect(page.locator('#chat-view')).to_be_visible()
        expect(page.locator('#connection-label')).to_have_text('实时连接已就绪',timeout=15000)
    first,second=pages
    first.locator('#new-room').click()
    first.locator('#room-name').fill(room)
    first.locator('#room-description-input').fill('聊聊产品、设计，还有下一个好想法。')
    first.locator('#create-room-submit').click()
    expect(first.locator('#room-title')).to_have_text(room)
    second.reload()
    second.locator('.room-button').filter(has_text=room).click()
    expect(second.locator('#room-title')).to_have_text(room)
    message='你好，Relay！这是一条实时同步的消息。'
    first.locator('#message-input').fill(message)
    first.locator('#send-message').click()
    expect(second.locator('#message-list')).to_contain_text(message)
    reply='收到！我们可以从这个房间开始，把想法变成作品。'
    second.locator('#message-input').fill(reply)
    second.locator('#send-message').click()
    expect(first.locator('#message-list')).to_contain_text(reply)
    malicious='<script>window.unexpectedCode = true</script>'
    first.locator('#message-input').fill(malicious)
    first.locator('#send-message').click()
    expect(second.locator('#message-list')).to_contain_text(malicious)
    assert second.evaluate('window.unexpectedCode') is None
    first.reload()
    expect(first.locator('#chat-view')).to_be_visible()
    first.locator('.room-button').filter(has_text=room).click()
    expect(first.locator('#message-list')).to_contain_text(reply)
    first.screenshot(path=str(output / 'chat-desktop.png'),full_page=True)
    contexts[1].set_offline(True)
    session=first.evaluate("JSON.parse(sessionStorage.getItem('relay-chat-session'))")
    token=session['token']
    room_id=first.evaluate("async () => { const s=JSON.parse(sessionStorage.getItem('relay-chat-session')); const r=await fetch('/api/rooms',{headers:{Authorization:'Bearer '+s.token}}); return (await r.json()).data.find(x=>x.name===document.querySelector('#room-title').textContent).id; }")
    for index in range(65):
        response=contexts[0].request.post(f'{base}/api/rooms/{room_id}/messages',headers={'Authorization':'Bearer '+token},data={'content':f'断线恢复验证 {index:02d}','clientMessageId':str(uuid.uuid4())})
        assert response.ok
    contexts[1].set_offline(False)
    second.evaluate("window.dispatchEvent(new Event('online'))")
    expect(second.locator('#message-list')).to_contain_text('断线恢复验证 64',timeout=20000)
    expect(second.locator('#load-more')).to_be_visible()
    second.locator('#load-more').click()
    expect(second.locator('#message-list')).to_contain_text('断线恢复验证 00')
    expect(second.locator('#message-list')).to_contain_text(message)
    second.set_viewport_size({'width':390,'height':844})
    second.emulate_media(reduced_motion='reduce')
    expect(second.locator('#open-sidebar')).to_be_visible()
    assert not second.evaluate('document.documentElement.scrollWidth > innerWidth')
    second.locator('#open-sidebar').click()
    expect(second.locator('#sidebar')).to_have_class('sidebar open')
    second.keyboard.press('Escape')
    expect(second.locator('#sidebar')).to_have_class('sidebar')
    second.screenshot(path=str(output / 'chat-mobile.png'),full_page=True)
    second.locator('#open-sidebar').click()
    second.locator('#logout').click()
    expect(second.locator('#welcome-view')).to_be_visible()
    assert not errors,errors
    browser.close()
print('PASS: registration, rooms, two-user realtime, persistence, XSS text, >50 reconnect, pagination, mobile navigation, logout; no browser errors')
