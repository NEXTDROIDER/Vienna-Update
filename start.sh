#!/bin/bash

# --- Configuration ---
VIENNA_DIR="./data"          # Directory with JAR files
EVENTBUS_PORT=5532           # Event Bus port
OBJECTSTORE_PORT=5396        # Object Store port
DATA_DIR="$VIENNA_DIR/data"  # Data directory for Object Store

# --- Function to run JAR in background with logging ---
run_jar() {
    local jar_path="$1"
    shift
    echo "Starting $jar_path $* ..."
    nohup java -jar "$jar_path" "$@" > "${jar_path%.jar}.log" 2>&1 &
    local pid=$!
    echo "PID $pid for $jar_path"
    echo $pid
}

# --- Function to wait for port ---
wait_for_port() {
    local host=$1
    local port=$2
    echo "Waiting for $host:$port..."
    until nc -z "$host" "$port"; do
        sleep 1
    done
    echo "$host:$port is available!"
}

# --- 1. Event Bus ---
eventbus_server="$VIENNA_DIR/eventbus-server-0.0.1-SNAPSHOT-jar-with-dependencies.jar"
eventbus_pid=$(run_jar "$eventbus_server")
wait_for_port "localhost" $EVENTBUS_PORT

# --- 2. Object Store ---
objectstore_server="$VIENNA_DIR/objectstore-server-0.0.1-SNAPSHOT-jar-with-dependencies.jar"
objectstore_pid=$(run_jar "$objectstore_server" -dataDir "$DATA_DIR" -port $OBJECTSTORE_PORT)
wait_for_port "localhost" $OBJECTSTORE_PORT

# --- 3. Other JARs ---
find "$VIENNA_DIR" -maxdepth 1 -type f -name "*.jar" | while read -r jar; do
    if [[ "$jar" != "$eventbus_server" && "$jar" != "$objectstore_server" && "$jar" != "$apiserver" ]]; then
        run_jar "$jar"
    fi
done

# --- 4. API Server ---
java -jar server.jar --db ./earth.db --staticData "$DATA_DIR"

echo "All Vienna JAR files have been started in the background!"

bash data/stop.sh