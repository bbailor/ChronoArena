to test:
cd into ChronoArena folder (where you can see src/)

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
javac -d out src/server/*.java src/gui/*.java src/shared/*.java src/client/*.java

Then run the command above to run your client with the correct IP, and set your unique name.

For making JAR file, run in terminal:
jar cfe Client.jar client.Client -C out . -C resource .
Compiles the jar file with the correct resources.