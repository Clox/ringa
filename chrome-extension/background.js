const ENDPOINT = "http://127.0.0.1:8765/message";
const DIAL_MENU_ID = "ringa-dial-selection";
const CLIPBOARD_MENU_ID = "ringa-clipboard-selection";
const IMAGE_MENU_ID = "ringa-clipboard-image";
const DIAL_MENU_TITLE = "Dial via Ringa";
const CLIPBOARD_MENU_TITLE = "Send selection to phone clipboard";
const IMAGE_MENU_TITLE = "Send image to phone clipboard";
const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
const IMAGE_MIME_TYPES = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);

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

async function buildImagePayload(info, tab) {
  if (!info.srcUrl) {
    throw new Error("The image has no source URL.");
  }

  const imageUrl = new URL(info.srcUrl);
  if (imageUrl.protocol === "http:" || imageUrl.protocol === "https:") {
    const imageHostPattern = `${imageUrl.protocol}//${imageUrl.hostname}/*`;
    const granted = await chrome.permissions.request({ origins: [imageHostPattern] });
    if (!granted) {
      throw new Error("Permission to read the image was not granted.");
    }
  } else if (imageUrl.protocol !== "data:") {
    throw new Error("This image source is not supported. Use an HTTP, HTTPS, or data image.");
  }

  const response = await fetch(info.srcUrl, { credentials: "include" });
  if (!response.ok) {
    throw new Error(`Could not read image: HTTP ${response.status}`);
  }

  const mimeType = response.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase();
  if (!IMAGE_MIME_TYPES.has(mimeType)) {
    throw new Error(`Unsupported image type: ${mimeType || "unknown"}`);
  }

  const reportedLength = Number(response.headers.get("Content-Length"));
  if (reportedLength > MAX_IMAGE_BYTES) {
    throw new Error("Image exceeds the 4 MB limit.");
  }

  const chunks = [];
  let length = 0;
  const reader = response.body.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.length;
    if (length > MAX_IMAGE_BYTES) {
      await reader.cancel();
      throw new Error("Image exceeds the 4 MB limit.");
    }
    chunks.push(value);
  }
  if (!length) {
    throw new Error("The image is empty.");
  }

  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }

  return {
    source: "chrome-extension",
    sent_at: new Date().toISOString(),
    request_type: "clipboard_image",
    image_mime_type: mimeType,
    image_data_b64: btoa(binary),
    trigger: "image_context_menu",
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
      clipboardContextMenu: true,
      imageContextMenu: true
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
    if (settings.imageContextMenu !== false) {
      await createContextMenu(IMAGE_MENU_ID, IMAGE_MENU_TITLE, "image");
    }
  } catch (error) {
    console.error("Failed to update context menu:", error);
  }
}

function createContextMenu(id, title, context = "selection") {
  return new Promise((resolve, reject) => {
    chrome.contextMenus.create({ id, title, contexts: [context] }, () => {
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

  if (payload.request_type === "clipboard" || payload.request_type === "clipboard_image") {
    const result = await response.json();
    if (result.forwarded !== true) {
      throw new Error(result.forward_detail || "The phone did not receive the request.");
    }
  }
}

async function reportImageResult(ok, message) {
  await chrome.storage.local.set({ lastImageTransfer: { ok, message } });
  await chrome.action.setBadgeBackgroundColor({ color: ok ? "#278540" : "#c53929" });
  await chrome.action.setBadgeText({ text: ok ? "✓" : "!" });
}

let contextMenuUpdate = Promise.resolve();

function queueContextMenuUpdate() {
  contextMenuUpdate = contextMenuUpdate.then(ensureContextMenu);
}

chrome.runtime.onInstalled.addListener(queueContextMenuUpdate);

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "local") return;
  if (changes.dialContextMenu || changes.clipboardContextMenu || changes.imageContextMenu) {
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
    } else if (info.menuItemId === IMAGE_MENU_ID) {
      try {
        payload = await buildImagePayload(info, tab);
      } catch (error) {
        console.error("Failed to read image for Ringa:", error);
        await reportImageResult(false, error instanceof Error ? error.message : String(error));
        return;
      }
    } else {
      return;
    }
    try {
      await postPayload(payload);
      if (info.menuItemId === IMAGE_MENU_ID) {
        await reportImageResult(true, "Image sent to phone clipboard.");
      }
    } catch (error) {
      const detail = describeSendError(error);
      console.error("Failed to send Ringa request from context menu:", detail);
      if (info.menuItemId === IMAGE_MENU_ID) {
        await reportImageResult(false, detail);
      }
    }
  });
}
