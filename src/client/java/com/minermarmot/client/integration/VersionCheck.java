package com.minermarmot.client.integration;

import com.google.gson.JsonParser;
import com.minermarmot.Main;
import com.minermarmot.client.screen.ConfigScreen;
import com.minermarmot.client.screen.VersionScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class VersionCheck {
    private static final URI ENDPOINT = URI.create("https://api.rawcode.fr/minermarmot/v1/version");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static volatile Result result = new Result(false, "", "checking");
    private static CompletableFuture<?> request;
    private static boolean checking;
    private static boolean prompted;

    public record Result(boolean compatible, String requiredVersion, String status) {}

    private VersionCheck() {}

    public static void initialize() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> check());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { if (request != null) request.cancel(true); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!isCompatible() && !result.status().equals("checking") && !prompted
                    && client.gui.overlay() == null && client.gui.screen() != null) {
                prompted = true;
                client.setScreenAndShow(new VersionScreen(client.gui.screen()));
            }
        });
    }

    public static String installedVersion() {
        return FabricLoader.getInstance().getModContainer(Main.MOD_ID).orElseThrow().getMetadata().getVersion().getFriendlyString();
    }

    public static boolean isCompatible() { return result.compatible(); }
    public static Result result() { return result; }
    public static Screen configScreen(Screen parent) {
        if (isCompatible()) return new ConfigScreen(parent);
        prompted = true;
        return parent instanceof VersionScreen ? parent : new VersionScreen(parent);
    }
    public static void retry() { check(); }

    private static void check() {
        if (checking) return;
        checking = true;
        result = new Result(false, "", "checking");
        var query = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").header("Cache-Control", "no-cache").GET().build();
        request = HTTP.sendAsync(query, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> error == null ? parseResponse(response.statusCode(), response.body(), installedVersion())
                        : new Result(false, "", "unavailable"))
                .thenAccept(checked -> Minecraft.getInstance().execute(() -> {
                    result = checked;
                    checking = false;
                    if (!checked.compatible()) Main.LOGGER.warn("MinerMarmot version check: {} (required: {})", checked.status(), checked.requiredVersion());
                }));
    }

    static Result parseResponse(int code, String body, String installed) {
        try {
            if (code != 200 || body == null || body.length() > 4096) return new Result(false, "", "unavailable");
            var json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("status") || !json.get("status").isJsonPrimitive() || !json.getAsJsonPrimitive("status").isString()
                    || !json.get("status").getAsString().equals("ok") || !json.has("version")
                    || !json.get("version").isJsonPrimitive() || !json.getAsJsonPrimitive("version").isString()) {
                return new Result(false, "", "unavailable");
            }
            String required = json.get("version").getAsString();
            if (!required.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")) return new Result(false, "", "unavailable");
            boolean matches = required.equals(installed);
            return new Result(matches, required, matches ? "compatible" : "outdated");
        } catch (RuntimeException exception) {
            return new Result(false, "", "unavailable");
        }
    }
}
