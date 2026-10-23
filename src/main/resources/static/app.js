'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const state = {
    token: null,
    user: null,
    expiresAt: null,
    authMode: 'login',
    authEpoch: 0,
    roomEpoch: 0,
    rooms: [],
    activeRoom: null,
    messages: new Map(),
    pending: new Map(),
    hasMore: false,
    nextBefore: null,
    loadingHistory: false,
    socket: null,
    socketGeneration: 0,
    reconnectAttempt: 0,
    reconnectTimer: null,
    toastTimer: null,
    expirationTimer: null,
    connection: 'idle',
    roomLoadRequest: 0,
    subscriptionConfirmed: false,
  };
  const storageKey = 'relay-chat-session';
  const mobileViewport = window.matchMedia('(max-width: 760px)');
  const dateFormat = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' });
  const timeFormat = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });

  class ApiError extends Error {
    constructor(message, status, code) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  async function api(path, options = {}) {
    const requestToken = ['/api/auth/login', '/api/auth/register'].includes(path) ? null : state.token;
    const headers = { Accept: 'application/json', ...options.headers };
    if (options.body !== undefined) headers['Content-Type'] = 'application/json';
    if (requestToken) headers.Authorization = `Bearer ${requestToken}`;
    let response;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      response = await fetch(path, { ...options, signal: controller.signal, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) });
    } catch (error) {
      if (error.name === 'AbortError') throw new ApiError('请求超时，请稍后重试。', 0, 'request_timeout');
      throw new ApiError('暂时无法连接服务器，请检查网络后重试。', 0, 'network_error');
    } finally {
      clearTimeout(timeout);
    }
    let envelope;
    try {
      envelope = await response.json();
    } catch (error) {
      throw new ApiError('服务器返回了无法识别的响应，请稍后重试。', response.status, 'invalid_response');
    }
    if (!response.ok || envelope.success !== true) {
      if (response.status === 401 && requestToken && state.token === requestToken) {
        endSession('登录已过期，请重新登录。');
      }
      throw new ApiError(envelope.message || '操作未完成，请稍后重试。', response.status, envelope.code);
    }
    return envelope.data;
  }

  function showError(id, message) {
    const element = $(id);
    element.textContent = message;
    element.hidden = !message;
  }

  function toast(message) {
    clearTimeout(state.toastTimer);
    $('toast').textContent = message;
    $('toast').hidden = false;
    state.toastTimer = setTimeout(() => { $('toast').hidden = true; }, 5000);
  }

  function setConnection(status, label) {
    state.connection = status;
    $('connection-status').className = `connection-status ${status}`;
    $('connection-label').textContent = label;
    updateComposer();
  }

  function setBanner(message) {
    $('chat-banner').textContent = message;
    $('chat-banner').hidden = !message;
  }

  function setAuthMode(mode) {
    state.authMode = mode;
    const register = mode === 'register';
    $('login-tab').classList.toggle('active', !register);
    $('register-tab').classList.toggle('active', register);
    $('login-tab').setAttribute('aria-selected', String(!register));
    $('register-tab').setAttribute('aria-selected', String(register));
    $('login-tab').tabIndex = register ? -1 : 0;
    $('register-tab').tabIndex = register ? 0 : -1;
    $('auth-form').setAttribute('aria-labelledby', register ? 'register-tab' : 'login-tab');
    $('password').autocomplete = register ? 'new-password' : 'current-password';
    $('auth-title').textContent = register ? '从一句你好开始' : '欢迎回来';
    $('auth-subtitle').textContent = register ? '创建账号，找到你的对话空间。' : '登录账号，接着聊聊。';
    $('auth-submit').querySelector('span').textContent = register ? '创建账号并加入' : '登录工作空间';
    showError('auth-error', '');
  }

  function persistSession() {
    try {
      sessionStorage.setItem(storageKey, JSON.stringify({ token: state.token, user: state.user, expiresAt: state.expiresAt }));
    } catch (error) {
      toast('浏览器未允许保存会话，刷新后需要重新登录。');
    }
  }

  function scheduleExpiration() {
    clearTimeout(state.expirationTimer);
    if (!state.expiresAt) return;
    const expiry = typeof state.expiresAt === 'number'
      ? (state.expiresAt < 1e12 ? state.expiresAt * 1000 : state.expiresAt)
      : Date.parse(state.expiresAt);
    if (!Number.isFinite(expiry)) return;
    const remaining = expiry - Date.now();
    if (remaining <= 0) {
      endSession('登录已过期，请重新登录。');
    } else {
      state.expirationTimer = setTimeout(() => {
        if (Date.now() >= expiry) endSession('登录已过期，请重新登录。');
        else scheduleExpiration();
      }, Math.min(remaining, 2147483647));
    }
  }

  async function beginSession(session, restoring = false) {
    stopSocket();
    const epoch = ++state.authEpoch;
    state.token = session.token;
    state.user = session.user;
    state.expiresAt = session.expiresAt;
    if (restoring) {
      setConnection('connecting', '正在恢复会话');
      try {
        const user = await api('/api/auth/me');
        if (epoch !== state.authEpoch) return;
        state.user = user.user || user;
      } catch (error) {
        if (epoch !== state.authEpoch) return;
        if (error.status === 401) return;
        endSession();
        showError('auth-error', error.message);
        return;
      }
    }
    if (epoch !== state.authEpoch) return;
    persistSession();
    scheduleExpiration();
    if (!state.token) return;
    $('welcome-view').hidden = true;
    $('chat-view').hidden = false;
    $('new-room').disabled = false;
    $('logout').hidden = false;
    $('account-name').textContent = state.user.username;
    $('account-detail').textContent = '已登录 · 随时加入对话';
    $('account-avatar').textContent = initial(state.user.username);
    $('password').value = '';
    $('breadcrumb-room').textContent = '公共房间';
    setEmpty('选择一个房间，开始聊天。', '从左侧选择一个公共房间，\n或者创建一个属于新想法的空间。', true);
    await Promise.allSettled([loadRooms(), connectSocket()]);
  }

  function endSession(message = '') {
    state.authEpoch += 1;
    state.roomEpoch += 1;
    state.roomLoadRequest += 1;
    stopSocket();
    clearTimeout(state.expirationTimer);
    state.token = null;
    state.user = null;
    state.expiresAt = null;
    state.rooms = [];
    state.activeRoom = null;
    state.messages.clear();
    state.pending.clear();
    state.hasMore = false;
    state.loadingHistory = false;
    try { sessionStorage.removeItem(storageKey); } catch (error) { /* 禁用存储时，仍然在内存中结束会话。 */ }
    $('welcome-view').hidden = false;
    $('chat-view').hidden = true;
    $('new-room').disabled = true;
    $('logout').hidden = true;
    $('account-name').textContent = '欢迎来到 Relay';
    $('account-detail').textContent = '你的下一段对话，在这里。';
    $('account-avatar').textContent = 'R';
    $('breadcrumb-room').textContent = '欢迎';
    $('message-input').value = '';
    $('message-list').replaceChildren();
    $('room-title').textContent = '选择一个房间';
    $('room-description').textContent = '加入公共房间，开启新的对话。';
    $('load-more').hidden = true;
    $('new-messages').hidden = true;
    $('password').value = '';
    $('password').type = 'password';
    $('toggle-password').textContent = '显示';
    $('toggle-password').setAttribute('aria-label', '显示密码');
    $('toggle-password').setAttribute('aria-pressed', 'false');
    $('room-list').replaceChildren(placeholder('登录后，加入一段对话。'));
    if ($('room-dialog').open) $('room-dialog').close();
    closeSidebar();
    setConnection('idle', '等待连接');
    showError('auth-error', message);
    if (message) $('username').focus();
  }

  function placeholder(text) {
    const element = document.createElement('div');
    element.className = 'sidebar-placeholder';
    element.textContent = text;
    return element;
  }

  function renderRooms() {
    const fragment = document.createDocumentFragment();
    if (!state.rooms.length) fragment.append(placeholder('还没有房间，创建第一个吧。'));
    state.rooms.forEach((room) => {
      const button = document.createElement('button');
      const active = state.activeRoom && String(room.id) === String(state.activeRoom.id);
      button.type = 'button';
      button.className = `room-button${active ? ' active' : ''}`;
      button.dataset.roomId = String(room.id);
      if (active) button.setAttribute('aria-current', 'true');
      const hash = document.createElement('span');
      hash.className = 'room-hash';
      hash.textContent = '#';
      hash.setAttribute('aria-hidden', 'true');
      const name = document.createElement('span');
      name.className = 'room-name';
      name.textContent = room.name;
      button.append(hash, name);
      if (active) {
        const dot = document.createElement('span');
        dot.className = 'room-active-dot';
        dot.setAttribute('aria-hidden', 'true');
        button.append(dot);
      }
      fragment.append(button);
    });
    $('room-list').replaceChildren(fragment);
  }

  async function loadRooms() {
    const epoch = state.authEpoch;
    const requestId = ++state.roomLoadRequest;
    $('room-list').replaceChildren(placeholder('正在寻找对话空间…'));
    try {
      const rooms = await api('/api/rooms');
      if (epoch !== state.authEpoch || requestId !== state.roomLoadRequest) return;
      state.rooms = rooms;
      renderRooms();
      if (!state.activeRoom && rooms.length) selectRoom(rooms[0]);
    } catch (error) {
      if (epoch !== state.authEpoch || requestId !== state.roomLoadRequest) return;
      const message = placeholder(error.message);
      const retry = document.createElement('button');
      retry.type = 'button';
      retry.className = 'room-retry';
      retry.textContent = '重新加载房间';
      retry.addEventListener('click', loadRooms);
      message.append(document.createElement('br'), retry);
      $('room-list').replaceChildren(message);
      setEmpty('房间还没加载出来。', '检查网络后，从左侧重试。', true);
    }
  }

  function setEmpty(title, description, showCreate) {
    $('empty-title').textContent = title;
    $('empty-description').textContent = description;
    $('empty-create-room').hidden = !showCreate;
    $('retry-history').hidden = true;
    $('chat-empty').hidden = false;
  }

  async function selectRoom(room) {
    if (state.activeRoom && String(state.activeRoom.id) === String(room.id)) {
      closeSidebar();
      return;
    }
    const epoch = ++state.roomEpoch;
    state.activeRoom = room;
    state.messages.clear();
    state.hasMore = false;
    state.nextBefore = null;
    state.loadingHistory = false;
    state.subscriptionConfirmed = false;
    $('message-input').value = '';
    resizeComposer();
    $('room-title').textContent = room.name;
    $('room-description').textContent = room.description || '每个人都可以加入的公共对话。';
    $('breadcrumb-room').textContent = room.name;
    $('message-input').placeholder = `发送到 #${room.name}`;
    $('load-more').hidden = true;
    $('new-messages').hidden = true;
    $('message-list').replaceChildren();
    setEmpty('正在接上这段对话…', '消息历史马上就好。', false);
    renderRooms();
    closeSidebar();
    setBanner(state.connection === 'connected' ? '' : '实时连接正在恢复，你仍可发送消息。连接恢复后将自动同步。');
    updateComposer();
    joinRoom();
    if (state.connection !== 'connected') await loadHistory(false, epoch);
  }

  async function loadHistory(older = false, expectedEpoch = state.roomEpoch) {
    if (!state.activeRoom || expectedEpoch !== state.roomEpoch || state.loadingHistory) return;
    const roomId = state.activeRoom.id;
    const before = older ? state.nextBefore : null;
    if (older && (!state.hasMore || !before)) return;
    state.loadingHistory = true;
    $('load-more').disabled = true;
    $('load-more').textContent = older ? '正在加载…' : '加载更早的消息';
    const oldHeight = $('message-scroll').scrollHeight;
    const oldTop = $('message-scroll').scrollTop;
    try {
      const query = new URLSearchParams({ limit: '50' });
      if (before !== null) query.set('before', String(before));
      const page = await api(`/api/rooms/${encodeURIComponent(roomId)}/messages?${query}`);
      if (expectedEpoch !== state.roomEpoch) return;
      page.items.forEach((message) => storeMessage(message));
      state.hasMore = page.hasMore;
      state.nextBefore = page.nextBefore;
      renderMessages(!older);
      if (older) $('message-scroll').scrollTop = oldTop + $('message-scroll').scrollHeight - oldHeight;
      if (state.connection === 'connected') setBanner('');
    } catch (error) {
      if (expectedEpoch !== state.roomEpoch) return;
      if (!state.messages.size) {
        setEmpty('这段对话暂时没加载出来。', error.message, false);
        $('retry-history').hidden = false;
      }
      setBanner(`${error.message} 请重新加载消息，或稍后重试。`);
      if (older) toast(error.message);
    } finally {
      if (expectedEpoch === state.roomEpoch) {
        state.loadingHistory = false;
        $('load-more').disabled = false;
        $('load-more').textContent = '加载更早的消息';
        $('load-more').hidden = !state.hasMore;
      }
    }
  }

  function syncLatestMessages() {
    if (!state.activeRoom) return;
    const epoch = ++state.roomEpoch;
    state.messages.clear();
    state.hasMore = false;
    state.nextBefore = null;
    state.loadingHistory = false;
    $('message-list').replaceChildren();
    $('load-more').hidden = true;
    setEmpty('正在同步最新对话…', '稍等一下，马上就好。', false);
    loadHistory(false, epoch);
  }

  function initial(name) {
    return Array.from(name || '?').slice(0, 1).join('').toLocaleUpperCase();
  }

  function avatarTone(name) {
    return Array.from(name || '').reduce((sum, char) => sum + char.codePointAt(0), 0) % 5;
  }

  function storeMessage(message) {
    if (!message || !state.activeRoom || String(message.roomId) !== String(state.activeRoom.id)) return;
    state.messages.set(String(message.id), message);
    if (message.clientMessageId) state.pending.delete(message.clientMessageId);
  }

  function dateOf(message) {
    const date = new Date(message.createdAt);
    return Number.isNaN(date.getTime()) ? new Date(0) : date;
  }

  function renderMessages(forceScroll = false) {
    if (!state.activeRoom) return;
    const wasAtBottom = atBottom();
    const items = [...state.messages.values(), ...Array.from(state.pending.values()).filter((message) => String(message.roomId) === String(state.activeRoom.id))];
    items.sort((a, b) => {
      const timeDiff = dateOf(a).getTime() - dateOf(b).getTime();
      if (timeDiff) return timeDiff;
      return String(a.id || a.clientMessageId).localeCompare(String(b.id || b.clientMessageId), undefined, { numeric: true });
    });
    const fragment = document.createDocumentFragment();
    let previousDate = '';
    items.forEach((message) => {
      const date = dateOf(message);
      const dateKey = `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
      if (dateKey !== previousDate) {
        const divider = document.createElement('div');
        divider.className = 'date-divider';
        divider.textContent = dateFormat.format(date);
        fragment.append(divider);
        previousDate = dateKey;
      }
      fragment.append(messageElement(message));
    });
    $('message-list').replaceChildren(fragment);
    if (items.length) $('chat-empty').hidden = true;
    else setEmpty('第一句，留给你。', '这段对话还没有消息。\n打个招呼，让新的连接开始。', false);
    if (forceScroll || wasAtBottom) scrollToBottom();
  }

  function messageElement(message) {
    const own = String(message.sender.id) === String(state.user.id);
    const article = document.createElement('article');
    article.className = `message${message.status ? ` ${message.status}` : ''}`;
    const avatar = document.createElement('div');
    avatar.className = `avatar message-avatar avatar-tone-${avatarTone(message.sender.username)}`;
    avatar.textContent = initial(message.sender.username);
    avatar.setAttribute('aria-hidden', 'true');
    const main = document.createElement('div');
    main.className = 'message-main';
    const meta = document.createElement('div');
    meta.className = 'message-meta';
    const name = document.createElement('span');
    name.className = 'message-name';
    name.textContent = message.sender.username;
    meta.append(name);
    if (own) {
      const label = document.createElement('span');
      label.className = 'self-label';
      label.textContent = '你';
      meta.append(label);
    }
    const time = document.createElement('time');
    time.className = 'message-time';
    time.dateTime = dateOf(message).toISOString();
    time.textContent = timeFormat.format(dateOf(message));
    time.title = `${dateFormat.format(dateOf(message))} ${time.textContent}`;
    meta.append(time);
    const content = document.createElement('p');
    content.className = 'message-content';
    content.textContent = message.content;
    main.append(meta, content);
    if (message.status) {
      const delivery = document.createElement('div');
      delivery.className = 'message-delivery';
      const detail = document.createElement('span');
      detail.textContent = message.status === 'failed' ? `发送失败 · ${message.error || '请重试'}` : '正在发送…';
      delivery.append(detail);
      if (message.status === 'failed') {
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.className = 'retry-message';
        retry.textContent = '重新发送';
        retry.dataset.retryId = message.clientMessageId;
        delivery.append(retry);
      }
      main.append(delivery);
    }
    article.append(avatar, main);
    return article;
  }

  function atBottom() {
    const scroller = $('message-scroll');
    return scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 100;
  }

  function scrollToBottom() {
    const scroller = $('message-scroll');
    scroller.scrollTop = scroller.scrollHeight;
    $('new-messages').hidden = true;
  }

  function updateComposer() {
    const enabled = Boolean(state.token && state.activeRoom);
    $('message-input').disabled = !enabled;
    $('send-message').disabled = !enabled || !$('message-input').value.trim() || navigator.onLine === false;
    $('character-count').textContent = `${$('message-input').value.length} / 2000`;
  }

  function resizeComposer() {
    const textarea = $('message-input');
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 144)}px`;
    updateComposer();
  }

  function uuid() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  async function transmitMessage(pending) {
    if (!state.token || pending.status === 'pending') return;
    const authEpoch = state.authEpoch;
    pending.status = 'pending';
    pending.error = '';
    if (state.activeRoom && String(pending.roomId) === String(state.activeRoom.id)) renderMessages(true);
    try {
      const message = await api(`/api/rooms/${encodeURIComponent(pending.roomId)}/messages`, {
        method: 'POST', body: { content: pending.content, clientMessageId: pending.clientMessageId },
      });
      if (authEpoch !== state.authEpoch) return;
      state.pending.delete(pending.clientMessageId);
      if (state.activeRoom && String(message.roomId) === String(state.activeRoom.id)) {
        storeMessage(message);
        renderMessages(false);
      }
    } catch (error) {
      if (authEpoch !== state.authEpoch || !state.pending.has(pending.clientMessageId)) return;
      pending.status = 'failed';
      pending.error = error.message;
      if (state.activeRoom && String(pending.roomId) === String(state.activeRoom.id)) renderMessages(false);
    }
  }

  async function sendMessage(event) {
    event.preventDefault();
    const content = $('message-input').value.trim();
    if (!content || !state.activeRoom || !state.user) return;
    if (content.length > 2000) {
      toast('每条消息最多 2000 个字符。');
      return;
    }
    if (navigator.onLine === false) {
      toast('当前处于离线状态，恢复网络后再发送。');
      return;
    }
    const pending = {
      clientMessageId: uuid(), roomId: state.activeRoom.id,
      content, sender: { ...state.user }, createdAt: new Date().toISOString(), status: 'queued',
    };
    state.pending.set(pending.clientMessageId, pending);
    $('message-input').value = '';
    resizeComposer();
    $('message-input').focus();
    await transmitMessage(pending);
  }

  function joinRoom() {
    if (state.activeRoom && state.socket && state.socket.readyState === WebSocket.OPEN) {
      state.socket.send(JSON.stringify({ type: 'JOIN', roomId: state.activeRoom.id }));
    }
  }

  function stopSocket() {
    state.socketGeneration += 1;
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
    state.reconnectAttempt = 0;
    state.subscriptionConfirmed = false;
    if (state.socket) {
      const socket = state.socket;
      state.socket = null;
      socket.close(1000, 'Session ended');
    }
  }

  function scheduleReconnect(generation) {
    if (!state.token || generation !== state.socketGeneration) return;
    clearTimeout(state.reconnectTimer);
    if (navigator.onLine === false) {
      setConnection('disconnected', '网络已断开');
      setBanner('当前处于离线状态。网络恢复后会自动重新连接。');
      return;
    }
    const delay = Math.min(1000 * (2 ** Math.min(state.reconnectAttempt, 5)), 30000) + Math.floor(Math.random() * 500);
    state.reconnectAttempt += 1;
    setConnection('connecting', '正在重新连接');
    setBanner('实时连接正在恢复，你仍可发送消息。连接恢复后将自动同步。');
    state.reconnectTimer = setTimeout(connectSocket, delay);
  }

  async function connectSocket() {
    if (!state.token || navigator.onLine === false) {
      if (state.token) setConnection('disconnected', '网络已断开');
      return;
    }
    clearTimeout(state.reconnectTimer);
    if (state.socket && (state.socket.readyState === WebSocket.OPEN || state.socket.readyState === WebSocket.CONNECTING)) return;
    const generation = ++state.socketGeneration;
    const authEpoch = state.authEpoch;
    setConnection('connecting', state.reconnectAttempt ? '正在重新连接' : '正在连接');
    try {
      const { ticket } = await api('/api/auth/ws-ticket', { method: 'POST' });
      if (authEpoch !== state.authEpoch || generation !== state.socketGeneration || !state.token) return;
      const url = new URL('/ws', location.href);
      url.protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
      url.searchParams.set('ticket', ticket);
      const socket = new WebSocket(url);
      state.socket = socket;
      let handshakeComplete = false;
      const handshakeTimer = setTimeout(() => {
        if (generation === state.socketGeneration && !handshakeComplete) socket.close(1000, 'Connection timeout');
      }, 15000);
      socket.addEventListener('open', () => {
        if (generation !== state.socketGeneration) { socket.close(); return; }
        joinRoom();
      });
      socket.addEventListener('message', (event) => {
        if (generation !== state.socketGeneration) return;
        let packet;
        try { packet = JSON.parse(event.data); } catch (error) { return; }
        if (packet.type === 'READY') {
          handshakeComplete = true;
          clearTimeout(handshakeTimer);
          state.reconnectAttempt = 0;
          setConnection('connected', '实时连接已就绪');
          setBanner('');
        } else if (packet.type === 'JOINED' && state.activeRoom && String(packet.roomId) === String(state.activeRoom.id)) {
          state.subscriptionConfirmed = true;
          syncLatestMessages();
        } else if (packet.type === 'MESSAGE' && packet.data) {
          if (!state.activeRoom || String(packet.data.roomId) !== String(state.activeRoom.id)) return;
          const wasAtBottom = atBottom();
          const isNew = !state.messages.has(String(packet.data.id));
          storeMessage(packet.data);
          renderMessages(false);
          if (!wasAtBottom && isNew) $('new-messages').hidden = false;
        } else if (packet.type === 'ERROR') {
          if (packet.code === 'unauthorized' || packet.code === 'token_expired') {
            endSession('登录已过期，请重新登录。');
          } else {
            setBanner(packet.message || '实时消息连接发生错误，请稍后重试。');
          }
        }
      });
      socket.addEventListener('close', (event) => {
        clearTimeout(handshakeTimer);
        if (generation !== state.socketGeneration) return;
        state.socket = null;
        state.subscriptionConfirmed = false;
        if (event.code === 1008 || event.code === 4001 || event.code === 4401) {
          endSession('实时连接认证已失效，请重新登录。');
          return;
        }
        scheduleReconnect(generation);
      });
      socket.addEventListener('error', () => {
        if (generation === state.socketGeneration && socket.readyState === WebSocket.OPEN) socket.close();
      });
    } catch (error) {
      if (authEpoch !== state.authEpoch || generation !== state.socketGeneration) return;
      scheduleReconnect(generation);
    }
  }

  function openRoomDialog() {
    if (!state.token) return;
    closeSidebar();
    $('room-form').reset();
    showError('room-error', '');
    $('room-dialog').showModal();
    $('room-name').focus();
  }

  function openSidebar() {
    $('sidebar').inert = false;
    $('sidebar').removeAttribute('aria-hidden');
    $('sidebar').classList.add('open');
    $('sidebar-overlay').hidden = false;
    $('open-sidebar').setAttribute('aria-expanded', 'true');
    document.querySelector('.main').inert = true;
    $('close-sidebar').focus();
  }

  function closeSidebar() {
    const wasOpen = $('sidebar').classList.contains('open');
    $('sidebar').classList.remove('open');
    $('sidebar-overlay').hidden = true;
    $('open-sidebar').setAttribute('aria-expanded', 'false');
    document.querySelector('.main').inert = false;
    $('sidebar').inert = mobileViewport.matches;
    if (mobileViewport.matches) $('sidebar').setAttribute('aria-hidden', 'true');
    else $('sidebar').removeAttribute('aria-hidden');
    if (wasOpen) $('open-sidebar').focus();
  }

  $('login-tab').addEventListener('click', () => setAuthMode('login'));
  $('register-tab').addEventListener('click', () => setAuthMode('register'));
  document.querySelector('.auth-tabs').addEventListener('keydown', (event) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const mode = event.key === 'Home' ? 'login' : event.key === 'End' ? 'register' : state.authMode === 'login' ? 'register' : 'login';
    setAuthMode(mode);
    $(`${mode}-tab`).focus();
  });
  $('toggle-password').addEventListener('click', () => {
    const visible = $('password').type === 'password';
    $('password').type = visible ? 'text' : 'password';
    $('toggle-password').textContent = visible ? '隐藏' : '显示';
    $('toggle-password').setAttribute('aria-label', visible ? '隐藏密码' : '显示密码');
    $('toggle-password').setAttribute('aria-pressed', String(visible));
  });
  $('auth-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if ($('auth-submit').disabled) return;
    if (new TextEncoder().encode($('password').value).length > 72) {
      showError('auth-error', '密码最多 72 个 UTF-8 字节，请缩短后重试。');
      $('password').focus();
      return;
    }
    const mode = state.authMode;
    const epoch = state.authEpoch;
    $('auth-submit').disabled = true;
    $('login-tab').disabled = true;
    $('register-tab').disabled = true;
    $('auth-submit').querySelector('span').textContent = mode === 'register' ? '正在创建账号…' : '正在登录…';
    showError('auth-error', '');
    try {
      const session = await api(`/api/auth/${mode}`, {
        method: 'POST', body: { username: $('username').value.trim(), password: $('password').value },
      });
      if (epoch === state.authEpoch) await beginSession(session);
    } catch (error) {
      if (epoch === state.authEpoch) showError('auth-error', error.message);
    } finally {
      $('auth-submit').disabled = false;
      $('login-tab').disabled = false;
      $('register-tab').disabled = false;
      $('auth-submit').querySelector('span').textContent = state.authMode === 'register' ? '创建账号并加入' : '登录工作空间';
    }
  });
  $('logout').addEventListener('click', () => { endSession(); toast('已退出登录。'); });
  $('new-room').addEventListener('click', openRoomDialog);
  $('empty-create-room').addEventListener('click', openRoomDialog);
  $('close-dialog').addEventListener('click', () => $('room-dialog').close());
  $('room-dialog').addEventListener('click', (event) => {
    if (event.target !== $('room-dialog')) return;
    const bounds = $('room-dialog').getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) $('room-dialog').close();
  });
  $('room-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const name = $('room-name').value.trim();
    const description = $('room-description-input').value.trim();
    if (!name) { showError('room-error', '请给房间起一个名字。'); return; }
    const epoch = state.authEpoch;
    $('create-room-submit').disabled = true;
    $('create-room-submit').querySelector('span').textContent = '正在创建…';
    showError('room-error', '');
    try {
      const room = await api('/api/rooms', { method: 'POST', body: { name, description } });
      if (epoch !== state.authEpoch) return;
      state.rooms = [...state.rooms.filter((item) => String(item.id) !== String(room.id)), room];
      $('room-dialog').close();
      renderRooms();
      selectRoom(room);
      toast('房间已创建，来发第一条消息吧。');
    } catch (error) {
      if (epoch === state.authEpoch) showError('room-error', error.message);
    } finally {
      $('create-room-submit').disabled = false;
      $('create-room-submit').querySelector('span').textContent = '创建房间';
    }
  });
  $('room-list').addEventListener('click', (event) => {
    const button = event.target.closest('[data-room-id]');
    if (!button) return;
    const room = state.rooms.find((item) => String(item.id) === button.dataset.roomId);
    if (room) selectRoom(room);
  });
  $('message-form').addEventListener('submit', sendMessage);
  $('message-input').addEventListener('input', resizeComposer);
  $('message-input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      if (!$('send-message').disabled) $('message-form').requestSubmit();
    }
  });
  $('message-list').addEventListener('click', (event) => {
    const button = event.target.closest('[data-retry-id]');
    if (!button) return;
    const pending = state.pending.get(button.dataset.retryId);
    if (pending) transmitMessage(pending);
  });
  $('load-more').addEventListener('click', () => loadHistory(true));
  $('retry-history').addEventListener('click', () => {
    setEmpty('正在重新加载对话…', '稍等一下，马上就好。', false);
    loadHistory(false);
  });
  $('new-messages').addEventListener('click', scrollToBottom);
  $('message-scroll').addEventListener('scroll', () => { if (atBottom()) $('new-messages').hidden = true; }, { passive: true });
  $('open-sidebar').addEventListener('click', openSidebar);
  $('close-sidebar').addEventListener('click', closeSidebar);
  $('sidebar-overlay').addEventListener('click', closeSidebar);
  mobileViewport.addEventListener('change', closeSidebar);
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeSidebar();
    if (!$('sidebar').classList.contains('open') || event.key !== 'Tab') return;
    const focusables = [...$('sidebar').querySelectorAll('a,button:not(:disabled)')].filter((element) => element.offsetParent !== null);
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  });
  window.addEventListener('online', () => {
    updateComposer();
    if (state.token) connectSocket();
  });
  window.addEventListener('offline', () => {
    if (!state.token) return;
    stopSocket();
    setConnection('disconnected', '网络已断开');
    setBanner('当前处于离线状态。网络恢复后会自动重新连接。');
  });
  window.addEventListener('pageshow', () => {
    if (state.token) { scheduleExpiration(); connectSocket(); }
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && state.token) {
      scheduleExpiration();
      connectSocket();
      if (state.activeRoom && state.connection === 'connected') joinRoom();
    }
  });

  async function initialize() {
    closeSidebar();
    let session;
    try { session = JSON.parse(sessionStorage.getItem(storageKey) || 'null'); } catch (error) { session = null; }
    if (session && typeof session.token === 'string' && session.token && session.user && typeof session.user.username === 'string') {
      await beginSession(session, true);
    } else {
      setConnection('idle', '等待连接');
    }
  }

  initialize();
})();
