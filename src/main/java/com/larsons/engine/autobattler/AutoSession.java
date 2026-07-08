package com.larsons.engine.autobattler;

/**
 * An active auto-battler session, carried from the lobby scene into the game
 * scene: the connected {@link AutoClient}, plus the embedded {@link AutoServer}
 * when this player is the host — the auto-battler twin of the world game's
 * {@link com.larsons.engine.net.NetSession}.
 */
public final class AutoSession implements AutoCloseable {

    private final AutoClient client;
    private final AutoServer hostedServer; // null when joining someone else's game

    public AutoSession(AutoClient client, AutoServer hostedServer) {
        this.client = client;
        this.hostedServer = hostedServer;
    }

    public AutoClient client() {
        return client;
    }

    /** True if this player is hosting the game others are connected to. */
    public boolean isHost() {
        return hostedServer != null;
    }

    public AutoServer hostedServer() {
        return hostedServer;
    }

    /** Disconnect, and shut the integrated server down when hosting. */
    @Override
    public void close() {
        client.close();
        if (hostedServer != null) hostedServer.stop();
    }
}
