#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HANDLER_SCRIPT="$ROOT_DIR/terminal-host/ringa-tel-handler.sh"
SERVER_SCRIPT="$ROOT_DIR/terminal-host/server.py"
REQUIREMENTS_FILE="$ROOT_DIR/terminal-host/requirements.txt"
VENV_DIR="$ROOT_DIR/.venv"
PYTHON_BIN="$VENV_DIR/bin/python"
DESKTOP_ID="ringa-tel-handler.desktop"
APP_DIR="$HOME/.local/share/applications"
DESKTOP_FILE="$APP_DIR/$DESKTOP_ID"
CONFIG_DIR="$HOME/.config/ringa"
PREV_HANDLER_FILE="$CONFIG_DIR/previous-tel-handler"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
SERVICE_NAME="ringa-host.service"
SERVICE_FILE="$SYSTEMD_USER_DIR/$SERVICE_NAME"

if [[ ! -f "$HANDLER_SCRIPT" ]]; then
  echo "Missing handler script: $HANDLER_SCRIPT" >&2
  exit 1
fi

mkdir -p "$APP_DIR" "$CONFIG_DIR" "$SYSTEMD_USER_DIR"
chmod +x "$HANDLER_SCRIPT"

CURRENT_DEFAULT="$(xdg-mime query default x-scheme-handler/tel 2>/dev/null || true)"
if [[ -n "$CURRENT_DEFAULT" && "$CURRENT_DEFAULT" != "$DESKTOP_ID" ]]; then
  printf '%s\n' "$CURRENT_DEFAULT" > "$PREV_HANDLER_FILE"
fi

cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Ringa Tel Handler
Comment=Forward tel: links to local Ringa host
Exec=$HANDLER_SCRIPT %u
Terminal=false
NoDisplay=true
MimeType=x-scheme-handler/tel;
Categories=Network;
EOF

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APP_DIR" >/dev/null 2>&1 || true
fi

echo "Installed custom tel handler desktop entry:"
echo "  $DESKTOP_FILE"
echo
echo "Current default tel handler:"
echo "  ${CURRENT_DEFAULT:-<none>}"
echo
if [[ "$CURRENT_DEFAULT" == "$DESKTOP_ID" ]]; then
  echo "Ringa Tel Handler is already the default for tel: links."
else
  read -r -p "Set Ringa Tel Handler as default for tel: links? [y/N] " answer
  case "$answer" in
    y|Y|yes|YES)
      xdg-mime default "$DESKTOP_ID" x-scheme-handler/tel
      NEW_DEFAULT="$(xdg-mime query default x-scheme-handler/tel 2>/dev/null || true)"
      echo "Updated default tel handler:"
      echo "  ${NEW_DEFAULT:-<none>}"
      ;;
    *)
      echo "Default tel handler unchanged."
      ;;
  esac
fi

echo
echo "Tip: run your Ringa host on 127.0.0.1:8765 before clicking tel: links."

echo
read -r -p "Install/update Ringa host as a background service? [y/N] " service_answer
case "$service_answer" in
  y|Y|yes|YES)
    if ! command -v systemctl >/dev/null 2>&1; then
      echo "systemctl not found. Cannot install background service." >&2
      exit 1
    fi
    if ! command -v python3 >/dev/null 2>&1; then
      echo "python3 not found. Cannot install background service." >&2
      exit 1
    fi
    if [[ ! -f "$SERVER_SCRIPT" ]]; then
      echo "Missing server script: $SERVER_SCRIPT" >&2
      exit 1
    fi

    if [[ ! -d "$VENV_DIR" ]]; then
      echo "Creating Python virtual environment at $VENV_DIR"
      python3 -m venv "$VENV_DIR"
    fi

    echo "Installing/updating Python dependencies"
    "$PYTHON_BIN" -m pip install -r "$REQUIREMENTS_FILE"

    cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Ringa host server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$ROOT_DIR
ExecStart=$PYTHON_BIN $SERVER_SCRIPT --host 127.0.0.1 --port 8765 --auto-discover
Restart=always
RestartSec=2
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
EOF

    echo "Installed/updated systemd user service:"
    echo "  $SERVICE_FILE"

    if ! systemctl --user daemon-reload; then
      echo
      echo "Failed to talk to systemd user manager." >&2
      echo "Try logging in with a desktop session, then rerun ./install.sh." >&2
      exit 1
    fi

    systemctl --user enable --now "$SERVICE_NAME"
    systemctl --user restart "$SERVICE_NAME"
    SERVICE_STATUS="$(systemctl --user is-active "$SERVICE_NAME" 2>/dev/null || true)"
    echo "Service status: ${SERVICE_STATUS:-unknown}"

    echo
    read -r -p "Try enabling linger so service can auto-start after reboot before login? [y/N] " linger_answer
    case "$linger_answer" in
      y|Y|yes|YES)
        if command -v loginctl >/dev/null 2>&1; then
          if loginctl enable-linger "$USER"; then
            echo "Lingering enabled for user $USER."
          else
            echo "Could not enable lingering automatically." >&2
            echo "If needed, run: sudo loginctl enable-linger $USER" >&2
          fi
        else
          echo "loginctl not found; skipping linger setup."
        fi
        ;;
      *)
        echo "Linger setup skipped."
        ;;
    esac

    echo
    echo "Use these commands to inspect service health:"
    echo "  systemctl --user status $SERVICE_NAME"
    echo "  journalctl --user -u $SERVICE_NAME -f"
    ;;
  *)
    echo "Background service setup skipped."
    ;;
esac
