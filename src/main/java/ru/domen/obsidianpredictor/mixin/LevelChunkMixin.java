package ru.domen.obsidianpredictor.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.domen.obsidianpredictor.world.BlockChangeHooks;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    /** Every block change on either side ends up here; returns the old state, or null if nothing changed. */
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void obsidianpredictor$onSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) return;
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (level instanceof ServerLevel serverLevel) {
            BlockChangeHooks.server(serverLevel, pos, state);
        } else {
            BlockChangeHooks.client(level, pos, state);
        }
    }
}
