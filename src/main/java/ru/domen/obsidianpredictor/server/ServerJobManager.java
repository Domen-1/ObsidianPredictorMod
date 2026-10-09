package ru.domen.obsidianpredictor.server;

import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.state.BlockState;
import ru.domen.obsidianpredictor.ObsidianPredictorMod;
import ru.domen.obsidianpredictor.network.Payloads;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ServerJobManager {
    private static final Map<Long, ServerJob> JOBS = new LinkedHashMap<>();
    /** Read by the block change hook on every block update; avoids touching the map when idle. */
    private static volatile boolean hasJobs;
    private static long nextId = 1;

    private ServerJobManager() {}

    public static void registerNetworking() {
        // Clients announce their channels after joining; greet the ones that have the mod.
        ClientboundPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> {
            if (channels.contains(Payloads.Hello.TYPE.id())) {
                sender.sendPacket(new Payloads.Hello(Payloads.PROTOCOL_VERSION, ObsidianPredictorMod.version()));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Payloads.StartJob.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            String error = start(player.level(), player, payload.name(), payload.min(), payload.max());
            if (error != null) {
                ServerPlayNetworking.send(player, new Payloads.JobClosed(-1, error));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Payloads.JobAction.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerJob job = JOBS.get(payload.jobId());
            if (job == null) {
                ServerPlayNetworking.send(player, new Payloads.JobClosed(payload.jobId(), "job not found"));
                return;
            }
            switch (Payloads.Action.values()[Math.clamp(payload.action(), 0, Payloads.Action.values().length - 1)]) {
                case STOP -> {
                    if (job.owner.equals(player.getUUID()) || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
                        stop(job.id, "stopped by " + player.getName().getString());
                    } else {
                        ServerPlayNetworking.send(player, new Payloads.JobClosed(-1, "only " + job.ownerName + " or an operator can stop this job"));
                    }
                }
                case SUBSCRIBE -> {
                    if (job.level != player.level()) {
                        ServerPlayNetworking.send(player, new Payloads.JobClosed(-1, "this job is in " + job.level.dimension().identifier()));
                        return;
                    }
                    job.subscribe(player);
                }
                case UNSUBSCRIBE -> job.unsubscribe(player.getUUID());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Payloads.ListJobs.TYPE, (payload, context) ->
                ServerPlayNetworking.send(context.player(), new Payloads.JobList(list())));
    }

    /** Starts a job; returns an error message or null. {@code player} may be null (console). */
    public static String start(ServerLevel level, ServerPlayer player, String name, BlockPos a, BlockPos b) {
        ServerConfig config = ServerConfig.get();
        if (player != null && config.opsOnly && !player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return "only operators can start predictions on this server";
        }
        if (JOBS.size() >= config.maxJobs) {
            return "too many jobs (" + JOBS.size() + "/" + config.maxJobs + "), stop one first";
        }
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.max(Math.min(a.getY(), b.getY()), level.getMinY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.min(Math.max(a.getY(), b.getY()), level.getMaxY()), Math.max(a.getZ(), b.getZ()));
        if (min.getY() > max.getY()) {
            return "the area is outside the world height";
        }
        long chunks = (long) ((max.getX() >> 4) - (min.getX() >> 4) + 1) * ((max.getZ() >> 4) - (min.getZ() >> 4) + 1);
        if (chunks > config.maxChunksPerJob) {
            return "area too large: " + chunks + " chunks, the server allows " + config.maxChunksPerJob;
        }
        String jobName = name == null || name.isBlank() ? (player == null ? "console" : player.getName().getString()) + "'s area" : name.strip();
        if (jobName.length() > 48) jobName = jobName.substring(0, 48);
        UUID owner = player == null ? new UUID(0, 0) : player.getUUID();
        String ownerName = player == null ? "Server" : player.getName().getString();
        ServerJob job = new ServerJob(nextId++, jobName, owner, ownerName, level, min, max);
        JOBS.put(job.id, job);
        hasJobs = true;
        if (player != null) {
            job.subscribe(player);
        }
        ObsidianPredictorMod.LOGGER.info("{} started obsidian prediction #{} '{}' for {} .. {} ({} chunks)",
                ownerName, job.id, jobName, min.toShortString(), max.toShortString(), chunks);
        return null;
    }

    public static boolean stop(long id, String reason) {
        ServerJob job = JOBS.remove(id);
        hasJobs = !JOBS.isEmpty();
        if (job == null) return false;
        job.stop(reason);
        return true;
    }

    public static ServerJob get(long id) {
        return JOBS.get(id);
    }

    public static List<Payloads.JobInfo> list() {
        List<Payloads.JobInfo> infos = new ArrayList<>();
        for (ServerJob job : JOBS.values()) infos.add(job.info());
        return infos;
    }

    public static void tick(MinecraftServer server) {
        if (!hasJobs) return;
        for (Iterator<ServerJob> it = JOBS.values().iterator(); it.hasNext(); ) {
            ServerJob job = it.next();
            job.tick();
            if (job.idleTooLong()) {
                ObsidianPredictorMod.LOGGER.info("Stopping idle obsidian prediction #{} '{}'", job.id, job.name);
                job.stop("nobody followed it for " + ServerConfig.get().idleStopMinutes + " minutes");
                it.remove();
            }
        }
        hasJobs = !JOBS.isEmpty();
    }

    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState state) {
        if (!hasJobs) return;
        for (ServerJob job : JOBS.values()) {
            job.blockChanged(level, pos, state);
        }
    }

    public static void onPlayerLeft(UUID player) {
        for (ServerJob job : JOBS.values()) job.unsubscribe(player);
    }

    public static void clear() {
        for (ServerJob job : JOBS.values()) job.job.stop();
        JOBS.clear();
        hasJobs = false;
        nextId = 1;
    }
}
