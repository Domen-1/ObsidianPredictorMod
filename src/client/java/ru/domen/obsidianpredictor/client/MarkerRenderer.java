package ru.domen.obsidianpredictor.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import ru.domen.obsidianpredictor.ObsidianPredictorMod;
import ru.domen.obsidianpredictor.world.PredictionJob;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws the area, chunks still missing from a local prediction and the markers, through walls.
 * The draw path follows AreaScanner's RenderUtil (LGPL-3.0, by Obabok).
 */
public final class MarkerRenderer {
    private record Color(float r, float g, float b, float a) {}

    private static final Color LAVA = new Color(1f, 0.25f, 0.1f, 0.55f);
    private static final Color BLAST_RESISTANT = new Color(0.55f, 0.1f, 0.85f, 0.55f);
    private static final Color AREA = new Color(1f, 1f, 1f, 0.6f);
    private static final Color SELECTION = new Color(0.4f, 0.9f, 1f, 0.6f);
    private static final Color MISSING_CHUNK = new Color(1f, 0.85f, 0.1f, 0.16f);
    /** The lid over missing chunks covers a lot of the view from above, so it is fainter than the walls. */
    private static final Color MISSING_CHUNK_LID = new Color(1f, 0.85f, 0.1f, 0.06f);

    /** Closer than this a marker is drawn as a block, farther as a camera-facing square. */
    private static final double BOX_DISTANCE = 48;
    /** Closer than this a blast-resistant group is drawn block by block. */
    private static final double GROUP_MESH_DISTANCE = 96;
    private static final double MAX_DISTANCE = 4096;

