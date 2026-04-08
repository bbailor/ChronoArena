to test:
cd into ChronoArena folder

# Terminal 1 (Windows)
java -cp out;resource server.Server
# Terminal 1 (Mac/Linux)
java -cp out:resource server.Server

# Terminal 2 (Windows)
java -cp out;resource client.Client
# Terminal 2 (Mac/Linux)
java -cp out:resource client.Client


Make sure the out folder exists first (mkdir out), and that game.properties is copied into it after compiling (copy game.properties out\ on Windows, cp game.properties out/ on Mac/Linux).

Compile all files:
javac -d out src/shared/Config.java src/shared/Messages.java src/server/GameState.java src/server/GameLoop.java src/server/UdpReceiver.java src/server/ClientManager.java src/server/Server.java src/client/StateCache.java src/client/UdpSender.java src/gui/GameUI.java src/gui/HeadlessUI.java src/gui/CustomUI.java src/gui/SwingUI.java src/client/Client.java

For making JAR file, run in terminal:
jar cfe Client.jar client.Client -C out . -C resource .
Compiles the jar file with the correct resources.