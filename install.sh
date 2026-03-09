#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HANDLER_SCRIPT="$ROOT_DIR/terminal-host/ringa-tel-handler.sh"
DESKTOP_ID="ringa-tel-handler.desktop"
APP_DIR="$HOME/.local/share/applications"
DESKTOP_FILE="$APP_DIR/$DESKTOP_ID"
CONFIG_DIR="$HOME/.config/ringa"
PREV_HANDLER_FILE="$CONFIG_DIR/previous-tel-handler"

if [[ ! -f "$HANDLER_SCRIPT" ]]; then
  echo "Missing handler script: $HANDLER_SCRIPT" >&2
  exit 1
fi

mkdir -p "$APP_DIR" "$CONFIG_DIR"
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
Comment=Forward tel: links to local Ringa terminal host
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
echo "Tip: run your terminal host on 127.0.0.1:8765 before clicking tel: links."
