package com.larsons.engine.net;

/**
 * The pieces of an active multiplayer session, carried by the game context
 * from the multiplayer menu into the play scene: the connected client, plus
 * the embedded {@link GameServer} when this player is the host (an integrated
 * server, like a Minecraft world opened to others).
 */
public final class NetSession implements AutoCloseable {

    private final GameClient client;
    private final GameServer hostedServer; // null when joining someone else's server

    public NetSession(GameClient client, GameServer hostedServer) {
        this.client = client;
        this.hostedServer = hostedServer;
    }

    public GameClient client() {
        return client;
    }

    /** True if this player is hosting the server others are connected to. */
    public boolean isHost() {
        return hostedServer != null;
    }

    /** The embedded server when hosting, or {@code null}. */
    public GameServer hostedServer() {
        return hostedServer;
    }

    /** Disconnect, and shut the integrated server down when hosting. */
    @Override
    public void close() {
        client.close();
        if (hostedServer != null) hostedServer.stop();
    }
}
