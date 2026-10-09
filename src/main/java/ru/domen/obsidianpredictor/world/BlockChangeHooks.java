package ru.domen.obsidianpredictor.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import ru.domen.obsidianpredictor.server.ServerJobManager;

/** Called from the chunk mixin on every block change. */
public final class BlockChangeHooks {
    public interface ClientListener {
        void onBlockChanged(Level level, BlockPos pos, BlockState state);
    }

    /** Set by the client entrypoint; null on dedicated servers. */
    public static volatile ClientListener clientListener;

    private BlockChangeHooks() {}

    public static void server(ServerLevel level, BlockPos pos, BlockState state) {
        ServerJobManager.onBlockChanged(level, pos, state);
    }

    public static void client(Level level, BlockPos pos, BlockState state) {
        ClientListener listener = clientListener;
        if (listener != null) {
            listener.onBlockChanged(level, pos, state);
        }
    }
}
