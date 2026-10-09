package ru.domen.obsidianpredictor.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.domen.obsidianpredictor.network.Payloads;
import ru.domen.obsidianpredictor.world.PredictionJob;

import java.util.List;

/** Predictions running on the server: follow one to see its markers, or stop it. */
public final class JobsScreen extends Screen {
    private static final int ROWS = 8;
    private final Screen parent;
    private boolean requested;

    public JobsScreen(Screen parent) {
        super(Component.literal("Server predictions"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!requested) {
            requested = true;
            ClientSession.requestJobList(false);
        }
        int left = width / 2 - 170;
        List<Payloads.JobInfo> jobs = ClientSession.serverJobs;
        for (int i = 0; i < Math.min(ROWS, jobs.size()); i++) {
            Payloads.JobInfo info = jobs.get(i);
            int y = 40 + i * 26;
            addRenderableWidget(Button.builder(Component.literal("Follow"), b -> {
                ClientSession.follow(info.id());
                onClose();
            }).bounds(left + 250, y, 44, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
                ClientSession.stopServerJob(info.id());
                ClientSession.requestJobList(false);
            })
                    .bounds(left + 298, y, 44, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> ClientSession.requestJobList(false))
                .bounds(width / 2 - 102, height - 30, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
                .bounds(width / 2 + 2, height - 30, 100, 20).build());
    }

    /** Called when a new job list arrives. */
    void refresh() {
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(font, title, width / 2 - font.width(title) / 2, 14, 0xFFFFFFFF);
        int left = width / 2 - 170;
        List<Payloads.JobInfo> jobs = ClientSession.serverJobs;
        if (jobs.isEmpty()) {
            graphics.text(font, "No predictions on this server yet.", left, 44, 0xFFA0A0A0);
        }
        for (int i = 0; i < Math.min(ROWS, jobs.size()); i++) {
            Payloads.JobInfo info = jobs.get(i);
            int y = 40 + i * 26;
            PredictionJob.Phase phase = PredictionJob.Phase.byId(info.phase());
            String state = switch (phase) {
                case CAPTURING -> "reading " + info.capturedChunks() + "/" + info.totalChunks();
                case PREDICTING -> "simulating";
                case READY -> info.lavaCount() + " lava, " + info.blastCount() + " blast res.";
                case FAILED -> "failed: " + info.message();
                case STOPPED -> "stopped";
            };
            graphics.text(font, "#" + info.id() + " " + info.name() + " (" + info.owner() + ")", left, y + 1, 0xFFFFFFFF);
            graphics.text(font, info.min().toShortString() + " .. " + info.max().toShortString() + "  " + state, left, y + 11, 0xFFA0A0A0);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
