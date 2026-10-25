"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const storageKey = "relay-room-session";
  const mobile = window.matchMedia("(max-width: 760px)");
  const state = {
    token: null,
    user: null,
    expiresAt: null,
    mode: "login",
    authBusy: false,
    roomBusy: false,
    roomsLoading: false,
    rooms: [],
    selectedId: null,
    authEpoch: 0,
    authRequest: 0,
    roomRequest: 0,
    expirationTimer: null,
    toastTimer: null,
    chats: new Map(),
    socket: null,
    socketEpoch: 0,
    connecting: false,
    reconnectAttempt: 0,
    reconnectTimer: null,
    connectTimer: null,
    pingTimer: null,
    recoveryTimer: null,
    lastActivity: 0,
    connection: "connecting",

  };

  class ApiError extends Error {
    constructor(message, status) {
      super(message);
      this.status = status;
    }
  }

  async function api(path, { method = "GET", body, token = state.token } = {}) {
    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (token) headers.Authorization = `Bearer ${token}`;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
        credentials: "same-origin",
        cache: "no-store",
      });
      let payload;
      try {
        payload = await response.json();
      } catch (error) {
        throw new ApiError(
          "The service returned an unexpected response. Please try again.",
          response.status,
        );
      }
      if (!response.ok || payload.success !== true) {
        const message =
          typeof payload.message === "string" && payload.message
            ? payload.message
            : "The request could not be completed. Please try again.";
        if (response.status === 401 && token && token === state.token) {
          endSession("Your session has expired. Please sign in again.");
        }
        throw new ApiError(message, response.status);
      }
      return payload.data;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (error.name === "AbortError") {
        throw new ApiError(
          "This is taking longer than expected. Please try again.",
          0,
        );
      }
      throw new ApiError(
        "Could not reach the chat service. Check your connection and try again.",
        0,
      );
    } finally {
      clearTimeout(timeout);
    }
  }

  function showError(id, message) {
    $(id).textContent = message;
    $(id).hidden = !message;
  }

  function toast(message) {
    clearTimeout(state.toastTimer);
    $("toast").textContent = message;
    $("toast").hidden = false;
    state.toastTimer = setTimeout(() => {
      $("toast").hidden = true;
    }, 5000);
  }

  function updateAuthControls() {
    $("username").disabled = state.authBusy;
    $("password").disabled = state.authBusy;
    $("auth-submit").disabled = state.authBusy;
    $("login-tab").disabled = state.authBusy;
    $("register-tab").disabled = state.authBusy;
    $("toggle-password").disabled = state.authBusy;
    $("auth-submit-label").textContent = state.authBusy
      ? state.mode === "register"
        ? "Creating your account…"
        : "Signing you in…"
      : state.mode === "register"
        ? "Create your account"
        : "Sign in to chat";
  }

  function setMode(mode, focus = false) {
    if (state.authBusy) return;
    state.mode = mode;
    const registering = mode === "register";
    $("auth-title").textContent = registering
      ? "Start something good."
      : "Welcome back.";
    $("auth-subtitle").textContent = registering
      ? "A name, a password, and a little space."
      : "Sign in and find your place.";
    $("login-tab").classList.toggle("active", !registering);
    $("register-tab").classList.toggle("active", registering);
    $("login-tab").setAttribute("aria-selected", String(!registering));
    $("register-tab").setAttribute("aria-selected", String(registering));
    $("login-tab").tabIndex = registering ? -1 : 0;
    $("register-tab").tabIndex = registering ? 0 : -1;
    $("auth-form").setAttribute(
      "aria-labelledby",
      registering ? "register-tab" : "login-tab",
    );
    $("password").autocomplete = registering
      ? "new-password"
      : "current-password";
    showError("auth-error", "");
    updateAuthControls();
    if (focus) $(registering ? "register-tab" : "login-tab").focus();
  }

  function scheduleExpiration() {
    clearTimeout(state.expirationTimer);
    if (!state.token) return;
    const remaining = new Date(state.expiresAt).getTime() - Date.now();
    if (!Number.isFinite(remaining) || remaining <= 0) {
      endSession("Your session has expired. Please sign in again.");
      return;
    }
    state.expirationTimer = setTimeout(
      scheduleExpiration,
      Math.min(remaining + 50, 2147483647),
    );
  }

  async function beginSession(session, restore = false) {
    if (
      !session ||
      typeof session.token !== "string" ||
      !session.token ||
      !session.user ||
      typeof session.user.username !== "string" ||
      !Number.isFinite(Date.parse(session.expiresAt))
    ) {
      throw new ApiError(
        "The sign-in response was incomplete. Please try again.",
        0,
      );
    }
    const epoch = ++state.authEpoch;
    state.token = session.token;
    state.user = session.user;
    state.expiresAt = session.expiresAt;
    if (Date.parse(session.expiresAt) <= Date.now()) {
      endSession("Your session has expired. Please sign in again.");
      return;
    }
    if (restore) {
      const user = await api("/api/auth/me");
      if (epoch !== state.authEpoch) return;
      state.user = user;
    }
    try {
      sessionStorage.setItem(
        storageKey,
        JSON.stringify({
          token: state.token,
          user: state.user,
          expiresAt: state.expiresAt,
        }),
      );
    } catch (error) {
      toast(
        "You're signed in. Your browser won't keep this session after a refresh.",
      );
    }
    $("account-name").textContent = state.user.username;
    $("account-avatar").textContent = Array.from(
      state.user.username,
    )[0].toUpperCase();
    $("account-detail").textContent = "A little space of your own.";
    $("logout").hidden = false;
    $("new-room").disabled = false;
    $("refresh-rooms").disabled = false;
    $("welcome-view").hidden = true;
    $("workspace-view").hidden = false;
    $("breadcrumb-room").textContent = "Rooms";
    $("password").value = "";
    $("password").type = "password";
    $("toggle-password").textContent = "Show";
    $("toggle-password").setAttribute("aria-label", "Show password");
    $("toggle-password").setAttribute("aria-pressed", "false");
    scheduleExpiration();
    connectRealtime();
    clearInterval(state.recoveryTimer);
    state.recoveryTimer = setInterval(() => {
      if (document.visibilityState === "visible") syncCurrentRoom();
    }, 30000);
    await loadRooms();
  }

  function endSession(message = "") {
    state.authEpoch += 1;
    state.authRequest += 1;
    state.roomRequest += 1;
    clearTimeout(state.expirationTimer);
    stopRealtime();
    state.chats.clear();
    state.token = null;
    state.user = null;
    state.expiresAt = null;
    state.rooms = [];
    state.selectedId = null;
    state.roomsLoading = false;
    state.roomBusy = false;
    state.authBusy = false;
    try {
      sessionStorage.removeItem(storageKey);
    } catch (error) {
      /* 禁用浏览器存储时仍清理当前内存会话。 */
    }
    if ($("room-dialog").open) $("room-dialog").close();
    $("welcome-view").hidden = false;
    $("workspace-view").hidden = true;
    $("logout").hidden = true;
    $("new-room").disabled = true;
    $("refresh-rooms").disabled = true;
    $("account-name").textContent = "Welcome to chat";
    $("account-avatar").textContent = "R";
    $("account-detail").textContent = "Your next idea belongs here.";
    $("breadcrumb-room").textContent = "Welcome";
    $("room-count").textContent = "0 rooms";
    $("room-detail").hidden = true;
    $("workspace-notice").hidden = true;
    $("message-list").replaceChildren();
    $("message-input").value = "";
    $("password").value = "";
    $("password").type = "password";
    $("toggle-password").textContent = "Show";
    $("toggle-password").setAttribute("aria-label", "Show password");
    $("toggle-password").setAttribute("aria-pressed", "false");
    setMode("login");
    renderRooms();
    closeSidebar();
    if (message) showError("auth-error", message);
  }

  async function submitAuth(event) {
    event.preventDefault();
    if (state.authBusy) return;
    const username = $("username").value.trim().toLowerCase();
    const password = $("password").value;
    if (!/^[a-z0-9_]{3,24}$/.test(username)) {
      showError(
        "auth-error",
        "Choose a username with 3–24 letters, numbers, or underscores.",
      );
      return;
    }
    if (password.length < 8 || new TextEncoder().encode(password).length > 72) {
      showError(
        "auth-error",
        "Use at least 8 characters. With special characters, a shorter password may be needed.",
      );
      return;
    }
    const epoch = state.authEpoch;
    const operation = ++state.authRequest;
    state.authBusy = true;
    showError("auth-error", "");
    updateAuthControls();
    try {
      const session = await api(`/api/auth/${state.mode}`, {
        method: "POST",
        body: { username, password },
        token: null,
      });
      if (epoch !== state.authEpoch) return;
      await beginSession(session);
    } catch (error) {
      if (operation === state.authRequest)
        showError("auth-error", error.message);
    } finally {
      if (operation === state.authRequest) {
        state.authBusy = false;
        updateAuthControls();
      }
    }
  }

  function renderRooms() {
    const fragment = document.createDocumentFragment();
    if (!state.rooms.length) {
      const note = document.createElement("p");
      note.className = "sidebar-placeholder";
      note.textContent = !state.token
        ? "Sign in to explore the workspace."
        : state.roomsLoading
          ? "Finding your rooms…"
          : "A fresh workspace. Create the first room.";
      fragment.append(note);
    }
    state.rooms.forEach((room) => {
      const selected = String(room.id) === String(state.selectedId);
      const button = document.createElement("button");
      button.type = "button";
      button.className = selected ? "room-button active" : "room-button";
      button.setAttribute("aria-pressed", String(selected));
      const hash = document.createElement("span");
      hash.className = "room-hash";
      hash.textContent = "#";
      hash.setAttribute("aria-hidden", "true");
      const name = document.createElement("span");
      name.className = "room-name";
      name.textContent = room.name;
      button.append(hash, name);
      if (selected) {
        const dot = document.createElement("span");
        dot.className = "room-active-dot";
        dot.setAttribute("aria-hidden", "true");
        button.append(dot);
      }
      button.addEventListener("click", () => selectRoom(room));
      fragment.append(button);
    });
    $("room-list").replaceChildren(fragment);
    $("room-count").textContent =
      `${state.rooms.length} ${state.rooms.length === 1 ? "room" : "rooms"}`;
  }

  function setEmpty(title, description, canCreate) {
    $("room-detail").hidden = true;
    $("empty-state").hidden = false;
    $("empty-title").textContent = title;
    $("empty-description").textContent = description;
    $("empty-create-room").hidden = !canCreate;
  }

  function activeChat() {
    return state.chats.get(String(state.selectedId));
  }

  function currentChat(chat) {
    return Boolean(state.token && chat.epoch === state.authEpoch && chat === activeChat());
  }

  function selectRoom(room) {
    if (!state.token) return;
    const previous = activeChat();
    if (previous && !previous.pending) previous.draft = $("message-input").value;
    state.selectedId = room.id;
    const key = String(room.id);
    if (!state.chats.has(key)) {
      state.chats.set(key, {
        roomId: room.id, epoch: state.authEpoch, messages: new Map(),
        loaded: false, syncing: false, syncAgain: false, hasOlder: false,
        olderBusy: false, oldestId: null, syncedThrough: 0, error: "", errorAction: "sync",
        draft: "", pending: null, sendError: "",
      });
    }
    const chat = activeChat();
    $("room-title").textContent = room.name;
    $("room-description").textContent = room.description || "A public room for a little conversation.";
    $("breadcrumb-room").textContent = room.name;
    $("message-label").textContent = `Message #${room.name}`;
    $("room-detail").hidden = false;
    $("empty-state").hidden = true;
    $("message-input").value = chat.pending ? chat.pending.content : chat.draft;
    renderRooms();
    renderMessages(chat, { bottom: true });
    updateConnectionStatus();
    closeSidebar();
    subscribeRoom();
    syncHistory(chat);
  }

  function messageIsValid(message) {
    return message && Number.isSafeInteger(message.id) && message.id > 0 &&
      Number.isSafeInteger(message.roomId) && Number.isSafeInteger(message.userId) &&
      typeof message.username === "string" && typeof message.content === "string" &&
      typeof message.clientMessageId === "string" && Number.isFinite(Date.parse(message.createdAt));
  }

  function mergeMessage(chat, message) {
    if (!messageIsValid(message) || String(message.roomId) !== String(chat.roomId)) return false;
    chat.messages.set(message.id, message);
    if (chat.pending && message.clientMessageId === chat.pending.clientMessageId &&
      String(message.userId) === String(state.user?.id)) {
      chat.pending = null;
      chat.draft = "";
      chat.sendError = "";
      if (currentChat(chat)) $("message-input").value = "";
    }
    return true;
  }

  function renderMessages(chat, { bottom = false } = {}) {
    if (!currentChat(chat)) return;
    const scroller = $("chat-history");
    const nearBottom = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 100;
    const fragment = document.createDocumentFragment();
    const messages = [...chat.messages.values()].sort((a, b) => a.id - b.id);
    for (const message of messages) {
      const item = document.createElement("li");
      item.className = "message";
      if (String(message.userId) === String(state.user.id)) item.classList.add("own-message");
      item.dataset.messageId = String(message.id);
      const avatar = document.createElement("span");
      avatar.className = "message-avatar";
      avatar.setAttribute("aria-hidden", "true");
      avatar.textContent = Array.from(message.username)[0]?.toUpperCase() || "?";
      const body = document.createElement("div");
      body.className = "message-body";
      const metadata = document.createElement("div");
      metadata.className = "message-meta";
      const author = document.createElement("strong");
      author.textContent = message.username;
      const time = document.createElement("time");
      time.dateTime = message.createdAt;
      const date = new Date(message.createdAt);
      time.textContent = new Intl.DateTimeFormat("en", {
        month: "short", day: "numeric", hour: "numeric", minute: "2-digit",
      }).format(date);
      time.title = date.toLocaleString();
      metadata.append(author, time);
      const content = document.createElement("p");
      content.className = "message-content";
      content.textContent = message.content;
      body.append(metadata, content);
      item.append(avatar, body);
      fragment.append(item);
    }
    if (chat.pending) {
      const item = document.createElement("li");
      item.className = "message pending-message";
      const body = document.createElement("div");
      body.className = "message-body";
      const content = document.createElement("p");
      content.className = "message-content";
      content.textContent = chat.pending.content;
      const status = document.createElement("div");
      status.className = "pending-status";
      const label = document.createElement("span");
      label.textContent = chat.pending.status === "sending" ? "Sending…" : "Delivery not confirmed.";
      status.append(label);
      if (chat.pending.status === "failed") {
        const retry = document.createElement("button");
        retry.type = "button";
        retry.className = "text-button retry-message";
        retry.textContent = "Retry message";
        retry.addEventListener("click", () => sendPending(chat));
        status.append(retry);
      }
      body.append(content, status);
      item.append(body);
      fragment.append(item);
    }
    $("message-list").replaceChildren(fragment);
    $("conversation-empty").hidden = !chat.loaded || messages.length > 0 || Boolean(chat.pending);
    $("load-older").hidden = !chat.hasOlder;
    $("load-older").disabled = chat.olderBusy;
    $("load-older").textContent = chat.olderBusy ? "Loading older messages…" : "Load older messages";
    $("history-state").textContent = !chat.loaded ? "Loading messages…" : chat.syncing ? "Checking for new messages…" :
      !chat.hasOlder && messages.length ? "You're at the beginning of this conversation." : "";
    $("history-error-text").textContent = chat.error;
    $("history-error").hidden = !chat.error;
    updateComposer(chat);
    if (bottom || nearBottom) scroller.scrollTop = scroller.scrollHeight;
  }

  function updateComposer(chat = activeChat()) {
    if (!chat || !currentChat(chat)) return;
    const input = $("message-input");
    input.readOnly = Boolean(chat.pending);
    const sending = chat.pending?.status === "sending";
    const content = input.value.trim();
    $("send-message").disabled = Boolean(sending || (!chat.pending && (!content || content.length > 2000)));
    $("send-message-label").textContent = sending ? "Sending…" : chat.pending ? "Retry message" : "Send message";
    $("message-count").textContent = `${input.value.length} / 2000`;
    showError("message-error", chat.sendError);
  }

  async function syncHistory(chat) {
    if (!currentChat(chat)) return;
    if (chat.syncing) {
      chat.syncAgain = true;
      return;
    }
    chat.syncing = true;
    chat.error = "";
    chat.errorAction = "sync";
    renderMessages(chat);
    let continueLater = false;
    try {
      if (!chat.loaded) {
        const page = await api(`/api/rooms/${chat.roomId}/messages?limit=50`);
        if (!currentChat(chat)) return;
        if (!Array.isArray(page?.messages)) throw new ApiError("The conversation could not be loaded. Please try again.", 0);
        for (const message of page.messages) mergeMessage(chat, message);
        const ids = page.messages.filter(messageIsValid).map((message) => message.id);
        chat.oldestId = ids.length ? Math.min(...ids) : null;
        chat.syncedThrough = ids.length ? Math.max(...ids) : 0;
        chat.hasOlder = Boolean(page.hasMore);
        chat.loaded = true;
        renderMessages(chat, { bottom: true });
      }
      // 历史游标只在 HTTP 补读后推进，避免实时事件越过尚未补齐的消息。
      for (let pageNumber = 0; pageNumber < 20; pageNumber += 1) {
        const page = await api(`/api/rooms/${chat.roomId}/messages?after=${chat.syncedThrough}&limit=100`);
        if (!currentChat(chat)) return;
        if (!Array.isArray(page?.messages)) throw new ApiError("New messages could not be checked. Please try again.", 0);
        const previous = chat.syncedThrough;
        for (const message of page.messages) {
          if (mergeMessage(chat, message)) chat.syncedThrough = Math.max(chat.syncedThrough, message.id);
        }
        if (chat.oldestId === null && chat.messages.size) chat.oldestId = Math.min(...chat.messages.keys());
        renderMessages(chat);
        if (!page.hasMore) break;
        if (chat.syncedThrough <= previous) throw new ApiError("Message history could not be continued. Please try again.", 0);
        if (pageNumber === 19) continueLater = true;
      }
    } catch (error) {
      if (currentChat(chat)) chat.error = error.message;
    } finally {
      chat.syncing = false;
      if (currentChat(chat)) {
        renderMessages(chat);
        if ((chat.syncAgain || continueLater) && !chat.error) {
          chat.syncAgain = false;
          setTimeout(() => syncHistory(chat), 0);
        }
      }
    }
  }

  function syncCurrentRoom() {
    const chat = activeChat();
    if (chat) syncHistory(chat);
  }

  async function loadOlder() {
    const chat = activeChat();
    if (!chat || !chat.hasOlder || chat.olderBusy || chat.oldestId === null) return;
    const before = chat.oldestId;
    chat.olderBusy = true;
    chat.error = "";
    chat.errorAction = "older";
    renderMessages(chat);
    try {
      const page = await api(`/api/rooms/${chat.roomId}/messages?before=${before}&limit=50`);
      if (!currentChat(chat)) return;
      if (!Array.isArray(page?.messages)) throw new ApiError("Older messages could not be loaded. Please try again.", 0);
      const scroller = $("chat-history");
      const previousHeight = scroller.scrollHeight;
      const previousTop = scroller.scrollTop;
      for (const message of page.messages) mergeMessage(chat, message);
      const ids = page.messages.filter(messageIsValid).map((message) => message.id);
      if (ids.length) chat.oldestId = Math.min(before, ...ids);
      chat.hasOlder = Boolean(page.hasMore);
      renderMessages(chat);
      scroller.scrollTop = previousTop + scroller.scrollHeight - previousHeight;
    } catch (error) {
      if (currentChat(chat)) chat.error = error.message;
    } finally {
      chat.olderBusy = false;
      if (currentChat(chat)) renderMessages(chat);
    }
  }

  async function submitMessage(event) {
    event.preventDefault();
    const chat = activeChat();
    if (!chat || !currentChat(chat)) return;
    if (chat.pending) {
      sendPending(chat);
      return;
    }
    const content = $("message-input").value.trim();
    if (!content || content.length > 2000) {
      chat.sendError = "Write a message with 1–2000 characters.";
      updateComposer(chat);
      return;
    }
    chat.pending = { clientMessageId: crypto.randomUUID(), content, status: "ready" };
    chat.draft = content;
    $("message-input").value = content;
    sendPending(chat);
  }

  async function sendPending(chat) {
    if (!currentChat(chat) || !chat.pending || chat.pending.status === "sending") return;
    const pending = chat.pending;
    pending.status = "sending";
    chat.sendError = "";
    renderMessages(chat, { bottom: true });
    try {
      const message = await api(`/api/rooms/${chat.roomId}/messages`, {
        method: "POST", body: { clientMessageId: pending.clientMessageId, content: pending.content },
      });
      if (chat.epoch !== state.authEpoch) return;
      if (!messageIsValid(message)) throw new ApiError("Delivery could not be confirmed. Retry the message to check.", 0);
      mergeMessage(chat, message);
      if (currentChat(chat)) {
        renderMessages(chat, { bottom: true });
        $("message-input").focus();
      }
    } catch (error) {
      if (chat.epoch !== state.authEpoch || chat.pending !== pending) return;
      if (error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429) {
        chat.pending = null;
        chat.sendError = error.message;
      } else {
        pending.status = "failed";
        chat.sendError = "Delivery was not confirmed. Retry the same message to safely check its status.";
      }
      if (currentChat(chat)) renderMessages(chat);
    }
  }

  function updateConnectionStatus() {
    const labels = { connecting: "Connecting…", live: "Live", reconnecting: "Reconnecting…", offline: "Offline" };
    $("connection-status").dataset.state = state.connection;
    $("connection-status").textContent = labels[state.connection] || labels.connecting;
  }

  function subscribeRoom() {
    if (state.socket?.readyState === WebSocket.OPEN && state.selectedId !== null) {
      state.socket.send(JSON.stringify({ type: "subscribe", roomId: state.selectedId }));
    }
  }

  function reconnectSoon() {
    if (!state.token || state.reconnectTimer) return;
    state.connection = navigator.onLine ? "reconnecting" : "offline";
    updateConnectionStatus();
    const delay = Math.min(15000, 750 * (2 ** Math.min(state.reconnectAttempt, 5))) + Math.random() * 400;
    state.reconnectAttempt += 1;
    state.reconnectTimer = setTimeout(() => {
      state.reconnectTimer = null;
      connectRealtime();
    }, delay);
  }

  async function connectRealtime() {
    if (!state.token || state.connecting || state.socket?.readyState === WebSocket.OPEN || state.socket?.readyState === WebSocket.CONNECTING) return;
    clearTimeout(state.reconnectTimer);
    state.reconnectTimer = null;
    if (!navigator.onLine) {
      state.connection = "offline";
      updateConnectionStatus();
      return;
    }
    const epoch = state.authEpoch;
    const socketEpoch = ++state.socketEpoch;
    state.connecting = true;
    state.connection = state.reconnectAttempt ? "reconnecting" : "connecting";
    updateConnectionStatus();
    try {
      const ticket = await api("/api/ws/tickets", { method: "POST" });
      if (epoch !== state.authEpoch || socketEpoch !== state.socketEpoch) return;
      if (typeof ticket?.ticket !== "string" || !ticket.ticket) throw new ApiError("A live connection could not be established.", 0);
      const url = new URL("/ws", window.location.href);
      url.protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
      url.searchParams.set("ticket", ticket.ticket);
      const socket = new WebSocket(url);
      state.socket = socket;
      state.connectTimer = setTimeout(() => socket.close(), 12000);
      const valid = () => epoch === state.authEpoch && socketEpoch === state.socketEpoch && state.socket === socket;
      socket.addEventListener("open", () => {
        if (!valid()) { socket.close(); return; }
        clearTimeout(state.connectTimer);
        state.connecting = false;
        state.reconnectAttempt = 0;
        state.lastActivity = Date.now();
        state.connection = "live";
        updateConnectionStatus();
        subscribeRoom();
        clearInterval(state.pingTimer);
        state.pingTimer = setInterval(() => {
          if (!valid() || socket.readyState !== WebSocket.OPEN) return;
          if (Date.now() - state.lastActivity > 60000) socket.close();
          else socket.send(JSON.stringify({ type: "ping" }));
        }, 25000);
      });
      socket.addEventListener("message", (event) => {
        if (!valid() || typeof event.data !== "string") return;
        state.lastActivity = Date.now();
        let payload;
        try { payload = JSON.parse(event.data); } catch (error) { return; }
        const chat = activeChat();
        if (!chat) return;
        if (payload.type === "subscribed" && String(payload.roomId) === String(chat.roomId)) syncHistory(chat);
        else if (payload.type === "message" && mergeMessage(chat, payload.message)) renderMessages(chat);
        else if (payload.type === "error") {
          chat.error = typeof payload.message === "string" ? payload.message : "The live connection could not be updated.";
          renderMessages(chat);
        }
      });
      socket.addEventListener("close", () => {
        if (!valid()) return;
        clearTimeout(state.connectTimer);
        clearInterval(state.pingTimer);
        state.socket = null;
        state.connecting = false;
        reconnectSoon();
      });
      socket.addEventListener("error", () => {
        if (valid()) state.connection = "reconnecting";
        updateConnectionStatus();
      });
    } catch (error) {
      if (epoch !== state.authEpoch || socketEpoch !== state.socketEpoch) return;
      state.connecting = false;
      reconnectSoon();
    }
  }

  function stopRealtime() {
    state.socketEpoch += 1;
    clearTimeout(state.reconnectTimer);
    clearTimeout(state.connectTimer);
    clearInterval(state.pingTimer);
    clearInterval(state.recoveryTimer);
    state.reconnectTimer = null;
    state.connecting = false;
    if (state.socket) state.socket.close();
    state.socket = null;
    state.reconnectAttempt = 0;
    state.connection = "connecting";
  }

  async function loadRooms() {
    if (!state.token) return;
    const epoch = state.authEpoch;
    const request = ++state.roomRequest;
    state.roomsLoading = true;
    $("refresh-rooms").disabled = true;
    $("workspace-notice").hidden = true;
    if (!state.rooms.length)
      setEmpty(
        "Looking for your next room.",
        "Your workspace is loading.",
        false,
      );
    renderRooms();
    try {
      const rooms = await api("/api/rooms");
      if (epoch !== state.authEpoch || request !== state.roomRequest) return;
      if (!Array.isArray(rooms))
        throw new ApiError(
          "The room list could not be loaded. Please try again.",
          0,
        );
      state.rooms = rooms;
      const selected =
        rooms.find((room) => String(room.id) === String(state.selectedId)) ||
        rooms[0];
      if (selected) selectRoom(selected);
      else {
        state.selectedId = null;
        $("breadcrumb-room").textContent = "Rooms";
        setEmpty(
          "A fresh start.",
          "Create the first public room and give your next idea a home.",
          true,
        );
      }
    } catch (error) {
      if (epoch !== state.authEpoch || request !== state.roomRequest) return;
      $("workspace-notice-text").textContent = error.message;
      $("workspace-notice").hidden = false;
      if (!state.rooms.length)
        setEmpty(
          "Your rooms will be right here.",
          "We couldn't load the workspace. Try again when you're ready.",
          true,
        );
    } finally {
      if (epoch === state.authEpoch && request === state.roomRequest) {
        state.roomsLoading = false;
        $("refresh-rooms").disabled = false;
        renderRooms();
      }
    }
  }

  function openRoomDialog() {
    if (!state.token) return;
    if (state.roomBusy) {
      toast("Your room is still being created. Please wait a moment.");
      return;
    }
    closeSidebar();
    $("room-form").reset();
    showError("room-error", "");
    $("create-room-submit").disabled = false;
    $("room-name").disabled = false;
    $("room-description-input").disabled = false;
    $("create-room-label").textContent = "Create room";
    $("room-dialog").showModal();
    $("room-name").focus();
  }

  async function createRoom(event) {
    event.preventDefault();
    if (state.roomBusy || !state.token) return;
    const name = $("room-name").value.trim();
    const description = $("room-description-input").value.trim();
    if (
      name.length < 3 ||
      name.length > 48 ||
      !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name)
    ) {
      showError(
        "room-error",
        "Use 3–48 lowercase letters, numbers, or single hyphens. Start and end with a letter or number.",
      );
      return;
    }
    if (description.length > 160) {
      showError(
        "room-error",
        "Keep the description to 160 characters or fewer.",
      );
      return;
    }
    const epoch = state.authEpoch;
    state.roomBusy = true;
    $("create-room-submit").disabled = true;
    $("room-name").disabled = true;
    $("room-description-input").disabled = true;
    $("create-room-label").textContent = "Creating your room…";
    showError("room-error", "");
    try {
      const room = await api("/api/rooms", {
        method: "POST",
        body: { name, description },
      });
      if (epoch !== state.authEpoch) return;
      state.roomRequest += 1;
      state.roomsLoading = false;
      $("refresh-rooms").disabled = false;
      state.rooms = [
        ...state.rooms.filter(
          (existing) => String(existing.id) !== String(room.id),
        ),
        room,
      ];
      $("workspace-notice").hidden = true;
      $("room-dialog").close();
      selectRoom(room);
      toast("A little space, all set. Your room is ready.");
    } catch (error) {
      if (epoch === state.authEpoch) showError("room-error", error.message);
    } finally {
      if (epoch === state.authEpoch) {
        state.roomBusy = false;
        $("create-room-submit").disabled = false;
        $("room-name").disabled = false;
        $("room-description-input").disabled = false;
        $("create-room-label").textContent = "Create room";
      }
    }
  }

  function openSidebar() {
    if (!mobile.matches) return;
    $("sidebar").inert = false;
    $("sidebar").classList.add("open");
    $("sidebar-overlay").hidden = false;
    $("open-sidebar").setAttribute("aria-expanded", "true");
    $("main").inert = true;
    $("close-sidebar").focus();
  }

  function closeSidebar() {
    const wasOpen = $("sidebar").classList.contains("open");
    $("sidebar").classList.remove("open");
    $("sidebar-overlay").hidden = true;
    $("open-sidebar").setAttribute("aria-expanded", "false");
    $("main").inert = false;
    $("sidebar").inert = mobile.matches;
    if (wasOpen && mobile.matches) $("open-sidebar").focus();
  }

  $("message-form").addEventListener("submit", submitMessage);
  $("message-input").addEventListener("input", () => {
    const chat = activeChat();
    if (!chat || chat.pending) return;
    chat.draft = $("message-input").value;
    chat.sendError = "";
    updateComposer(chat);
  });
  $("message-input").addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing && !mobile.matches) {
      event.preventDefault();
      $("message-form").requestSubmit();
    }
  });
  $("load-older").addEventListener("click", loadOlder);
  $("retry-history").addEventListener("click", () => {
    if (activeChat()?.errorAction === "older") loadOlder();
    else syncCurrentRoom();
  });
  $("auth-form").addEventListener("submit", submitAuth);
  $("login-tab").addEventListener("click", () => setMode("login"));
  $("register-tab").addEventListener("click", () => setMode("register"));
  document.querySelector(".auth-tabs").addEventListener("keydown", (event) => {
    if (
      state.authBusy ||
      !["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)
    )
      return;
    event.preventDefault();
    setMode(
      event.key === "Home"
        ? "login"
        : event.key === "End"
          ? "register"
          : state.mode === "login"
            ? "register"
            : "login",
      true,
    );
  });
  $("toggle-password").addEventListener("click", () => {
    const visible = $("password").type === "password";
    $("password").type = visible ? "text" : "password";
    $("toggle-password").textContent = visible ? "Hide" : "Show";
    $("toggle-password").setAttribute(
      "aria-label",
      visible ? "Hide password" : "Show password",
    );
    $("toggle-password").setAttribute("aria-pressed", String(visible));
  });
  $("logout").addEventListener("click", () => {
    endSession();
    toast("You're signed out. See you around.");
  });
  ["new-room", "empty-create-room", "another-room"].forEach((id) =>
    $(id).addEventListener("click", openRoomDialog),
  );
  $("room-form").addEventListener("submit", createRoom);
  $("close-dialog").addEventListener("click", () => $("room-dialog").close());
  $("room-dialog").addEventListener("click", (event) => {
    if (event.target !== $("room-dialog")) return;
    const rect = $("room-dialog").getBoundingClientRect();
    if (
      event.clientX < rect.left ||
      event.clientX > rect.right ||
      event.clientY < rect.top ||
      event.clientY > rect.bottom
    )
      $("room-dialog").close();
  });
  $("refresh-rooms").addEventListener("click", loadRooms);
  $("retry-rooms").addEventListener("click", loadRooms);
  $("open-sidebar").addEventListener("click", openSidebar);
  $("close-sidebar").addEventListener("click", closeSidebar);
  $("sidebar-overlay").addEventListener("click", closeSidebar);
  mobile.addEventListener("change", closeSidebar);
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeSidebar();
    if (event.key !== "Tab" || !$("sidebar").classList.contains("open")) return;
    const items = [
      ...$("sidebar").querySelectorAll("a,button:not(:disabled)"),
    ].filter((element) => element.offsetParent !== null);
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });
  window.addEventListener("pageshow", scheduleExpiration);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") {
      scheduleExpiration();
      connectRealtime();
      syncCurrentRoom();
    }
  });

  window.addEventListener("focus", () => {
    scheduleExpiration();
    connectRealtime();
    syncCurrentRoom();
  });
  window.addEventListener("online", () => {
    connectRealtime();
    syncCurrentRoom();
  });
  window.addEventListener("offline", () => {
    state.connection = "offline";
    updateConnectionStatus();
    if (state.socket) state.socket.close();
  });

  async function initialize() {
    closeSidebar();
    let session;
    try {
      session = JSON.parse(sessionStorage.getItem(storageKey) || "null");
    } catch (error) {
      session = null;
    }
    if (!session) return;
    const operation = ++state.authRequest;
    state.authBusy = true;
    updateAuthControls();
    $("auth-submit-label").textContent = "Restoring your session…";
    try {
      await beginSession(session, true);
    } catch (error) {
      endSession(error.message);
    } finally {
      if (operation === state.authRequest) {
        state.authBusy = false;
        updateAuthControls();
      }
    }
  }

  initialize();
})();
