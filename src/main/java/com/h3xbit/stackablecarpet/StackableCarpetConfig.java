package com.h3xbit.stackablecarpet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

// config/stackable_carpet.json and the admin command that changes it
public class StackableCarpetConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(StackableCarpet.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve(StackableCarpet.MOD_ID + ".json");

    // On: 16 layers of one carpet turn into its full block. Off: they stay a carpet stack of 16 layers
    public static boolean fullBlockConversion = true;
    // Off: there is no /stackablecarpet command, the file is the only way to change the conversion
    public static boolean conversionCommand = true;

    public static void load() {
        if (Files.notExists(FILE)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(FILE)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);

            if (json.has("fullBlockConversion")) {
                fullBlockConversion = json.get("fullBlockConversion").getAsBoolean();
            }
            if (json.has("conversionCommand")) {
                conversionCommand = json.get("conversionCommand").getAsBoolean();
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Couldn't read {}, using the defaults", FILE, exception);
        }
    }

    private static void save() {
        JsonObject json = new JsonObject();
        json.addProperty("fullBlockConversion", fullBlockConversion);
        json.addProperty("conversionCommand", conversionCommand);

        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException exception) {
            LOGGER.warn("Couldn't write {}", FILE, exception);
        }
    }

    // /stackablecarpet conversion shows the setting, /stackablecarpet conversion true|false changes it and saves it
    public static void registerCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (!conversionCommand) {
                return;
            }

            dispatcher.register(Commands.literal("stackablecarpet")
                    .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                    .then(Commands.literal("conversion")
                            .executes(context -> {
                                context.getSource().sendSuccess(() -> Component.literal("Full block conversion: " + fullBlockConversion), false);
                                return 1;
                            })
                            .then(Commands.argument("enabled", BoolArgumentType.bool())
                                    .executes(context -> {
                                        fullBlockConversion = BoolArgumentType.getBool(context, "enabled");
                                        save();

                                        context.getSource().sendSuccess(() -> Component.literal("Full block conversion set to " + fullBlockConversion), true);
                                        return 1;
                                    }))));
        });
    }
}
