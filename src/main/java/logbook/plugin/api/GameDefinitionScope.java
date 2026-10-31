package logbook.plugin.api;

/** A thread-confined, nested operation lease; close in reverse order on the creating thread. */
public interface GameDefinitionScope extends AutoCloseable {
    GameDefinitionSnapshot snapshot();

    @Override
    void close();
}
