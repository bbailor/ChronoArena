to test:
cd into ChronoArena folder

# Terminal 1
java -cp out server.Server

# Terminal 2
java -cp out client.Client


Make sure the out folder exists first (mkdir out), and that game.properties is copied into it after compiling (copy game.properties out\ on Windows, cp game.properties out/ on Mac/Linux).

Compile all files:
javac -d out src/shared/Config.java src/shared/Messages.java src/server/GameState.java src/server/GameLoop.java src/server/UdpReceiver.java src/server/ClientManager.java src/server/Server.java src/client/StateCache.java src/client/UdpSender.java src/gui/GameUI.java src/gui/HeadlessUI.java src/gui/CustomUI.java src/client/Client.java
