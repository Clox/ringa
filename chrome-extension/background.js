const ENDPOINT = "http://127.0.0.1:8765/message";
const DIAL_MENU_ID = "ringa-dial-selection";
const CLIPBOARD_MENU_ID = "ringa-clipboard-selection";
const DIAL_MENU_TITLE = "Dial via Ringa";
const CLIPBOARD_MENU_TITLE = "Send selection to phone clipboard";

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

function buildDialPayload(dialNumber, info, tab) {
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

function buildClipboardPayload(selectionText, info, tab) {
  return {
    source: "chrome-extension",
    sent_at: new Date().toISOString(),
    request_type: "clipboard",
    clipboard_text: selectionText,
    trigger: "selection_context_menu",
    page_url: info.pageUrl || tab?.url || null
  };
}

function describeSendError(error) {
  const detail = error instanceof Error ? error.message : String(error);
  if (
    detail === "Failed to fetch" ||
    detail.includes("NetworkError") ||
    detail.includes("TypeError")
  ) {
    return `Ringa host is not running on this computer. Start the local host with ./install.sh, or make sure the service on ${ENDPOINT} is running.`;
  }
  return detail;
}

async function ensureContextMenu() {
  let settings;
  try {
    settings = await chrome.storage.local.get({
      dialContextMenu: true,
      clipboardContextMenu: true
    });
  } catch (error) {
    console.error("Failed to load context menu settings:", error);
    return;
  }

  try {
    await chrome.contextMenus.removeAll();
    if (settings.dialContextMenu !== false) {
      await createContextMenu(DIAL_MENU_ID, DIAL_MENU_TITLE);
    }
    if (settings.clipboardContextMenu !== false) {
      await createContextMenu(CLIPBOARD_MENU_ID, CLIPBOARD_MENU_TITLE);
    }
  } catch (error) {
    console.error("Failed to update context menu:", error);
  }
}

function createContextMenu(id, title) {
  return new Promise((resolve, reject) => {
    chrome.contextMenus.create({ id, title, contexts: ["selection"] }, () => {
      if (chrome.runtime.lastError) {
        reject(new Error(chrome.runtime.lastError.message));
      } else {
        resolve();
      }
    });
  });
}

async function postPayload(payload) {
  const response = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  if (payload.request_type === "clipboard") {
    const result = await response.json();
    if (result.forwarded !== true) {
      throw new Error(result.forward_detail || "The phone did not receive the request.");
    }
  }
}

let contextMenuUpdate = Promise.resolve();

function queueContextMenuUpdate() {
  contextMenuUpdate = contextMenuUpdate.then(ensureContextMenu);
}

chrome.runtime.onInstalled.addListener(queueContextMenuUpdate);

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "local") return;
  if (changes.dialContextMenu || changes.clipboardContextMenu) {
    queueContextMenuUpdate();
  }
});

if (chrome.contextMenus && chrome.contextMenus.onClicked && typeof chrome.contextMenus.onClicked.addListener === "function") {
  chrome.contextMenus.onClicked.addListener(async (info, tab) => {
    let payload;
    if (info.menuItemId === DIAL_MENU_ID) {
      const dialNumber = normalizeDialNumber(info.selectionText);
      if (!dialNumber) return;
      payload = buildDialPayload(dialNumber, info, tab);
    } else if (info.menuItemId === CLIPBOARD_MENU_ID) {
      if (!info.selectionText?.trim()) return;
      payload = buildClipboardPayload(info.selectionText, info, tab);
    } else {
      return;
    }
    try {
      await postPayload(payload);
    } catch (error) {
      const detail = describeSendError(error);
      console.error("Failed to send Ringa request from context menu:", detail);
    }
  });
}
