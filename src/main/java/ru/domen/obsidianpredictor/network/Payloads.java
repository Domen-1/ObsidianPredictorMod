package ru.domen.obsidianpredictor.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.domen.obsidianpredictor.ObsidianPredictorMod;

import java.util.ArrayList;
import java.util.List;

/** Client/server protocol for shared (server-side) predictions. */
public final class Payloads {
    public static final int PROTOCOL_VERSION = 2;
    /** Positions per {@link JobBlocks} packet; keeps each packet well under the 1 MB payload limit. */
    public static final int BLOCKS_PER_PACKET = 65536;

    private Payloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ObsidianPredictorMod.MOD_ID, path));
    }

    /** Everything a client needs to list, follow and render a server job. */
    public record JobInfo(long id, String name, String owner, String dimension, BlockPos min, BlockPos max,
                          int phase, int capturedChunks, int totalChunks, int predictionPermille,
                          int revision, int lavaCount, int blastCount, int subscribers, String message) {
        public static JobInfo read(RegistryFriendlyByteBuf buf) {
            return new JobInfo(buf.readLong(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBlockPos(),
                    buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf());
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeLong(id);
            buf.writeUtf(name);
            buf.writeUtf(owner);
            buf.writeUtf(dimension);
            buf.writeBlockPos(min);
            buf.writeBlockPos(max);
            buf.writeVarInt(phase);
            buf.writeVarInt(capturedChunks);
            buf.writeVarInt(totalChunks);
            buf.writeVarInt(predictionPermille);
            buf.writeVarInt(revision);
            buf.writeVarInt(lavaCount);
            buf.writeVarInt(blastCount);
            buf.writeVarInt(subscribers);
            buf.writeUtf(message);
        }
    }

    // ---- client -> server ----

    public record StartJob(String name, BlockPos min, BlockPos max) implements CustomPacketPayload {
        public static final Type<StartJob> TYPE = payloadType("start_job");
        public static final StreamCodec<RegistryFriendlyByteBuf, StartJob> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeUtf(p.name);
                    buf.writeBlockPos(p.min);
                    buf.writeBlockPos(p.max);
                },
                buf -> new StartJob(buf.readUtf(), buf.readBlockPos(), buf.readBlockPos()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public enum Action { STOP, SUBSCRIBE, UNSUBSCRIBE }

    public record JobAction(int action, long jobId) implements CustomPacketPayload {
        public static final Type<JobAction> TYPE = payloadType("job_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobAction> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.action);
                    buf.writeLong(p.jobId);
                },
                buf -> new JobAction(buf.readVarInt(), buf.readLong()));

        public JobAction(Action action, long jobId) {
            this(action.ordinal(), jobId);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ListJobs() implements CustomPacketPayload {
        public static final Type<ListJobs> TYPE = payloadType("list_jobs");
        public static final StreamCodec<RegistryFriendlyByteBuf, ListJobs> CODEC = StreamCodec.of((buf, p) -> {}, buf -> new ListJobs());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- server -> client ----

    public record Hello(int protocolVersion, String modVersion) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = payloadType("hello");
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.protocolVersion);
                    buf.writeUtf(p.modVersion);
                },
                buf -> new Hello(buf.readVarInt(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record JobList(List<JobInfo> jobs) implements CustomPacketPayload {
        public static final Type<JobList> TYPE = payloadType("job_list");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobList> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.jobs.size());
                    p.jobs.forEach(j -> j.write(buf));
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<JobInfo> jobs = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) jobs.add(JobInfo.read(buf));
                    return new JobList(jobs);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sent to subscribers while a job works and whenever its state changes. */
    public record JobStatus(JobInfo info) implements CustomPacketPayload {
        public static final Type<JobStatus> TYPE = payloadType("job_status");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobStatus> CODEC = StreamCodec.of(
                (buf, p) -> p.info.write(buf), buf -> new JobStatus(JobInfo.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Markers as packed {@link BlockPos#asLong()}, in waypoint order. */
    public record JobResult(long jobId, int revision, long[] lava, long[] blastResistant) implements CustomPacketPayload {
        public static final Type<JobResult> TYPE = payloadType("job_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobResult> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeLong(p.jobId);
                    buf.writeVarInt(p.revision);
                    buf.writeLongArray(p.lava);
                    buf.writeLongArray(p.blastResistant);
                },
                buf -> new JobResult(buf.readLong(), buf.readVarInt(), buf.readLongArray(), buf.readLongArray()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Every blast-resistant block of a result, split into parts; a revision is complete once all parts arrived. */
    public record JobBlocks(long jobId, int revision, int part, int parts, long[] positions) implements CustomPacketPayload {
        public static final Type<JobBlocks> TYPE = payloadType("job_blocks");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobBlocks> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeLong(p.jobId);
                    buf.writeVarInt(p.revision);
                    buf.writeVarInt(p.part);
                    buf.writeVarInt(p.parts);
                    buf.writeLongArray(p.positions);
                },
                buf -> new JobBlocks(buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readLongArray()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record JobClosed(long jobId, String reason) implements CustomPacketPayload {
        public static final Type<JobClosed> TYPE = payloadType("job_closed");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobClosed> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeLong(p.jobId);
                    buf.writeUtf(p.reason);
                },
                buf -> new JobClosed(buf.readLong(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static boolean registered;

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.serverboundPlay().register(StartJob.TYPE, StartJob.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(JobAction.TYPE, JobAction.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ListJobs.TYPE, ListJobs.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JobList.TYPE, JobList.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JobStatus.TYPE, JobStatus.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JobResult.TYPE, JobResult.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JobBlocks.TYPE, JobBlocks.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(JobClosed.TYPE, JobClosed.CODEC);
    }
}
