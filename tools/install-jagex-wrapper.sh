#!/bin/sh
# Routes the Jagex Launcher's "Play" for RuneLite through the RLTX dev client.
#
# RuneLite disables developer mode (and with it plugin sideloading) whenever it is started by
# the RuneLite launcher, so the only way to run this plugin with a Jagex account is to have the
# Jagex Launcher start our own client. The launcher spawns games/runelite/RuneLite.AppImage with
# the JX_* login variables in the environment and only re-downloads that file when it is
# missing or after an install-layout migration. This keeps the original as
# RuneLite.AppImage.stock and puts a wrapper in its place.
#
# Create ~/.runelite/rltx-use-stock to play the unmodified client without uninstalling.
# To uninstall: delete RuneLite.AppImage and rename RuneLite.AppImage.stock back.
set -eu

GAMES="$HOME/.local/share/Jagex Launcher/games/runelite"
TARGET="$GAMES/RuneLite.AppImage"
STOCK="$GAMES/RuneLite.AppImage.stock"
CLIENT="$(cd "$(dirname "$0")/.." && pwd)/build/rltx-client.sh"

[ -x "$CLIENT" ] || { echo "missing $CLIENT; run ./gradlew launchScript first" >&2; exit 1; }
[ -e "$TARGET" ] || { echo "missing $TARGET; install RuneLite from the Jagex Launcher first" >&2; exit 1; }

if head -c 2 "$TARGET" | grep -q '#!'; then
	echo "wrapper already installed at $TARGET"
else
	mv "$TARGET" "$STOCK"
	echo "kept original as $STOCK"
fi

cat > "$TARGET" <<WRAPPER
#!/bin/sh
# Installed by RLTX. See tools/install-jagex-wrapper.sh in the RLTX repo.
DIR="\$(cd "\$(dirname "\$0")" && pwd)"
if [ -e "\$HOME/.runelite/rltx-use-stock" ] || [ ! -x "$CLIENT" ]; then
	exec "\$DIR/RuneLite.AppImage.stock" "\$@"
fi
exec "$CLIENT" "\$@"
WRAPPER
chmod 755 "$TARGET"
echo "wrapper installed at $TARGET"
