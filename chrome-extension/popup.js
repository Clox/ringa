const ENDPOINT = "http://127.0.0.1:8765/message";

const messageInput = document.getElementById("message");
const statusEl = document.getElementById("status");
const sendBtn = document.getElementById("sendBtn");

function setStatus(text, ok) {
  statusEl.textContent = text;
  statusEl.className = ok ? "ok" : "err";
}

async function sendMessage() {
  const message = messageInput.value.trim();
  if (!message) {
    setStatus("Enter a message first.", false);
    return;
  }

  sendBtn.disabled = true;
  setStatus("Sending...", true);

  const payload = {
    message,
    source: "chrome-extension",
    sent_at: new Date().toISOString()
  };

  try {
    const response = await fetch(ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    setStatus("Message sent.", true);
    messageInput.value = "";
  } catch (error) {
    const detail = error instanceof Error ? error.message : "unknown error";
    setStatus(`Failed to send: ${detail}`, false);
  } finally {
    sendBtn.disabled = false;
  }
}

sendBtn.addEventListener("click", sendMessage);
messageInput.addEventListener("keydown", (event) => {
  if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
    sendMessage();
  }
});
