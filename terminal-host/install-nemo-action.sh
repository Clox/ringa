#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_SENDER="$ROOT_DIR/terminal-host/ringa-send-image.py"
ACTION_DIR="$HOME/.local/share/nemo/actions"
ACTION_FILE="$ACTION_DIR/ringa-send-image.nemo_action"

if ! command -v nemo >/dev/null 2>&1; then
  echo "Nemo is not installed; image context menu was not added." >&2
  exit 1
fi

mkdir -p "$ACTION_DIR"
chmod +x "$IMAGE_SENDER"
cat > "$ACTION_FILE" <<EOF
[Nemo Action]
Active=true
Name=Skicka bild till telefonens urklipp
Comment=Skicka den valda bilden via Ringa till Android
Exec=$IMAGE_SENDER %F
Icon-Name=edit-paste
Selection=s
Mimetypes=image/png;image/jpeg;image/gif;image/webp;
UriScheme=file
Dependencies=python3;
EOF

echo "Installed Nemo image action: $ACTION_FILE"
