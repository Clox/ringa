const ENDPOINT = "http://127.0.0.1:8765/message";

const messageInput = document.getElementById("message");
const phoneInput = document.getElementById("phoneNumber");
const statusEl = document.getElementById("status");
const sendBtn = document.getElementById("sendBtn");
const sendDialBtn = document.getElementById("sendDialBtn");

function setStatus(text, ok) {
  statusEl.textContent = text;
  statusEl.className = ok ? "ok" : "err";
}

function setButtonsDisabled(disabled) {
  sendBtn.disabled = disabled;
  sendDialBtn.disabled = disabled;
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

    setStatus(successText, true);
  } catch (error) {
    const detail = error instanceof Error ? error.message : "unknown error";
    setStatus(`Failed to send: ${detail}`, false);
  } finally {
    setButtonsDisabled(false);
  }
}

async function sendMessage() {
  const message = messageInput.value.trim();
  if (!message) {
    setStatus("Enter a message first.", false);
    return;
  }

  const payload = {
    ...basePayload(),
    message
  };

  await postPayload(payload, "Message sent.");
  messageInput.value = "";
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

sendBtn.addEventListener("click", sendMessage);
sendDialBtn.addEventListener("click", sendDialRequest);

messageInput.addEventListener("keydown", (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
    sendMessage();
  }
});

phoneInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    sendDialRequest();
  }
});
