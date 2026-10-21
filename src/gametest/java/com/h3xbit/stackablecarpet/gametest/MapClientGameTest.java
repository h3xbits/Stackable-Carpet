package com.h3xbit.stackablecarpet.gametest;

import com.h3xbit.stackablecarpet.StackableCarpet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Map colors, placed by hand like a player would: one row with each carpet on the floor, one row with each carpet on
// top of a different carpet. On a map drawn by holding it, both rows must look the same. Only runs when the
// STACKABLE_CARPET_MAP_TEST environment variable is set - the map is saved as map_placed.png next to the screenshots.
public class MapClientGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("stackable_carpet-map");
    private static final int SETTLE_TICKS = 3;
    private static final BlockPos SINGLE_ROW = new BlockPos(0, 100, 0);
    private static final BlockPos STACKED_ROW = SINGLE_ROW.south(3);

    public static boolean enabled() {
        return System.getenv("STACKABLE_CARPET_MAP_TEST") != null;
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!enabled()) {
            return;
        }
        List<String> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Block> carpets = StackableCarpet.layerCarpets();

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode survival @a");
            server.runCommand("time set noon");
            server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:air", SINGLE_ROW.getX() - 4, SINGLE_ROW.getY(), SINGLE_ROW.getZ() - 4, SINGLE_ROW.getX() + carpets.size() + 4, SINGLE_ROW.getY() + 4, STACKED_ROW.getZ() + 6));
            server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:stone", SINGLE_ROW.getX() - 4, SINGLE_ROW.getY() - 1, SINGLE_ROW.getZ() - 4, SINGLE_ROW.getX() + carpets.size() + 4, SINGLE_ROW.getY() - 1, STACKED_ROW.getZ() + 6));
            context.waitTicks(20);

            for (int index = 0; index < carpets.size(); index++) {
                placeCarpetAt(context, server, SINGLE_ROW.east(index), carpets.get(index));
            }
            for (int index = 0; index < carpets.size(); index++) {
                placeCarpetAt(context, server, STACKED_ROW.east(index), carpets.get((index + 1) % carpets.size()));
                placeCarpetAt(context, server, STACKED_ROW.east(index), carpets.get(index));
            }

            // Stand over the rows looking down at a map of them, with the HUD on so the map in hand shows
            server.runCommand(String.format("tp @a %.1f %d %.1f", SINGLE_ROW.getX() + carpets.size() / 2.0, SINGLE_ROW.getY(), STACKED_ROW.getZ() + 3.5));
            server.runOnServer(minecraftServer -> minecraftServer.getPlayerList().getPlayers().getFirst().setItemInHand(InteractionHand.MAIN_HAND,
                    MapItem.create(minecraftServer.overworld(), SINGLE_ROW.getX(), SINGLE_ROW.getZ(), (byte) 0, true, false)));
            context.runOnClient(client -> client.player.setXRot(80.0F));
            context.waitTicks(80);
            context.takeScreenshot("map_in_hand");
            saveMapPicture(context, server, "map_placed.png");

            for (int index = 0; index < carpets.size(); index++) {
                String carpetName = BuiltInRegistries.BLOCK.getKey(carpets.get(index)).getPath();
                int singleColor = mapColorAt(server, SINGLE_ROW.east(index));
                int stackedColor = mapColorAt(server, STACKED_ROW.east(index));
                report.add(String.format("[map %s] on its own=%d on top of another carpet=%d", carpetName, singleColor, stackedColor));
                if (stackedColor != singleColor) {
                    failures.add("map: " + carpetName + " on top of another carpet shows map color " + stackedColor + ", on its own " + singleColor);
                }
            }
        }

        LOGGER.info("==== Map report ====\n{}", String.join("\n", report));
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " map check(s) failed:\n" + String.join("\n", failures));
        }
        LOGGER.info("All map checks passed");
    }

    // Right-clicks a carpet onto whatever is at pos, standing just south of it
    private static void placeCarpetAt(ClientGameTestContext context, TestServerContext server, BlockPos pos, Block carpet) {
        server.runCommand("item replace entity @a weapon.mainhand with " + BuiltInRegistries.BLOCK.getKey(carpet) + " 64");
        server.runCommand(String.format("tp @a %.1f %d %.1f", pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.7));
        context.waitTicks(2);

        int layers = server.computeOnServer(minecraftServer -> {
            BlockState state = minecraftServer.overworld().getBlockState(pos);
            return StackableCarpet.layersAt(minecraftServer.overworld(), pos, state).size();
        });
        Vec3 topOfStack = new Vec3(pos.getX() + 0.5, pos.getY() + layers / 16.0, pos.getZ() + 0.5);
        context.runOnClient(client -> client.player.lookAt(EntityAnchorArgument.Anchor.EYES, topOfStack));
        context.waitTicks(2);

        context.getInput().holdKeyFor(options -> options.keyUse, 1);
        context.waitTicks(SETTLE_TICKS);
    }

    // The map color id drawn at a block on the map the player is holding
    private static int mapColorAt(TestServerContext server, BlockPos pos) {
        return server.computeOnServer(minecraftServer -> {
            ItemStack map = minecraftServer.getPlayerList().getPlayers().getFirst().getMainHandItem();
            MapItemSavedData mapData = MapItem.getSavedData(map, minecraftServer.overworld());
            int pixelX = pos.getX() - mapData.centerX + 64;
            int pixelZ = pos.getZ() - mapData.centerZ + 64;
            return (mapData.colors[pixelX + pixelZ * 128] & 0xFF) >> 2;
        });
    }

    // The held map as a picture, 4 pixels per map pixel, next to the screenshots
    private static void saveMapPicture(ClientGameTestContext context, TestServerContext server, String fileName) {
        byte[] colors = server.computeOnServer(minecraftServer -> MapItem.getSavedData(
                minecraftServer.getPlayerList().getPlayers().getFirst().getMainHandItem(), minecraftServer.overworld()).colors.clone());

        BufferedImage picture = new BufferedImage(128 * 4, 128 * 4, BufferedImage.TYPE_INT_ARGB);
        for (int pixelZ = 0; pixelZ < 128; pixelZ++) {
            for (int pixelX = 0; pixelX < 128; pixelX++) {
                int packed = colors[pixelX + pixelZ * 128] & 0xFF;
                int argb = MapColor.byId(packed >> 2).calculateARGBColor(MapColor.Brightness.values()[packed & 3]);
                for (int offsetZ = 0; offsetZ < 4; offsetZ++) {
                    for (int offsetX = 0; offsetX < 4; offsetX++) {
                        picture.setRGB(pixelX * 4 + offsetX, pixelZ * 4 + offsetZ, argb);
                    }
                }
            }
        }

        Path folder = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("screenshots"));
        try {
            Files.createDirectories(folder);
            ImageIO.write(picture, "png", folder.resolve(fileName).toFile());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
