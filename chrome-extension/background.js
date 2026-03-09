const ENDPOINT = "http://127.0.0.1:8765/message";
const MENU_ID = "ringa-dial-selection";
const MENU_TITLE = "Dial via Ringa";
let menuReady = false;

function normalizeDialNumber(selectionText) {
  if (!selectionText) {
    return null;
  }

  let text = String(selectionText).trim();
  if (!text) {
    return null;
  }

  text = text.replace(/^tel:\s*/i, "");

  const compact = text.replace(/[\s\u00A0\-‐‑‒–—―().,/\\]/g, "");
  if (!compact) {
    return null;
  }

  if (!/^\+?\d+$/.test(compact)) {
    return null;
  }

  const digitsOnly = compact.replace(/\D/g, "");
  if (digitsOnly.length < 3) {
    return null;
  }

  return compact;
}

function buildPayload(dialNumber, info, tab) {
  return {
    source: "chrome-extension",
    sent_at: new Date().toISOString(),
    request_type: "dial",
    dial_number: dialNumber,
    trigger: "selection_context_menu",
    selection_raw: info.selectionText || "",
    page_url: info.pageUrl || tab?.url || null
  };
}

function hasContextMenusApi() {
  return Boolean(chrome.contextMenus && typeof chrome.contextMenus.create === "function");
}

function supportsOnShown() {
  return Boolean(
    chrome.contextMenus &&
      chrome.contextMenus.onShown &&
      typeof chrome.contextMenus.onShown.addListener === "function"
  );
}

function refreshContextMenusSafe() {
  if (chrome.contextMenus && typeof chrome.contextMenus.refresh === "function") {
    chrome.contextMenus.refresh();
  }
}

function ensureContextMenu() {
  if (!hasContextMenusApi()) {
    return;
  }

  menuReady = false;
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_ID,
      title: MENU_TITLE,
      contexts: ["selection"],
      visible: !supportsOnShown()
    }, () => {
      if (chrome.runtime.lastError) {
        console.warn("Failed to create context menu:", chrome.runtime.lastError.message);
        return;
      }
      menuReady = true;
    });
  });
}

async function postDialPayload(payload) {
  const response = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }
}

if (chrome.runtime && chrome.runtime.onInstalled && typeof chrome.runtime.onInstalled.addListener === "function") {
  chrome.runtime.onInstalled.addListener(() => {
    ensureContextMenu();
  });
}

if (chrome.runtime && chrome.runtime.onStartup && typeof chrome.runtime.onStartup.addListener === "function") {
  chrome.runtime.onStartup.addListener(() => {
    ensureContextMenu();
  });
}

if (supportsOnShown()) {
  chrome.contextMenus.onShown.addListener((info) => {
    if (!menuReady) {
      return;
    }

    const dialNumber = normalizeDialNumber(info.selectionText);
    const isVisible = Boolean(dialNumber);
    const title = isVisible ? `${MENU_TITLE}: ${dialNumber}` : MENU_TITLE;

    chrome.contextMenus.update(MENU_ID, { visible: isVisible, title }, () => {
      if (chrome.runtime.lastError) {
        return;
      }
      refreshContextMenusSafe();
    });
  });
}

if (chrome.contextMenus && chrome.contextMenus.onClicked && typeof chrome.contextMenus.onClicked.addListener === "function") {
  chrome.contextMenus.onClicked.addListener(async (info, tab) => {
    if (info.menuItemId !== MENU_ID) {
      return;
    }

    const dialNumber = normalizeDialNumber(info.selectionText);
    if (!dialNumber) {
      return;
    }

    const payload = buildPayload(dialNumber, info, tab);
    try {
      await postDialPayload(payload);
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      console.error("Failed to send dial request from context menu:", detail);
    }
  });
}

ensureContextMenu();
