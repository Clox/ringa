const ENDPOINT = "http://127.0.0.1:8765/message";

const phoneInput = document.getElementById("phoneNumber");
const statusEl = document.getElementById("status");
const sendDialBtn = document.getElementById("sendDialBtn");

function setStatus(text, ok) {
  statusEl.textContent = text;
  statusEl.className = ok ? "ok" : "err";
}

function setButtonsDisabled(disabled) {
  sendDialBtn.disabled = disabled;
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

sendDialBtn.addEventListener("click", sendDialRequest);

phoneInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    sendDialRequest();
  }
});
