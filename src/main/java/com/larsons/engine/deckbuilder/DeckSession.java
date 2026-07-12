package com.larsons.engine.deckbuilder;

/**
 * An active Council of Six session, carried from the lobby scene into the
 * game scene: the connected {@link DeckClient}, plus the embedded
 * {@link DeckServer} when this player is the host — the deckbuilder twin of
 * {@link com.larsons.engine.autobattler.AutoSession}.
 */
public final class DeckSession implements AutoCloseable {

    private final DeckClient client;
    private final DeckServer hostedServer; // null when joining someone else's game

    public DeckSession(DeckClient client, DeckServer hostedServer) {
        this.client = client;
        this.hostedServer = hostedServer;
    }

    public DeckClient client() {
        return client;
    }

    /** True if this player is hosting the game others are connected to. */
    public boolean isHost() {
        return hostedServer != null;
    }

    public DeckServer hostedServer() {
        return hostedServer;
    }

    /** Disconnect, and shut the integrated server down when hosting. */
    @Override
    public void close() {
        client.close();
        if (hostedServer != null) hostedServer.stop();
    }
}
