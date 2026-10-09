package ru.domen.obsidianpredictor.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public interface ChunkMapInvoker {
    /** Reads chunk NBT from disk (or pending writes) and upgrades old data versions, off-thread. */
    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> obsidianpredictor$readChunk(ChunkPos pos);
}