    private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(ObsidianPredictorMod.MOD_ID, "pipeline/markers"))
            .withDepthStencilState(Optional.empty())
            .build());
    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(1024 * 1024 * 2);
    private static final ByteBufferBuilder SORT_ALLOCATOR = new ByteBufferBuilder(1024 * 1024);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private static BufferBuilder buffer;
    private static Vec3 camera;
    private static Matrix4fc pose;

    private MarkerRenderer() {}

    public static void render(LevelRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gui.hud.isHidden()) return;
        ClientSession.Markers markers = ClientSession.markers();
        BlockPos[] selection = ClientSession.selection();
        boolean showSelection = selection != null && (markers == null || mc.gui.screen() instanceof PredictorScreen);
        if (markers == null && !showSelection) return;

        try {
            context.poseStack().pushPose();
            pose = context.poseStack().last().pose();
            camera = context.levelState().cameraRenderState.pos;
            buffer = new BufferBuilder(ALLOCATOR, PIPELINE.getPrimitiveTopology(), PIPELINE.getVertexFormatBinding(0));

            if (showSelection) {
                outline(selection[0], selection[1], SELECTION);
            }
            if (markers != null && markers.dimension() == mc.level.dimension()) {
                if (!showSelection) {
                    outline(markers.min(), markers.max(), AREA);
                }
                missingChunks(markers.min(), markers.max());
                markers(markers.lava(), LAVA);
                blastResistant(markers);
            }
            context.poseStack().popPose();
            draw(mc);
        } catch (Exception e) {
            ObsidianPredictorMod.LOGGER.debug("marker rendering failed", e);
            buffer = null;
        }
    }

    /**
     * Chunks a local prediction still needs, as walls around them over the area's height plus a lid,
     * so they show from any distance (they are always beyond render distance).
     */
    private static void missingChunks(BlockPos min, BlockPos max) {
        ClientSession.LocalPrediction local = ClientSession.local();
        if (local == null || local.job().phase() != PredictionJob.Phase.CAPTURING) return;
        LongOpenHashSet missing = local.job().missingChunks();
        double y1 = min.getY(), y2 = max.getY() + 1;
        LongIterator it = missing.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            double x1 = Math.max(cx << 4, min.getX()), x2 = Math.min((cx << 4) + 16, max.getX() + 1);
            double z1 = Math.max(cz << 4, min.getZ()), z2 = Math.min((cz << 4) + 16, max.getZ() + 1);
            quad(x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, MISSING_CHUNK_LID);
            if (!missing.contains(ChunkPos.pack(cx, cz - 1))) quad(x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, MISSING_CHUNK);
            if (!missing.contains(ChunkPos.pack(cx, cz + 1))) quad(x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, MISSING_CHUNK);
            if (!missing.contains(ChunkPos.pack(cx - 1, cz))) quad(x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, MISSING_CHUNK);
            if (!missing.contains(ChunkPos.pack(cx + 1, cz))) quad(x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1, MISSING_CHUNK);
        }
    }

    /** A quad visible from both sides. */
    private static void quad(double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz, Color c) {
        vertex(ax, ay, az, c); vertex(bx, by, bz, c); vertex(cx, cy, cz, c); vertex(dx, dy, dz, c);
        vertex(dx, dy, dz, c); vertex(cx, cy, cz, c); vertex(bx, by, bz, c); vertex(ax, ay, az, c);
    }

    /** Whole groups up close (outer faces only), one square per group farther away. */
    private static void blastResistant(ClientSession.Markers markers) {
        BlockGroups groups = markers.blastGroups();
        if (groups.groups.isEmpty()) {
            // blocks not received yet: show the one-per-group waypoints
            markers(markers.blastResistant(), BLAST_RESISTANT);
            return;
        }
        for (BlockGroups.Group group : groups.groups) {
            double dx = Math.max(0, Math.max(group.minX() - camera.x, camera.x - (group.maxX() + 1)));
            double dz = Math.max(0, Math.max(group.minZ() - camera.z, camera.z - (group.maxZ() + 1)));
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > MAX_DISTANCE) continue;
            if (dist < GROUP_MESH_DISTANCE) {
                for (long p : group.blocks()) {
                    outerFaces(p, groups.all, BLAST_RESISTANT);
                }
            } else {
                billboard(group.centerX(), group.centerY(), group.centerZ(), (float) Math.max(1.5, dist / 40), BLAST_RESISTANT);
            }
        }
    }

    /** Faces of a block that do not touch another block of the set. */
    private static void outerFaces(long p, LongOpenHashSet all, Color c) {
        int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
        double x1 = x, y1 = y, z1 = z, x2 = x + 1, y2 = y + 1, z2 = z + 1;
        if (!all.contains(BlockPos.asLong(x, y + 1, z))) { vertex(x1, y2, z2, c); vertex(x2, y2, z2, c); vertex(x2, y2, z1, c); vertex(x1, y2, z1, c); }
        if (!all.contains(BlockPos.asLong(x, y - 1, z))) { vertex(x1, y1, z1, c); vertex(x2, y1, z1, c); vertex(x2, y1, z2, c); vertex(x1, y1, z2, c); }
        if (!all.contains(BlockPos.asLong(x, y, z - 1))) { vertex(x2, y1, z1, c); vertex(x1, y1, z1, c); vertex(x1, y2, z1, c); vertex(x2, y2, z1, c); }
        if (!all.contains(BlockPos.asLong(x, y, z + 1))) { vertex(x1, y1, z2, c); vertex(x2, y1, z2, c); vertex(x2, y2, z2, c); vertex(x1, y2, z2, c); }
        if (!all.contains(BlockPos.asLong(x - 1, y, z))) { vertex(x1, y1, z1, c); vertex(x1, y1, z2, c); vertex(x1, y2, z2, c); vertex(x1, y2, z1, c); }
        if (!all.contains(BlockPos.asLong(x + 1, y, z))) { vertex(x2, y1, z2, c); vertex(x2, y1, z1, c); vertex(x2, y2, z1, c); vertex(x2, y2, z2, c); }
    }

    private static void markers(List<BlockPos> positions, Color color) {
        for (BlockPos pos : positions) {
            double dx = pos.getX() + 0.5 - camera.x, dz = pos.getZ() + 0.5 - camera.z;
            double distXZ = Math.sqrt(dx * dx + dz * dz);
            if (distXZ > MAX_DISTANCE) continue;
            if (distXZ < BOX_DISTANCE) {
                box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1, color);
            } else {
                // grows with distance so far markers stay visible
                float size = (float) Math.max(1.5, distXZ / 40);
                billboard(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, size, color);
            }
        }
    }

    /** The twelve edges of the area as thin boxes. */
    private static void outline(BlockPos min, BlockPos max, Color color) {
        double t = 0.06;
        double x1 = min.getX(), y1 = min.getY(), z1 = min.getZ();
        double x2 = max.getX() + 1, y2 = max.getY() + 1, z2 = max.getZ() + 1;
        for (double y : new double[]{y1, y2}) {
            for (double z : new double[]{z1, z2}) box(x1, y - t, z - t, x2, y + t, z + t, color);
            for (double x : new double[]{x1, x2}) box(x - t, y - t, z1, x + t, y + t, z2, color);
        }
        for (double x : new double[]{x1, x2}) {
            for (double z : new double[]{z1, z2}) box(x - t, y1, z - t, x + t, y2, z + t, color);
        }
    }

    private static void vertex(double x, double y, double z, Color c) {
        buffer.addVertex(pose, (float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z)).setColor(c.r, c.g, c.b, c.a);
    }

    private static void box(double x1, double y1, double z1, double x2, double y2, double z2, Color c) {
        // up
        vertex(x1, y2, z2, c); vertex(x2, y2, z2, c); vertex(x2, y2, z1, c); vertex(x1, y2, z1, c);
        // down
        vertex(x1, y1, z1, c); vertex(x2, y1, z1, c); vertex(x2, y1, z2, c); vertex(x1, y1, z2, c);
        // north
        vertex(x2, y1, z1, c); vertex(x1, y1, z1, c); vertex(x1, y2, z1, c); vertex(x2, y2, z1, c);
        // south
        vertex(x1, y1, z2, c); vertex(x2, y1, z2, c); vertex(x2, y2, z2, c); vertex(x1, y2, z2, c);
        // west
        vertex(x1, y1, z1, c); vertex(x1, y1, z2, c); vertex(x1, y2, z2, c); vertex(x1, y2, z1, c);
        // east
        vertex(x2, y1, z2, c); vertex(x2, y1, z1, c); vertex(x2, y2, z1, c); vertex(x2, y2, z2, c);
    }

    private static void billboard(double x, double y, double z, float size, Color c) {
        double cx = x - camera.x, cy = y - camera.y, cz = z - camera.z;
        double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (len == 0) return;
        double nx = cx / len, ny = cy / len, nz = cz / len;
        double rx = nz, rz = -nx;
        double rLen = Math.sqrt(rx * rx + rz * rz);
        if (rLen < 1e-6) {
            rx = 1;
            rz = 0;
        } else {
            rx /= rLen;
            rz /= rLen;
        }
        // up = right x forward
        double ux = -rz * ny, uy = rz * nx - rx * nz, uz = rx * ny;
        double h = size / 2;
        vertex(x - rx * h - ux * h, y - uy * h, z - rz * h - uz * h, c);
        vertex(x + rx * h - ux * h, y - uy * h, z + rz * h - uz * h, c);
        vertex(x + rx * h + ux * h, y + uy * h, z + rz * h + uz * h, c);
        vertex(x - rx * h + ux * h, y + uy * h, z - rz * h + uz * h, c);
    }

    private static void draw(Minecraft mc) {
        MeshData mesh;
        try {
            mesh = buffer.buildOrThrow();
        } catch (IllegalStateException e) {
            buffer = null;
            return;
        } finally {
            buffer = null;
        }
        if (mesh.drawState().vertexCount() == 0) {
            mesh.close();
            return;
        }
        GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(() -> "obsidianpredictor vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
        MeshData.DrawState drawState = mesh.drawState();
        GpuBuffer indices;
        IndexType indexType;
        MeshData.SortState sortState = mesh.sortQuads(SORT_ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
        if (sortState != null) {
            indices = RenderSystem.getDevice().createBuffer(() -> "obsidianpredictor indices", GpuBuffer.USAGE_INDEX, mesh.indexBuffer());
            indexType = drawState.indexType();
        } else {
            RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.TRIANGLES);
            indices = sequential.getBuffer(drawState.indexCount());
            indexType = sequential.type();
        }
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = target.getColorTextureView();
        if (colorTexture != null) {
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "obsidianpredictor markers", colorTexture, Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, vertexBuffer.slice());
                pass.setIndexBuffer(indices, indexType);
                pass.drawIndexed(drawState.indexCount(), 1, 0, 0, 0);
            }
        }
        vertexBuffer.close();
        if (sortState != null) {
            indices.close();
        }
        mesh.close();
    }
}
