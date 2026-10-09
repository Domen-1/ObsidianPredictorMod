package ru.domen.obsidianpredictor.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import ru.domen.obsidianpredictor.network.Payloads;

/** Area selection and start/stop/export, the in-game counterpart of the old file dialog. */
public final class PredictorScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFA0A0A0;
    private static String lastName = "";

    private final EditBox[] corner1 = new EditBox[3];
    private final EditBox[] corner2 = new EditBox[3];
    private EditBox name;
    private Button startServer;

    public PredictorScreen() {
        super(Component.literal("Obsidian Predictor"));
    }

    @Override
    protected void init() {
        int left = width / 2 - 155;
        int y = 40;
        createCorner(corner1, left, y, ClientSession.corner1, true);
        createCorner(corner2, left + 160, y, ClientSession.corner2, false);

        y += 56;
        name = new EditBox(font, left, y, 150, 18, Component.literal("Name"));
        name.setHint(Component.literal("name for the server job"));
        name.setValue(lastName);
        name.setResponder(v -> lastName = v);
        addRenderableWidget(name);

        y += 26;
        addRenderableWidget(Button.builder(Component.literal("Start here"), b -> {
            ClientSession.startLocal();
            onClose();
        }).bounds(left, y, 100, 20).build());
        startServer = addRenderableWidget(Button.builder(Component.literal("Start on server"), b -> {
            ClientSession.startOnServer(name.getValue());
            onClose();
        }).bounds(left + 105, y, 100, 20).build());
        startServer.active = ClientSession.serverAvailable();
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> ClientSession.stopAll(false))
                .bounds(left + 210, y, 100, 20).build());

        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Export Xaero waypoints"), b -> ClientSession.exportWaypoints())
                .bounds(left, y, 150, 20).build());
        Button jobs = addRenderableWidget(Button.builder(Component.literal("Server jobs..."), b -> minecraft.setScreenAndShow(new JobsScreen(this)))
                .bounds(left + 160, y, 150, 20).build());
        jobs.active = ClientSession.serverAvailable();

        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Clear markers"), b -> ClientSession.stopAll(true))
                .bounds(left, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(left + 160, y, 150, 20).build());
    }

    private void createCorner(EditBox[] fields, int x, int y, BlockPos value, boolean first) {
        for (int i = 0; i < 3; i++) {
            EditBox field = new EditBox(font, x + i * 50, y, 46, 18, Component.literal("xyz".substring(i, i + 1)));
            field.setValue(value == null ? "" : String.valueOf(i == 0 ? value.getX() : i == 1 ? value.getY() : value.getZ()));
            field.setResponder(v -> readCorner(fields, first));
            fields[i] = addRenderableWidget(field);
        }
        addRenderableWidget(Button.builder(Component.literal("Here"), b -> {
            if (minecraft.player == null) return;
            BlockPos pos = minecraft.player.blockPosition();
            fields[0].setValue(String.valueOf(pos.getX()));
            fields[1].setValue(String.valueOf(pos.getY()));
            fields[2].setValue(String.valueOf(pos.getZ()));
        }).bounds(x, y + 22, 146, 18).build());
    }

    private static void readCorner(EditBox[] fields, boolean first) {
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(fields[0].getValue().trim()),
                    Integer.parseInt(fields[1].getValue().trim()), Integer.parseInt(fields[2].getValue().trim()));
            if (first) ClientSession.corner1 = pos;
            else ClientSession.corner2 = pos;
        } catch (NumberFormatException ignored) {
            // keep the last valid corner while typing
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int left = width / 2 - 155;
        graphics.text(font, title, width / 2 - font.width(title) / 2, 14, WHITE);
        graphics.text(font, "Corner 1 (x y z)", left, 30, GRAY);
        graphics.text(font, "Corner 2 (x y z)", left + 160, 30, GRAY);

        int y = 214;
        BlockPos[] sel = ClientSession.selection();
        String area = sel == null ? "Set both corners. The area is the world eater perimeter."
                : (sel[1].getX() - sel[0].getX() + 1) + " x " + (sel[1].getY() - sel[0].getY() + 1) + " x "
                + (sel[1].getZ() - sel[0].getZ() + 1) + " blocks, " + ClientSession.selectionChunks() + " chunks";
        graphics.text(font, area, left, y, WHITE);
        String server = ClientSession.serverAvailable()
                ? "Server has Obsidian Predictor" + (ClientSession.serverModVersion == null ? "" : " " + ClientSession.serverModVersion)
                + ": 'Start on server' reads unloaded chunks and shares the result."
                : "Server without the mod: only 'Start here' (loaded chunks).";
        graphics.text(font, server, left, y + 12, GRAY);
        Payloads.JobInfo followed = ClientSession.followed();
        if (followed != null) {
            graphics.text(font, "Following server job '" + followed.name() + "' (#" + followed.id() + ")", left, y + 24, GRAY);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
