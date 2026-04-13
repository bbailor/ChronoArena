to run the game:

# 1. Clone this repository using 
    git clone https://github.com/bbailor/ChronoArena.git

# 2. cd into the ChronoArena/ChronoArena/ folder (where you can see src/)
    cd <folder/path/ChronoArena/ChronoArena>

# 3. Compile all files:
    javac -d out src/server/*.java src/gui/*.java src/shared/*.java src/client/*.java

# 4. (If there is already a server running, skip this step) Start a server by running
    On Windows: 
        java -cp out;resource server.Server
    On Mac/Linux: 
        java -cp out:resource server.Server

# 5. Start your client by running
    On Windows:
        java -cp out;resource client.Client
    On Mac/Linux:
        java -cp out:resource client.Client

# 6. Type your chosen username into the name input box, and then the IP of the server in the IP input box.

# 7. Have the host player choose the settings, and begin!
    Controls are SPACE = Freeze (when equipped), WASD = Movement


For making JAR file, run in terminal:
jar cfe Client.jar client.Client -C out . -C resource .
Compiles the jar file with the correct resources.

# Contributions: 
    Lukas: ServerGUI, Game/Weapon Logic
    Aaron: Powerups/Weapon Logic and Handling
    Ben: Server-Client Connections. ClientGUI, Lobby Frame + Logic, Game Logic
    Jackson: ClientGUI, Player color customization/progression 
