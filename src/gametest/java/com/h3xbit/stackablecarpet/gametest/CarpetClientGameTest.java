package com.h3xbit.stackablecarpet.gametest;

import com.h3xbit.stackablecarpet.StackableCarpet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

// Carpet in a real client + integrated server, driven with real mouse input:
// - white carpet stacked 1 -> 15 layers then into a wool block, then a 15 stack broken layer by
//   layer from the top and one layer at a time from the side
// - moss and pale moss carpet stacked into a moss / pale moss block, and broken from the side
// - mixed carpets: each layer keeps its own carpet, a mixed stack goes to 16 and never turns into a
//   full block, and broken layers drop as their own carpet
// Checks the hover outline, the layers on client and server, the drops and the place/break sounds
// at every step.
// Run with: ./gradlew runClientGameTest   (screenshots end up in build/run/clientGameTest/screenshots)
public class CarpetClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("stackable_carpet-gametest");
    private static final int SETTLE_TICKS = 3;
    private static final BlockPos CARPET = new BlockPos(0, 100, 0);
    private static final Block WHITE = Blocks.CARPET.pick(DyeColor.WHITE);
    private static final Block GRAY = Blocks.CARPET.pick(DyeColor.GRAY);
    private static final String PLACE_SOUND = ".place";
    private static final String BREAK_SOUND = ".break";
    // White, then gray on top of it, gray again, moss, pale moss, then dyed carpets - 16 layers, a full block
    private static final List<Block> MIXED = mixedLayers();

    private final List<String> sounds = new CopyOnWriteArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> report = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        if (ShowcaseClientGameTest.enabled() || MapClientGameTest.enabled()) {
            return;
        }
        SoundEventListener listener = (sound, events, range) -> sounds.add(sound.getIdentifier().toString());
        context.runOnClient(client -> client.getSoundManager().addListener(listener));

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode survival @a");
            server.runCommand("time set noon");
            // Survival so breaking drops items, Haste so each layer takes 2 ticks of mining instead of 8
            server.runCommand("effect give @a minecraft:haste infinite 255 true");
            context.waitTicks(SETTLE_TICKS);

            runBesideWoolCase(context, server);
            runPlaceCase(context, server, WHITE);
            runBreakFromTopCase(context, server);
            for (int layer = 1; layer <= StackableCarpet.MAX_SINGLE_COLOR_LAYERS; layer++) {
                runBreakFromSideCase(context, server, WHITE, layer);
            }

            for (Block moss : List.of(Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET)) {
                runPlaceCase(context, server, moss);
                runBreakFromSideCase(context, server, moss, 6);
                runBreakFromSideCase(context, server, moss, 1);
            }

            runMixedPlaceCase(context, server);
            runMixedOntoFullSingleColorCase(context, server);
            runMixedBreakFromTopCase(context, server);
            for (int layer = 1; layer <= StackableCarpet.MAX_LAYERS; layer++) {
                runMixedBreakFromSideCase(context, server, layer);
            }
            runMixedPickCase(context, server);
            runMixedShowcase(context, server);
        } finally {
            context.runOnClient(client -> client.getSoundManager().removeListener(listener));
        }

        LOGGER.info("==== Carpet report ====\n{}", String.join("\n", report));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " carpet check(s) failed:\n" + String.join("\n", failures));
        }
        LOGGER.info("All carpet checks passed");
    }

    // Right-click a carpet onto the floor, then onto the top of the stack, up to 15 layers and then into its full block
    private void runPlaceCase(ClientGameTestContext context, TestServerContext server, Block carpet) {
        String prefix = "place_" + shortName(carpet) + "_";
        resetArea(context, server);
        standAt(context, server, 2.5);

        for (int expected = 1; expected <= StackableCarpet.MAX_SINGLE_COLOR_LAYERS; expected++) {
            placeOnTop(context, server, prefix + expected, carpet, carpet(Collections.nCopies(expected, carpet)));
        }
        placeOnTop(context, server, prefix + "16", carpet, StackableCarpet.fullBlockFor(carpet).getName().getString());
    }

    // Stacks right next to wool blocks, to compare the shading by eye in the screenshots
    private void runBesideWoolCase(ClientGameTestContext context, TestServerContext server) {
        resetArea(context, server);
        int[] row = {16, 15, 12, 8, 4, 1, 16};
        server.runOnServer(minecraftServer -> {
            for (int i = 0; i < row.length; i++) {
                for (int z = 0; z < 2; z++) {
                    BlockPos pos = CARPET.offset(i - 3, 0, z);
                    if (row[i] > StackableCarpet.MAX_SINGLE_COLOR_LAYERS) {
                        minecraftServer.overworld().setBlock(pos, Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState(), 3);
                    } else {
                        StackableCarpet.place(minecraftServer.overworld(), pos, Collections.nCopies(row[i], WHITE));
                    }
                }
            }
            minecraftServer.overworld().setBlock(CARPET.offset(0, 0, -1), Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState(), 3);
        });
        server.runCommand(String.format("tp @a %.1f %d %.1f facing %.1f %.1f %.1f", CARPET.getX() + 0.5, CARPET.getY() + 1, CARPET.getZ() - 3.5, CARPET.getX() + 0.5, CARPET.getY() + 0.3, CARPET.getZ() + 0.5));
        context.waitTicks(SETTLE_TICKS * 2);
        context.takeScreenshot("beside_wool_front");
        server.runCommand(String.format("tp @a %.1f %d %.1f facing %.1f %.1f %.1f", CARPET.getX() + 4.5, CARPET.getY() + 2, CARPET.getZ() - 3.5, CARPET.getX() + 0.5, CARPET.getY() + 0.3, CARPET.getZ() + 0.5));
        context.waitTicks(SETTLE_TICKS);
        context.takeScreenshot("beside_wool_angle");
    }

    // A 15 stack, aimed at the top face each time: the outline should be the top layer and each break takes one layer
    private void runBreakFromTopCase(ClientGameTestContext context, TestServerContext server) {
        resetArea(context, server);
        setStack(server, Collections.nCopies(StackableCarpet.MAX_SINGLE_COLOR_LAYERS, WHITE));
        standAt(context, server, 2.5);

        for (int layers = StackableCarpet.MAX_SINGLE_COLOR_LAYERS; layers >= 1; layers--) {
            String name = "top_layer_" + layers;
            aimAt(context, new Vec3(CARPET.getX() + 0.5, CARPET.getY() + layers / 16.0, CARPET.getZ() + 0.5));
            checkOutline(context, name, layers);
            breakAimed(context, server, name, Collections.nCopies(layers - 1, WHITE), List.of(WHITE));
        }
    }

    // A fresh 15 stack each time, aimed at one layer on its east face
    private void runBreakFromSideCase(ClientGameTestContext context, TestServerContext server, Block carpet, int layer) {
        String name = "side_" + shortName(carpet) + "_layer_" + layer;
        resetArea(context, server);
        setStack(server, Collections.nCopies(StackableCarpet.MAX_SINGLE_COLOR_LAYERS, carpet));
        standAt(context, server, 3.5);

        aimAt(context, new Vec3(CARPET.getX() + 1.0, CARPET.getY() + (layer - 0.5) / 16.0, CARPET.getZ() + 0.5));
        checkOutline(context, name, layer);
        breakAimed(context, server, name, Collections.nCopies(layer - 1, carpet),
                Collections.nCopies(StackableCarpet.MAX_SINGLE_COLOR_LAYERS - layer + 1, carpet));
    }

    // White, gray on top, gray again, moss, ... each layer keeps its carpet, all the way to 16; a full mixed
    // stack doesn't become a full block, the next carpet goes on the block above instead
    private void runMixedPlaceCase(ClientGameTestContext context, TestServerContext server) {
        resetArea(context, server);
        standAt(context, server, 2.5);

        for (int layers = 1; layers <= StackableCarpet.MAX_LAYERS; layers++) {
            placeOnTop(context, server, "mixed_place_" + layers, MIXED.get(layers - 1), carpet(MIXED.subList(0, layers)));
        }
        placeOnTop(context, server, "mixed_place_17", WHITE, carpet(MIXED));
        String above = context.computeOnClient((Minecraft client) -> describeAt(client.level, CARPET.above()));
        String aboveOnServer = server.computeOnServer(minecraftServer -> describeAt(minecraftServer.overworld(), CARPET.above()));
        String wantAbove = carpet(List.of(WHITE));
        report.add(String.format("[mixed_place_17 above] server=%s client=%s", aboveOnServer, above));
        if (!aboveOnServer.equals(wantAbove) || !above.equals(wantAbove)) {
            failures.add("mixed_place_17: expected " + wantAbove + " on the block above the full mixed stack, server " + aboveOnServer + ", client " + above);
        }
    }

    // 15 white + gray = 16 separate layers, not a wool block; and moss / pale moss onto white mix the same way
    private void runMixedOntoFullSingleColorCase(ClientGameTestContext context, TestServerContext server) {
        List<Block> white = Collections.nCopies(StackableCarpet.MAX_SINGLE_COLOR_LAYERS, WHITE);
        for (Block top : List.of(GRAY, Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET)) {
            resetArea(context, server);
            setStack(server, white);
            standAt(context, server, 2.5);
            placeOnTop(context, server, "mixed_" + shortName(top) + "_onto_full_white", top, carpet(Stream.concat(white.stream(), Stream.of(top)).toList()));
        }
    }

    // Breaking the top layers off until only one carpet is left turns it back into a plain carpet stack
    private void runMixedBreakFromTopCase(ClientGameTestContext context, TestServerContext server) {
        List<Block> layers = List.of(Blocks.PALE_MOSS_CARPET, Blocks.PALE_MOSS_CARPET, Blocks.PALE_MOSS_CARPET, Blocks.MOSS_CARPET, GRAY);
        resetArea(context, server);
        setStack(server, layers);
        standAt(context, server, 2.5);

        for (int count = layers.size(); count >= 1; count--) {
            String name = "mixed_top_layer_" + count;
            aimAt(context, new Vec3(CARPET.getX() + 0.5, CARPET.getY() + count / 16.0, CARPET.getZ() + 0.5));
            checkOutline(context, name, count);
            breakAimed(context, server, name, layers.subList(0, count - 1), List.of(layers.get(count - 1)));
        }
    }

    // A fresh full mixed stack each time: everything from the aimed layer up drops as its own carpet
    private void runMixedBreakFromSideCase(ClientGameTestContext context, TestServerContext server, int layer) {
        String name = "mixed_side_layer_" + layer;
        resetArea(context, server);
        setStack(server, MIXED);
        standAt(context, server, 3.5);

        aimAt(context, new Vec3(CARPET.getX() + 1.0, CARPET.getY() + (layer - 0.5) / 16.0, CARPET.getZ() + 0.5));
        checkOutline(context, name, layer);
        breakAimed(context, server, name, MIXED.subList(0, layer - 1), MIXED.subList(layer - 1, MIXED.size()));
    }

    // Middle-click picks the carpet in the aimed layer of a mixed stack
    private void runMixedPickCase(ClientGameTestContext context, TestServerContext server) {
        resetArea(context, server);
        setStack(server, MIXED);
        standAt(context, server, 3.5);
        server.runCommand("gamemode creative @a");

        // Middle-click picks the carpet in the aimed layer
        for (int layer : new int[]{1, 5, 16}) {
            aimAt(context, new Vec3(CARPET.getX() + 1.0, CARPET.getY() + (layer - 0.5) / 16.0, CARPET.getZ() + 0.5));
            context.getInput().pressKey(options -> options.keyPickItem);
            context.waitTicks(SETTLE_TICKS);

            String picked = server.computeOnServer(minecraftServer -> BuiltInRegistries.ITEM.getKey(
                    minecraftServer.getPlayerList().getPlayers().getFirst().getMainHandItem().getItem()).getPath());
            String wanted = shortName(MIXED.get(layer - 1));
            report.add(String.format("[mixed_pick_layer_%d] picked %s, want %s", layer, picked, wanted));
            if (!picked.equals(wanted)) {
                failures.add("mixed_pick_layer_" + layer + ": picked " + picked + " instead of " + wanted);
            }
        }

        server.runCommand("gamemode survival @a");
    }

    private void runMixedShowcase(ClientGameTestContext context, TestServerContext server) {
        resetArea(context, server);
        server.runOnServer(minecraftServer -> {
            Level level = minecraftServer.overworld();
            level.setBlock(CARPET.offset(-3, 0, 0), Blocks.MOSS_BLOCK.defaultBlockState(), 3);
            StackableCarpet.place(level, CARPET.offset(-2, 0, 0), Collections.nCopies(8, Blocks.MOSS_CARPET));
            StackableCarpet.place(level, CARPET.offset(-1, 0, 0), MIXED);
            List<Block> stripes = new ArrayList<>();
            for (int i = 0; i < StackableCarpet.MAX_LAYERS; i++) {
                stripes.add(i % 2 == 0 ? Blocks.MOSS_CARPET : Blocks.PALE_MOSS_CARPET);
            }
            StackableCarpet.place(level, CARPET, stripes);
            StackableCarpet.place(level, CARPET.offset(1, 0, 0), Collections.nCopies(12, Blocks.PALE_MOSS_CARPET));
            List<Block> half = new ArrayList<>(Collections.nCopies(4, WHITE));
            half.addAll(Collections.nCopies(4, Blocks.MOSS_CARPET));
            StackableCarpet.place(level, CARPET.offset(2, 0, 0), half);
            level.setBlock(CARPET.offset(3, 0, 0), Blocks.PALE_MOSS_BLOCK.defaultBlockState(), 3);
        });
        server.runCommand(String.format("tp @a %.1f %d %.1f facing %.1f %.1f %.1f", CARPET.getX() + 2.5, CARPET.getY() + 1, CARPET.getZ() - 3.0, CARPET.getX() + 0.5, CARPET.getY() + 0.4, CARPET.getZ() + 0.5));
        context.waitTicks(SETTLE_TICKS * 2);
        context.takeScreenshot("mixed_showcase");
    }

    // Right-clicks this carpet onto the top of whatever is at CARPET
    private void placeOnTop(ClientGameTestContext context, TestServerContext server, String name, Block carpet, String want) {
        server.runCommand("item replace entity @a weapon.mainhand with " + BuiltInRegistries.BLOCK.getKey(carpet) + " 64");
        int layers = server.computeOnServer(minecraftServer -> {
            BlockState state = minecraftServer.overworld().getBlockState(CARPET);
            return state.hasProperty(StackableCarpet.LAYERS) ? state.getValue(StackableCarpet.LAYERS) : 0;
        });
        aimAt(context, new Vec3(CARPET.getX() + 0.5, CARPET.getY() + layers / 16.0, CARPET.getZ() + 0.5));

        sounds.clear();
        context.getInput().holdKeyFor(options -> options.keyUse, 1);
        context.waitTicks(SETTLE_TICKS);

        String onServer = server.computeOnServer(minecraftServer -> describeAt(minecraftServer.overworld(), CARPET));
        String onClient = context.computeOnClient((Minecraft client) -> describeAt(client.level, CARPET));
        boolean placeSound = sounds.stream().anyMatch(sound -> sound.endsWith(PLACE_SOUND));
        report.add(String.format("[%s] placed %s: server=%s client=%s placeSound=%s", name, shortName(carpet), onServer, onClient, placeSound));
        if (!onServer.equals(want)) {
            failures.add(name + ": expected " + want + " on the server, got " + onServer);
        }
        if (!onClient.equals(onServer)) {
            failures.add(name + ": client " + onClient + " disagrees with server " + onServer);
        }
        if (!placeSound) {
            failures.add(name + ": no place sound (heard " + sounds + ")");
        }
    }

    private void checkOutline(ClientGameTestContext context, String name, int layer) {
        AABB outline = context.computeOnClient(client -> {
            BlockOutlineRenderState state;
            try {
                Field field = LevelExtractor.class.getDeclaredField("levelRenderState");
                field.setAccessible(true);
                state = ((LevelRenderState) field.get(client.levelExtractor)).blockOutlineRenderState;
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException(exception);
            }
            if (state == null || !state.pos().equals(CARPET) || state.shape().isEmpty()) {
                return null;
            }
            return state.shape().bounds();
        });
        double wantMin = (layer - 1) / 16.0;
        double wantMax = layer / 16.0;
        report.add(String.format("[%s hover] outline=%s (want y %.4f..%.4f)", name, outline == null ? "none" : String.format("y %.4f..%.4f", outline.minY, outline.maxY), wantMin, wantMax));

        if (outline == null) {
            failures.add(name + ": no outline on the carpet");
        } else if (Math.abs(outline.minY - wantMin) > 1e-6 || Math.abs(outline.maxY - wantMax) > 1e-6
                || outline.minX != 0 || outline.maxX != 1 || outline.minZ != 0 || outline.maxZ != 1) {
            failures.add(name + ": outline " + outline + " is not just layer " + layer);
        }
    }

    // Holds attack in survival until the hovered layer goes, like a player would
    private void breakAimed(ClientGameTestContext context, TestServerContext server, String name, List<Block> wantLeft, List<Block> wantDrops) {
        server.runCommand("kill @e[type=minecraft:item]");
        context.waitTicks(2);
        sounds.clear();
        BlockState before = clientState(context);

        context.getInput().holdKey(options -> options.keyAttack);
        int ticks = 0;
        while (clientState(context).equals(before) && ticks < 20) {
            context.waitTick();
            ticks++;
        }
        context.getInput().releaseKey(options -> options.keyAttack);
        // What the client shows the moment the layer goes - anything but the final stack would be a flicker
        String firstSeen = context.computeOnClient((Minecraft client) -> describeAt(client.level, CARPET));
        context.waitTicks(SETTLE_TICKS);

        String onServer = server.computeOnServer(minecraftServer -> describeAt(minecraftServer.overworld(), CARPET));
        String onClient = context.computeOnClient((Minecraft client) -> describeAt(client.level, CARPET));
        Map<String, Integer> drops = server.computeOnServer(minecraftServer -> {
            Map<String, Integer> counted = new TreeMap<>();
            for (ItemEntity item : minecraftServer.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(CARPET).inflate(3))) {
                if (item.getItem().getItem() instanceof BlockItem blockItem) {
                    counted.merge(shortName(blockItem.getBlock()), item.getItem().getCount(), Integer::sum);
                }
            }
            return counted;
        });
        Map<String, Integer> wantDropCounts = new TreeMap<>();
        for (Block carpet : wantDrops) {
            wantDropCounts.merge(shortName(carpet), 1, Integer::sum);
        }
        boolean breakSound = sounds.stream().anyMatch(sound -> sound.endsWith(BREAK_SOUND));
        report.add(String.format("[%s break] took %d ticks, server=%s client=%s drops=%s breakSound=%s", name, ticks, onServer, onClient, drops, breakSound));

        String want = carpet(wantLeft);
        if (ticks >= 20) {
            failures.add(name + ": holding attack never broke anything");
        }
        if (!onServer.equals(want)) {
            failures.add(name + ": expected " + want + " left on the server, got " + onServer);
        }
        if (!onClient.equals(onServer)) {
            failures.add(name + ": client " + onClient + " disagrees with server " + onServer);
        }
        if (!firstSeen.equals(want)) {
            failures.add(name + ": flickered - the client showed " + firstSeen + " before " + want);
        }
        if (!drops.equals(wantDropCounts)) {
            failures.add(name + ": expected drops " + wantDropCounts + ", got " + drops);
        }
        if (!breakSound) {
            failures.add(name + ": no break sound (heard " + sounds + ")");
        }
    }

    private static void resetArea(ClientGameTestContext context, TestServerContext server) {
        server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air", CARPET.getX() - 5, CARPET.getY(), CARPET.getZ() - 5, CARPET.getX() + 5, CARPET.getY() + 4, CARPET.getZ() + 5));
        server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:stone", CARPET.getX() - 5, CARPET.getY() - 1, CARPET.getZ() - 5, CARPET.getX() + 5, CARPET.getY() - 1, CARPET.getZ() + 5));
        server.runCommand("kill @e[type=minecraft:item]");
        context.waitTicks(2);
    }

    private static void setStack(TestServerContext server, List<Block> layers) {
        server.runOnServer(minecraftServer -> StackableCarpet.place(minecraftServer.overworld(), CARPET, layers));
    }

    private static void standAt(ClientGameTestContext context, TestServerContext server, double eastOffset) {
        server.runCommand(String.format("tp @a %.1f %d %.1f", CARPET.getX() + eastOffset, CARPET.getY(), CARPET.getZ() + 0.5));
        context.waitTicks(SETTLE_TICKS);
    }

    // Turns the player's head on the client; the server picks up the rotation from the next movement packet
    private static void aimAt(ClientGameTestContext context, Vec3 target) {
        context.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES, target));
        context.waitTicks(2);
    }

    private static BlockState clientState(ClientGameTestContext context) {
        return context.computeOnClient((Minecraft client) -> client.level.getBlockState(CARPET));
    }

    // e.g. "Mixed Carpet [white_carpet, gray_carpet, moss_carpet]", "White Carpet [white_carpet, white_carpet]", "White Wool", "Air"
    private static String describeAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        List<Block> layers = StackableCarpet.layersAt(level, pos, state);
        String name = state.getBlock().getName().getString();
        return layers.isEmpty() ? name : name + " " + layers.stream().map(CarpetClientGameTest::shortName).toList();
    }

    private static String carpet(List<Block> layers) {
        if (layers.isEmpty()) {
            return Blocks.AIR.getName().getString();
        }
        return StackableCarpet.stateFor(layers).getBlock().getName().getString() + " " + layers.stream().map(CarpetClientGameTest::shortName).toList();
    }

    private static String shortName(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private static List<Block> mixedLayers() {
        List<Block> layers = new ArrayList<>(List.of(WHITE, GRAY, GRAY, Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET));
        for (DyeColor color : DyeColor.values()) {
            Block carpet = Blocks.CARPET.pick(color);
            if (layers.size() < StackableCarpet.MAX_LAYERS && carpet != WHITE && carpet != GRAY) {
                layers.add(carpet);
            }
        }
        return List.copyOf(layers);
    }
}
