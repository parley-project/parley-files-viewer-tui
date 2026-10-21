# Sourced by pfv and pfsh: finds the jar and the JVM options.
BIN_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_DIR=$(dirname "$BIN_DIR")
JAR=$(ls "$PROJECT_DIR"/target/parley-files-viewer-tui-*-all.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
	(cd "$PROJECT_DIR" && mvn -q package -DskipTests) || exit 1
	JAR=$(ls "$PROJECT_DIR"/target/parley-files-viewer-tui-*-all.jar | head -1)
fi

# JNA (fsh's terminal handling) needs native access on Java 17 and later
JAVA_OPTS=""
java_major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [ -n "$java_major" ] && [ "$java_major" -ge 17 ]; then
	JAVA_OPTS="--enable-native-access=ALL-UNNAMED"
fi
# fsh's own native library (libnativekeyboard) and fshexec, if you have them: the folder they're
# in, such as your parley-fsh/macos. Without it fsh uses JNA, which covers nearly everything.
if [ -n "$FSH_LIBDIR" ]; then
	JAVA_OPTS="$JAVA_OPTS -Djava.library.path=$FSH_LIBDIR"
fi
