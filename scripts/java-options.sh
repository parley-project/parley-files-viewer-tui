# Sourced by pfv and pfv-lanterna, after they set MODULE: finds that module's -all jar (building
# it if it isn't there) and the JVM options.
BIN_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_DIR=$(dirname "$BIN_DIR")
JAR=$(ls "$PROJECT_DIR/$MODULE"/target/$MODULE-*-all.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
	(cd "$PROJECT_DIR" && mvn -q package -DskipTests) || exit 1
	JAR=$(ls "$PROJECT_DIR/$MODULE"/target/$MODULE-*-all.jar | head -1)
fi

# JNA (parley-term's terminal handling) needs native access on Java 17 and later
JAVA_OPTS=""
java_major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [ -n "$java_major" ] && [ "$java_major" -ge 17 ]; then
	JAVA_OPTS="--enable-native-access=ALL-UNNAMED"
fi
