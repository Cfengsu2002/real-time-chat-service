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
        "Could not reach Relay. Check your connection and try again.",
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
        : "Sign in to Relay";
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
    await loadRooms();
  }

  function endSession(message = "") {
    state.authEpoch += 1;
    state.authRequest += 1;
    state.roomRequest += 1;
    clearTimeout(state.expirationTimer);
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
    $("account-name").textContent = "Welcome to Relay";
    $("account-avatar").textContent = "R";
    $("account-detail").textContent = "Your next idea belongs here.";
    $("breadcrumb-room").textContent = "Welcome";
    $("room-count").textContent = "0 rooms";
    $("room-detail").hidden = true;
    $("workspace-notice").hidden = true;
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

  function selectRoom(room) {
    if (!state.token) return;
    state.selectedId = room.id;
    $("room-title").textContent = room.name;
    $("room-name-value").textContent = `#${room.name}`;
    $("room-description").textContent =
      room.description || "No description yet. A little space for a new idea.";
    $("breadcrumb-room").textContent = room.name;
    const created = new Date(room.createdAt);
    const validDate = Number.isFinite(created.getTime());
    $("room-created").textContent = validDate
      ? new Intl.DateTimeFormat("en", {
          month: "short",
          day: "numeric",
          year: "numeric",
          hour: "numeric",
          minute: "2-digit",
        }).format(created)
      : "Not available";
    if (validDate) $("room-created").dateTime = created.toISOString();
    else $("room-created").removeAttribute("datetime");
    $("room-detail").hidden = false;
    $("empty-state").hidden = true;
    renderRooms();
    closeSidebar();
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
    if (document.visibilityState === "visible") scheduleExpiration();
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
