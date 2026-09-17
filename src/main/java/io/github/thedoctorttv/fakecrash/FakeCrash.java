package io.github.thedoctorttv.fakecrash;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(FakeCrash.MOD_ID)
public final class FakeCrash {
    public static final String MOD_ID = "ped";
    private static final ForgeConfigSpec CONFIG;
    private static final Map<UUID, PendingKick> PENDING = new HashMap<>();
    private final ImageSelection selection = new ImageSelection();
    private PrankSettings settings;
    private ThreadPoolExecutor imageWorkers;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Image file paths or direct HTTP/HTTPS URLs. The server loads and sends the chosen image.",
                "Relative paths start at the server game directory (the instance minecraft folder in single player).",
                "Example: [\"/home/me/Pictures/prank.png\", \"https://example.com/prank.jpg\"]")
                .defineList("images", Collections.<String>emptyList(),
                        value -> value instanceof String && !((String) value).trim().isEmpty()
                                && ((String) value).length() <= 4096);
        builder.comment("random chooses an image randomly; sequential cycles through the array in order.",
                "Run /crash reload after editing. Reload and server restart reset the sequence.")
                .define("imageSelection", "random", value -> "random".equals(value) || "sequential".equals(value));
        builder.comment("Seconds to display the image before disconnecting (server ticks).")
                .defineInRange("imageSeconds", 3, 1, 15);
        CONFIG = builder.build();
    }

    public FakeCrash() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG, "fakecrash-common.toml");
        PrankNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(this::serverStarting);
        MinecraftForge.EVENT_BUS.addListener(this::serverTick);
        MinecraftForge.EVENT_BUS.addListener(this::loggedOut);
        MinecraftForge.EVENT_BUS.addListener(this::serverStopped);
    }

    private void serverStarting(FMLServerStartingEvent event) {
        selection.reset();
        try {
            settings = PrankSettings.read(configPath());
        } catch (IOException ex) {
            settings = null;
            org.apache.logging.log4j.LogManager.getLogger().error("ped config could not be loaded; use /crash reload after fixing it", ex);
        }
        imageWorkers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), runnable -> {
                    Thread thread = new Thread(runnable, "ped-image-loader");
                    thread.setDaemon(true);
                    return thread;
                });
        CommandDispatcher<CommandSource> dispatcher = event.getCommandDispatcher();
        dispatcher.register(Commands.literal("crash")
                .requires(source -> source.hasPermissionLevel(3))
                .then(Commands.literal("reload").executes(context -> reload(context.getSource())))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> prank(context.getSource(),
                                EntityArgument.getPlayer(context, "player"), false, null))
                        .then(Commands.literal("image")
                                .executes(context -> prank(context.getSource(),
                                        EntityArgument.getPlayer(context, "player"), true, null))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(context -> prank(context.getSource(),
                                                EntityArgument.getPlayer(context, "player"), true,
                                                StringArgumentType.getString(context, "url")))))));
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("fakecrash-common.toml");
    }

    private int reload(CommandSource source) {
        try {
            PrankSettings loaded = PrankSettings.read(configPath());
            settings = loaded;
            selection.reset();
            source.sendFeedback(new StringTextComponent("ped config reloaded: " + loaded.images.size()
                    + " images, " + (loaded.sequential ? "sequential" : "random")
                    + " selection, " + loaded.imageSeconds + " seconds. Sequence reset."), true);
            return 1;
        } catch (IOException ex) {
            source.sendErrorMessage(new StringTextComponent("Config reload failed; previous settings kept. " + ex.getMessage()));
            return 0;
        }
    }

    private int prank(CommandSource source, ServerPlayerEntity player, boolean showImage, String override) {
        if (PENDING.containsKey(player.getUniqueID())) {
            source.sendErrorMessage(new StringTextComponent("That player already has a pending fake crash."));
            return 0;
        }
        if (showImage) {
            if (settings == null) {
                source.sendErrorMessage(new StringTextComponent("Fix config/fakecrash-common.toml and run /crash reload first."));
                return 0;
            }
            try {
                String image = selection.select(settings.images, settings.sequential, override);
                Future<byte[]> loading = imageWorkers.submit(() -> ImageLoader.loadAnimation(image, Paths.get(".").toAbsolutePath()));
                PENDING.put(player.getUniqueID(), new PendingKick(player, source, loading, settings.imageSeconds * 20));
                selection.accepted(settings.images, settings.sequential, override);
            } catch (IllegalArgumentException ex) {
                source.sendErrorMessage(new StringTextComponent(ex.getMessage()));
                return 0;
            } catch (RejectedExecutionException ex) {
                source.sendErrorMessage(new StringTextComponent("Image loader is busy. Try again shortly."));
                return 0;
            }
        } else {
            disconnect(player);
        }
        source.sendFeedback(new StringTextComponent((showImage ? "Loading an image for " : "Crash triggered for ")
                + player.getName().getString() + "."), true);
        return 1;
    }

    private void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Iterator<PendingKick> iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            PendingKick pending = iterator.next();
            if (pending.loading != null && pending.loading.isDone()) {
                try {
                    byte[] image = pending.loading.get();
                    pending.loading = null;
                    pending.timeout = 200;
                    PrankNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> pending.player),
                            new PrankNetwork.ShowImage(pending.token, image));
                } catch (Exception ex) {
                    iterator.remove();
                    pending.source.sendErrorMessage(new StringTextComponent("Could not load the selected image; player was not kicked. "
                            + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage())));
                    continue;
                }
            }
            if (!pending.displayed) {
                if (--pending.timeout <= 0) {
                    iterator.remove();
                    pending.cancel();
                    pending.source.sendErrorMessage(new StringTextComponent("Image loading or display timed out; player was not kicked."));
                }
            } else if (--pending.ticks <= 0) {
                // Remove before disconnect, which can synchronously fire logout events.
                iterator.remove();
                disconnect(pending.player);
            }
        }
    }

    private void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PendingKick pending = PENDING.remove(event.getPlayer().getUniqueID());
        if (pending != null) pending.cancel();
    }

    private void serverStopped(FMLServerStoppedEvent event) {
        PENDING.values().forEach(PendingKick::cancel);
        PENDING.clear();
        if (imageWorkers != null) imageWorkers.shutdownNow();
    }

    // Invoked on the server thread; a client can acknowledge only its own current request.
    static void imageDisplayed(ServerPlayerEntity player, UUID token) {
        if (player == null) return;
        PendingKick pending = PENDING.get(player.getUniqueID());
        if (pending != null && pending.player == player && pending.loading == null && pending.token.equals(token)) {
            pending.displayed = true;
        }
    }

    private static void disconnect(ServerPlayerEntity player) {
        player.connection.disconnect(new StringTextComponent(
                "Internal Exception: an unexpected error occurred.\n\nYou can reconnect to the server."));
    }

    private static final class PendingKick {
        final ServerPlayerEntity player;
        final CommandSource source;
        final UUID token = UUID.randomUUID();
        Future<byte[]> loading;
        boolean displayed;
        int timeout = 400;
        int ticks;

        PendingKick(ServerPlayerEntity player, CommandSource source, Future<byte[]> loading, int ticks) {
            this.player = player;
            this.source = source;
            this.loading = loading;
            this.ticks = ticks;
        }

        void cancel() {
            if (loading != null) loading.cancel(true);
        }
    }
}
