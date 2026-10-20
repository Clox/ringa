const ENDPOINT = "http://127.0.0.1:8765/message";

const phoneInput = document.getElementById("phoneNumber");
const clipboardInput = document.getElementById("clipboardText");
const statusEl = document.getElementById("status");
const sendDialBtn = document.getElementById("sendDialBtn");
const sendClipboardBtn = document.getElementById("sendClipboardBtn");
const dialContextMenu = document.getElementById("dialContextMenu");
const clipboardContextMenu = document.getElementById("clipboardContextMenu");
const imageContextMenu = document.getElementById("imageContextMenu");

function setStatus(text, ok) {
  statusEl.textContent = text;
  statusEl.className = ok ? "ok" : "err";
}

function setButtonsDisabled(disabled) {
  sendDialBtn.disabled = disabled;
  sendClipboardBtn.disabled = disabled;
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

function basePayload() {
  return {
    source: "chrome-extension",
    sent_at: new Date().toISOString()
  };
}

async function postPayload(payload, successText) {
  setButtonsDisabled(true);
  setStatus("Sending...", true);

  try {
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

    setStatus(successText, true);
  } catch (error) {
    const detail = describeSendError(error);
    setStatus(`Failed to send: ${detail}`, false);
  } finally {
    setButtonsDisabled(false);
  }
}

async function sendDialRequest() {
  const dialNumber = phoneInput.value.trim();
  if (!dialNumber) {
    setStatus("Enter a phone number first.", false);
    return;
  }

  const payload = {
    ...basePayload(),
    request_type: "dial",
    dial_number: dialNumber
  };

  await postPayload(payload, "Dial request sent.");
}

async function sendClipboardRequest() {
  const clipboardText = clipboardInput.value;
  if (!clipboardText.trim()) {
    setStatus("Paste text into the clipboard field first.", false);
    return;
  }

  await postPayload({
    ...basePayload(),
    request_type: "clipboard",
    clipboard_text: clipboardText,
    trigger: "extension_popup"
  }, "Text sent to phone clipboard.");
}

async function loadContextMenuSettings() {
  try {
    const settings = await chrome.storage.local.get({
      dialContextMenu: true,
      clipboardContextMenu: true,
      imageContextMenu: true,
      lastImageTransfer: null
    });
    dialContextMenu.checked = settings.dialContextMenu !== false;
    clipboardContextMenu.checked = settings.clipboardContextMenu !== false;
    imageContextMenu.checked = settings.imageContextMenu !== false;
    dialContextMenu.disabled = false;
    clipboardContextMenu.disabled = false;
    imageContextMenu.disabled = false;
    if (settings.lastImageTransfer) {
      setStatus(settings.lastImageTransfer.message, settings.lastImageTransfer.ok);
      await chrome.action.setBadgeText({ text: "" });
      await chrome.storage.local.remove("lastImageTransfer");
    }
  } catch (error) {
    setStatus(`Could not load menu settings: ${error.message}`, false);
  }
}

async function saveContextMenuSetting(event) {
  const toggle = event.currentTarget;
  const enabled = toggle.checked;
  toggle.disabled = true;
  try {
    await chrome.storage.local.set({ [toggle.id]: enabled });
  } catch (error) {
    toggle.checked = !enabled;
    setStatus(`Could not save menu setting: ${error.message}`, false);
  } finally {
    toggle.disabled = false;
  }
}

sendDialBtn.addEventListener("click", sendDialRequest);
sendClipboardBtn.addEventListener("click", sendClipboardRequest);
dialContextMenu.addEventListener("change", saveContextMenuSetting);
clipboardContextMenu.addEventListener("change", saveContextMenuSetting);
imageContextMenu.addEventListener("change", saveContextMenuSetting);
loadContextMenuSettings();

phoneInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    sendDialRequest();
  }
});
