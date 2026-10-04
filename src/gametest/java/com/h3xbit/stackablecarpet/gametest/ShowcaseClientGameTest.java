package com.h3xbit.stackablecarpet.gametest;

import com.h3xbit.stackablecarpet.StackableCarpet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MossyCarpetBlock;
import net.minecraft.world.level.block.state.properties.WallSide;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Builds a few clean scenes and takes screenshots for the mod page. Only runs when the
// STACKABLE_CARPET_SHOWCASE environment variable is set - the screenshots end up in
// build/run/clientGameTest/screenshots. Files starting with gif_ are frames for animations.
public class ShowcaseClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("stackable_carpet-showcase");
    private static final double EYE_HEIGHT = 1.62;

    private static final Block WHITE = Blocks.CARPET.pick(DyeColor.WHITE);
    private static final Block GRAY = Blocks.CARPET.pick(DyeColor.GRAY);

    private static final BlockPos GROWING = new BlockPos(40, 100, 0);
    private static final BlockPos MIXING = new BlockPos(48, 100, 0);
    private static final BlockPos BREAKING = new BlockPos(56, 100, 0);
    private static final BlockPos OUTLINE = new BlockPos(64, 100, 0);

    public static boolean enabled() {
        return System.getenv("STACKABLE_CARPET_SHOWCASE") != null;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!enabled()) {
            return;
        }

        // 1920x1080 window, the size the screenshots are published at - resized rather than maximized,
        // since maximizing also brings the window to the front and takes focus
        context.getInput().resizeWindow(1920, 1080);
        context.waitTicks(10);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("time set 6000");
            server.runCommand("weather clear 100000");
            server.runCommand("gamerule advance_time false");
            server.runCommand("gamerule doDaylightCycle false");
            server.runCommand("gamerule doMobSpawning false");
            server.runCommand("tp @a 0.5 101 8.5");
            context.waitTicks(40);

            buildPlatform(server);
            buildStaircase(server);
            buildMixedRow(server);
            buildMossRow(server);
            context.waitTicks(20);

            // Spectator so the camera can go anywhere without bumping into blocks
            server.runCommand("gamemode spectator @a");
            // F1 - hide the HUD and hand
            context.getInput().pressKey(options -> options.keyToggleGui);

            shot(context, server, "hero", 22.0, 103.5, 8.0, 22.0, 100.4, 0.5);
            shot(context, server, "staircase", -0.5, 104.0, 7.5, -0.5, 100.2, 0.5);
            shot(context, server, "mixed_stacks", 16.0, 101.5, 4.5, 16.0, 100.4, 0.5);
            shot(context, server, "moss", 29.0, 101.7, 4.5, 29.0, 100.5, 0.5);

            // A stack growing one layer at a time until the 16th turns it into wool
            camera(server, 40.5, 101.4, 3.2, 40.5, 100.4, 0.5);
            for (int layers = 1; layers <= StackableCarpet.MAX_SINGLE_COLOR_LAYERS; layers++) {
                setStack(server, GROWING, Collections.nCopies(layers, WHITE));
                capture(context, "gif_growing_" + frame(layers - 1));
            }
            server.runOnServer(minecraftServer -> minecraftServer.overworld().setBlock(GROWING, Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState(), 3));
            capture(context, "gif_growing_" + frame(StackableCarpet.MAX_SINGLE_COLOR_LAYERS));

            // A mixed stack built one carpet at a time
            List<Block> mixed = mixedLayers();
            camera(server, 48.5, 101.4, 3.2, 48.5, 100.4, 0.5);
            for (int layers = 1; layers <= mixed.size(); layers++) {
                setStack(server, MIXING, mixed.subList(0, layers));
                capture(context, "gif_mixing_" + frame(layers - 1));
            }

            // Layers broken off the top of a mixed stack
            camera(server, 56.5, 101.4, 3.2, 56.5, 100.4, 0.5);
            for (int layers = mixed.size(); layers >= 1; layers--) {
                setStack(server, BREAKING, mixed.subList(0, layers));
                capture(context, "gif_breaking_" + frame(mixed.size() - layers));
            }
            server.runCommand(String.format("setblock %d %d %d minecraft:air", BREAKING.getX(), BREAKING.getY(), BREAKING.getZ()));
            capture(context, "gif_breaking_" + frame(mixed.size()));

            // The hover outline following the aimed layer up the side of a mixed stack - creative and with the HUD on,
            // since F1 hides the outline too (the frames get cropped to the stack afterwards)
            context.getInput().pressKey(options -> options.keyToggleGui);
            setStack(server, OUTLINE, mixed);
            server.runCommand("gamemode creative @a");
            server.runOnServer(minecraftServer -> {
                ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            camera(server, 66.6, 101.3, 2.6, 65.0, 100.5, 0.5);
            context.waitTicks(10);
            for (int layer = 1; layer <= mixed.size(); layer++) {
                Vec3 aimedLayer = new Vec3(OUTLINE.getX() + 1.0, OUTLINE.getY() + (layer - 0.5) / 16.0, OUTLINE.getZ() + 0.5);
                context.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES, aimedLayer));
                capture(context, "gif_outline_" + frame(layer - 1));
            }
        }
        LOGGER.info("Showcase screenshots taken");
    }

    // Two digits, so the frames sort in order
    private static String frame(int index) {
        return String.format("%02d", index);
    }

    // Eye position -> the tp command takes the feet position, and aims from the feet too
    private static void camera(TestServerContext server, double x, double eyeY, double z, double lookX, double lookY, double lookZ) {
        server.runCommand(String.format("tp @a %.2f %.2f %.2f facing %.2f %.2f %.2f", x, eyeY - EYE_HEIGHT, z, lookX, lookY - EYE_HEIGHT, lookZ));
    }

    private static void shot(ClientGameTestContext context, TestServerContext server, String name, double x, double eyeY, double z, double lookX, double lookY, double lookZ) {
        camera(server, x, eyeY, z, lookX, lookY, lookZ);
        context.waitTicks(10);
        capture(context, name);
    }

    private static void capture(ClientGameTestContext context, String name) {
        context.waitTicks(8);
        context.waitFor(client -> client.levelRenderer.hasRenderedAllSections(), 400);
        context.waitTicks(3);
        context.takeScreenshot(name);
    }

    private static void setStack(TestServerContext server, BlockPos pos, List<Block> layers) {
        server.runOnServer(minecraftServer -> StackableCarpet.place(minecraftServer.overworld(), pos, layers));
    }

    // White, gray on top, then moss, pale moss and every other color - 16 layers
    private static List<Block> mixedLayers() {
        List<Block> layers = new ArrayList<>(List.of(WHITE, GRAY, Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET));
        for (DyeColor color : DyeColor.values()) {
            Block carpet = Blocks.CARPET.pick(color);
            if (layers.size() < StackableCarpet.MAX_LAYERS && carpet != WHITE && carpet != GRAY) {
                layers.add(carpet);
            }
        }
        return layers;
    }

    // Plank floor with stone brick backdrops behind each scene
    private static void buildPlatform(TestServerContext server) {
        server.runCommand("fill -30 100 -20 80 110 22 minecraft:air");
        server.runCommand("fill -30 99 -20 80 99 22 minecraft:grass_block");
        server.runCommand("fill -30 98 -20 80 98 22 minecraft:dirt");
        server.runCommand("fill -12 99 -3 72 99 4 minecraft:spruce_planks");
        server.runCommand("fill -12 100 -3 72 102 -3 minecraft:stone_bricks");
        server.runCommand("fill -12 103 -3 72 103 -3 minecraft:stone_brick_slab");
        server.runCommand("kill @e[type=!minecraft:player]");
    }

    // White carpet from 1 layer up to 15, then the wool block the 16th turns it into
    private static void buildStaircase(TestServerContext server) {
        for (int layers = 1; layers <= StackableCarpet.MAX_SINGLE_COLOR_LAYERS; layers++) {
            setStack(server, new BlockPos(-9 + layers, 100, 0), Collections.nCopies(layers, WHITE));
        }
        server.runCommand("setblock 7 100 0 minecraft:white_wool");
    }

    // A few mixed stacks side by side
    private static void buildMixedRow(TestServerContext server) {
        List<Block> stripes = new ArrayList<>();
        List<Block> mossStripes = new ArrayList<>();
        for (int i = 0; i < StackableCarpet.MAX_LAYERS; i++) {
            stripes.add(i % 2 == 0 ? WHITE : GRAY);
            mossStripes.add(i % 2 == 0 ? Blocks.MOSS_CARPET : Blocks.PALE_MOSS_CARPET);
        }
        List<Block> halves = new ArrayList<>(Collections.nCopies(5, WHITE));
        halves.addAll(Collections.nCopies(5, Blocks.CARPET.pick(DyeColor.BLUE)));
        List<Block> sandwich = new ArrayList<>(Collections.nCopies(3, Blocks.CARPET.pick(DyeColor.GREEN)));
        sandwich.addAll(Collections.nCopies(3, Blocks.CARPET.pick(DyeColor.WHITE)));
        sandwich.addAll(Collections.nCopies(3, Blocks.CARPET.pick(DyeColor.RED)));

        setStack(server, new BlockPos(12, 100, 0), mixedLayers());
        setStack(server, new BlockPos(14, 100, 0), stripes);
        setStack(server, new BlockPos(16, 100, 0), halves);
        setStack(server, new BlockPos(18, 100, 0), mossStripes);
        setStack(server, new BlockPos(20, 100, 0), sandwich);
    }

    // Moss and pale moss carpet stacks next to their full blocks - the pale moss against a wall, growing up it
    private static void buildMossRow(TestServerContext server) {
        server.runCommand("setblock 25 100 0 minecraft:moss_block");
        setStack(server, new BlockPos(26, 100, 0), Collections.nCopies(12, Blocks.MOSS_CARPET));
        setStack(server, new BlockPos(27, 100, 0), Collections.nCopies(6, Blocks.MOSS_CARPET));

        server.runCommand("fill 30 100 -1 33 102 -1 minecraft:stone_bricks");
        server.runCommand("setblock 30 100 0 minecraft:pale_moss_block");
        setStack(server, new BlockPos(31, 100, 0), Collections.nCopies(12, Blocks.PALE_MOSS_CARPET));
        setStack(server, new BlockPos(32, 100, 0), Collections.nCopies(6, Blocks.PALE_MOSS_CARPET));
        server.runOnServer(minecraftServer -> {
            ServerLevel level = minecraftServer.overworld();
            var hangingMoss = Blocks.PALE_MOSS_CARPET.defaultBlockState()
                    .setValue(MossyCarpetBlock.BASE, false)
                    .setValue(MossyCarpetBlock.NORTH, WallSide.TALL);
            level.setBlock(new BlockPos(31, 101, 0), hangingMoss, 3);
            level.setBlock(new BlockPos(32, 101, 0), hangingMoss.setValue(MossyCarpetBlock.NORTH, WallSide.LOW), 3);
        });
    }
}
