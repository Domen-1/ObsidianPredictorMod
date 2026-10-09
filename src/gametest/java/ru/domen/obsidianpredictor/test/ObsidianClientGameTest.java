package ru.domen.obsidianpredictor.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.domen.obsidianpredictor.client.ClientSession;
import ru.domen.obsidianpredictor.client.JobsScreen;
import ru.domen.obsidianpredictor.client.PredictorScreen;
import ru.domen.obsidianpredictor.network.Payloads;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * End to end in a real client against a dedicated server with the mod: a shared server prediction, the same
 * prediction computed locally from loaded chunks, live updates after a block change, Xaero export and the screens.
 * Screenshots go to build/run/clientGameTest/screenshots.
 */
public class ObsidianClientGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("obsidianpredictor-test");
    private static final BlockPos CORNER1 = new BlockPos(0, -64, 0);
    private static final BlockPos CORNER2 = new BlockPos(31, -50, 31);

    @Override
    public void runTest(ClientGameTestContext context) {
        Properties properties = new Properties();
        properties.setProperty("level-type", "minecraft:flat");
        properties.setProperty("enforce-secure-profile", "false");
        properties.setProperty("generate-structures", "false");
        properties.setProperty("spawn-protection", "0");
        List<Component> chat = new CopyOnWriteArrayList<>();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> chat.add(message));

        try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties);
             TestDedicatedServerConnection connection = server.connect()) {
            connection.waitForChunksRender();
            String player = context.computeOnClient(mc -> mc.player.getName().getString());
            server.runCommand("gamemode creative " + player);
            server.runCommand("tp " + player + " 12 -45 36 facing 12 -61 12");
            buildScenario(server);
            context.waitTicks(40);

            context.waitFor(mc -> ClientSession.serverModVersion != null, 200);
            check(context.computeOnClient(mc -> ClientSession.serverAvailable()), "client sees the mod on the server (" + ClientSession.serverModVersion + ")");

            // 1. Shared prediction on the server, started like the screen does.
            context.runOnClient(mc -> {
                ClientSession.corner1 = CORNER1;
                ClientSession.corner2 = CORNER2;
                ClientSession.startOnServer("gametest");
            });
            context.waitFor(mc -> hasResult(), 400);
            ClientSession.Markers fromServer = context.computeOnClient(mc -> ClientSession.markers());
            long jobId = context.computeOnClient(mc -> ClientSession.followed().id());
            LOG.info("Server markers: lava {} blast {}", fromServer.lava(), fromServer.blastResistant());
            check(!fromServer.lava().isEmpty(), "server prediction marks the flooded lava pocket");
            check(fromServer.lava().stream().allMatch(p -> p.getY() == -61 && in(p.getX(), 10, 14) && in(p.getZ(), 10, 14)),
                    "every lava marker is on the lava pocket");
            check(fromServer.blastResistant().size() == 1 && inBox(fromServer.blastResistant().getFirst(), 20, -60, 20, 22, -58, 22),
                    "one marker for the obsidian group");
            context.waitTicks(5);
            context.takeScreenshot("obsidian-1-server");

            // 2. The same area computed on the client from its loaded chunks must give the same markers.
            context.runOnClient(mc -> ClientSession.startLocal());
            context.waitFor(mc -> ClientSession.local() != null && ClientSession.local().job().result() != null, 400);
            ClientSession.Markers local = context.computeOnClient(mc -> ClientSession.markers());
            check(local.lava().equals(fromServer.lava()) && local.blastResistant().equals(fromServer.blastResistant()),
                    "local prediction equals the server one");

            // 3. Live: close the gap between water and lava with stone; markers must update without restarting.
            server.runCommand("fill 8 -60 8 16 -60 16 stone");
            context.waitFor(mc -> ClientSession.markers() != null && ClientSession.markers().lava().isEmpty(), 400);
            check(context.computeOnClient(mc -> ClientSession.markers().blastResistant().size()) == 1, "local markers updated after the change");
            context.runOnClient(mc -> ClientSession.follow(jobId));
            context.waitFor(mc -> ClientSession.followed() != null && ClientSession.markers() != null
                    && ClientSession.markers().lava().isEmpty() && ClientSession.markers().blastResistant().size() == 1, 400);
            check(true, "server job updated after the change as well");
            server.runCommand("fill 8 -60 8 16 -60 16 air");
            context.waitFor(mc -> ClientSession.markers() != null && ClientSession.markers().lava().equals(fromServer.lava()), 400);
            check(true, "server markers came back when the gap was opened again");

            // 4. Xaero export.
            chat.clear();
            context.runOnClient(mc -> ClientSession.exportWaypoints());
            Path file = FabricLoader.getInstance().getGameDir().resolve("obsidianpredictor").resolve("gametest waypoints.txt");
            context.waitFor(mc -> Files.exists(file), 100);
            List<String> lines = readLines(file);
            check(lines.size() == fromServer.lava().size() + 1 && lines.getFirst().startsWith("waypoint:x:x:")
                    && lines.getFirst().contains(":12:false:0:gui.xaero_default:"), "Xaero file has red lava and black obsidian lines");

            // 5. Commands and screens.
            chat.clear();
            context.runOnClient(mc -> mc.player.connection.sendCommand("obsidian jobs"));
            context.waitFor(mc -> chat.stream().anyMatch(c -> c.getString().contains("'gametest'")), 100);
            check(true, "/obsidian jobs lists the server job");
            context.runOnClient(mc -> mc.player.connection.sendCommand("obsidian pos1 0 -64 0"));
            context.waitFor(mc -> chat.stream().anyMatch(c -> c.getString().contains("Corner 1: 0, -64, 0")), 100);
            check(true, "/obsidian pos1 sets the corner");

            context.setScreen(PredictorScreen::new);
            context.waitTicks(5);
            context.takeScreenshot("obsidian-2-screen");
            context.setScreen(() -> new JobsScreen(null));
            context.waitFor(mc -> !ClientSession.serverJobs.isEmpty(), 100);
            context.waitTicks(5);
            context.takeScreenshot("obsidian-3-jobs");
            context.setScreen(() -> null);
            context.waitTicks(5);
            context.takeScreenshot("obsidian-4-world");
            context.runOnClient(mc -> ClientSession.stopAll(true));
        }
    }

    private static void buildScenario(TestDedicatedServerContext server) {
        // Stone block with a lava pocket, an air gap above it, a water pool over the gap, and an obsidian group.
        // Ticks are frozen so the water does not actually fall onto the lava. Only air-like blocks let the
        // simulated water fall (dirt would stop it just like stone).
        server.runCommand("tick freeze");
        server.runCommand("fill 0 -61 0 31 -55 31 stone");
        server.runCommand("fill 10 -61 10 14 -61 14 lava");
        server.runCommand("fill 8 -60 8 16 -60 16 air");
        server.runCommand("fill 8 -59 8 16 -57 16 water");
        server.runCommand("fill 20 -60 20 22 -58 22 obsidian");
    }

    private static boolean hasResult() {
        Payloads.JobInfo info = ClientSession.followed();
        return info != null && info.revision() > 0 && ClientSession.markers() != null && !ClientSession.markers().blastResistant().isEmpty();
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (Exception e) {
            throw new AssertionError("cannot read " + file, e);
        }
    }

    private static boolean in(int v, int min, int max) {
        return v >= min && v <= max;
    }

    private static boolean inBox(BlockPos p, int x1, int y1, int z1, int x2, int y2, int z2) {
        return in(p.getX(), x1, x2) && in(p.getY(), y1, y2) && in(p.getZ(), z1, z2);
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            LOG.error("Check failed: {}", what);
            throw new AssertionError("Check failed: " + what);
        }
        LOG.info("OK: {}", what);
    }
}
