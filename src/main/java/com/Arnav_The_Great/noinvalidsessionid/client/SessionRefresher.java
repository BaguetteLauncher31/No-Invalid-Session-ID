package com.Arnav_The_Great.noinvalidsessionid.client;

import com.Arnav_The_Great.noinvalidsessionid.client.mixin.MinecraftAccessor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SessionRefresher {
    private static final Logger LOGGER = LoggerFactory.getLogger("no_invalid_session_id");

    private static final AtomicBoolean isRefreshing = new AtomicBoolean(false);
    private static final AtomicLong lastRefreshTime = new AtomicLong(0);
    private static final long REFRESH_COOLDOWN = 300_000;

    private static final String CLIENT_ID = "00000000402b5328";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final String DEVICE_CODE_URL = "https://login.live.com/oauth20_connect.srf";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Path getTokenStorePath() {
        return Path.of(System.getProperty("user.home"), ".noinvalidsessionid", "token.enc");
    }

    private static SecretKey getEncryptionKey() throws Exception {
        String machineId = System.getProperty("user.name")
                + System.getProperty("os.name")
                + System.getProperty("user.home");
        byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(machineId.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }

    public static void refreshSessionQuietly() {
        if (!isRefreshing.compareAndSet(false, true)) return;

        long now = System.currentTimeMillis();
        if (now - lastRefreshTime.get() < REFRESH_COOLDOWN) {
            isRefreshing.set(false);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                String storedRefreshToken = loadRefreshToken();
                if (storedRefreshToken != null) {
                    silentRefresh(storedRefreshToken);
                } else {
                    startDeviceCodeFlow();
                }
                lastRefreshTime.set(System.currentTimeMillis());
            } catch (Exception e) {
                LOGGER.error("Session refresh failed", e);
            } finally {
                isRefreshing.set(false);
            }
        });
    }

    private static void silentRefresh(String refreshToken) throws IOException, InterruptedException {
        LOGGER.info("Silently refreshing session using stored refresh token...");

        String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                + "&grant_type=refresh_token"
                + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);

        JsonObject msaResponse = postForm(TOKEN_URL, body);
        if (!msaResponse.has("access_token")) {
            LOGGER.warn("Silent refresh failed — token expired. Clearing and starting device flow.");
            clearRefreshToken();
            startDeviceCodeFlow();
            return;
        }

        String msaAccessToken = msaResponse.get("access_token").getAsString();
        String newRefreshToken = msaResponse.get("refresh_token").getAsString();
        saveRefreshToken(newRefreshToken);

        String minecraftToken = doXblXstsMinecraftChain(msaAccessToken);
        updateSession(minecraftToken);
        LOGGER.info("Session silently refreshed successfully.");
    }

    private static void startDeviceCodeFlow() throws IOException, InterruptedException {
        LOGGER.info("Starting Microsoft device code flow...");

        String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8)
                + "&response_type=device_code";

        JsonObject deviceResponse = postForm(DEVICE_CODE_URL, body);
        if (!deviceResponse.has("device_code")) {
            LOGGER.error("Failed to get device code. Response: {}", deviceResponse);
            return;
        }

        String deviceCode = deviceResponse.get("device_code").getAsString();
        String userCode = deviceResponse.get("user_code").getAsString();
        String verificationUrl = deviceResponse.get("verification_uri").getAsString();
        int interval = deviceResponse.has("interval") ? deviceResponse.get("interval").getAsInt() : 5;

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new ConfirmScreen(
                confirmed -> minecraft.setScreen(null),
                Component.literal("§eSession Refresh Required"),
                Component.literal("§fGo to: §b" + verificationUrl + "\n§fEnter code: §a" + userCode + "\n§7(Check logs to copy the code)")
        )));

        LOGGER.info("========================");
        LOGGER.info("[NoInvalidSession] Session refresh required!");
        LOGGER.info("[NoInvalidSession] Go to: {}", verificationUrl);
        LOGGER.info("[NoInvalidSession] Enter code: {}", userCode);
        LOGGER.info("========================");

        String pollBody = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8)
                + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";

        long deadline = System.currentTimeMillis() + 300_000;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            JsonObject pollResponse = postForm(TOKEN_URL, pollBody);

            if (pollResponse.has("access_token")) {
                String msaAccessToken = pollResponse.get("access_token").getAsString();
                String refreshToken = pollResponse.get("refresh_token").getAsString();
                saveRefreshToken(refreshToken);

                String minecraftToken = doXblXstsMinecraftChain(msaAccessToken);
                updateSession(minecraftToken);

                minecraft.execute(() -> {
                    minecraft.setScreen(null);
                    if (minecraft.player != null) {
                        minecraft.player.sendSystemMessage(Component.literal(
                                "§a[NoInvalidSession] Session refreshed! You're good to go."
                        ));
                    }
                });
                LOGGER.info("Device code flow completed. Session updated.");
                return;
            }

            if (pollResponse.has("error")) {
                String error = pollResponse.get("error").getAsString();
                if ("authorization_pending".equals(error)) continue;
                if ("slow_down".equals(error)) { interval += 5; continue; }
                LOGGER.error("Device code flow error: {}", error);
                return;
            }
        }

        LOGGER.warn("Device code flow timed out.");
        minecraft.execute(() -> minecraft.setScreen(null));
    }

    private static String doXblXstsMinecraftChain(String msaAccessToken) throws IOException, InterruptedException {
        JsonObject xblResponse = postJson(
                "https://user.auth.xboxlive.com/user/authenticate",
                String.format("""
                {
                    "Properties": {
                        "AuthMethod": "RPS",
                        "SiteName": "user.auth.xboxlive.com",
                        "RpsTicket": "t=%s"
                    },
                    "RelyingParty": "http://auth.xboxlive.com",
                    "TokenType": "JWT"
                }""", msaAccessToken)
        );
        String xblToken = xblResponse.get("Token").getAsString();
        String userHash = xblResponse
                .getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui")
                .get(0).getAsJsonObject()
                .get("uhs").getAsString();

        JsonObject xstsResponse = postJson(
                "https://xsts.auth.xboxlive.com/xsts/authorize",
                String.format("""
                {
                    "Properties": {
                        "SandboxId": "RETAIL",
                        "UserTokens": ["%s"]
                    },
                    "RelyingParty": "rp://api.minecraftservices.com/",
                    "TokenType": "JWT"
                }""", xblToken)
        );
        String xstsToken = xstsResponse.get("Token").getAsString();

        JsonObject mcResponse = postJson(
                "https://api.minecraftservices.com/authentication/login_with_xbox",
                String.format("{\"identityToken\": \"XBL3.0 x=%s;%s\"}", userHash, xstsToken)
        );
        return mcResponse.get("access_token").getAsString();
    }

    private static void updateSession(String newToken) throws IOException, InterruptedException {
        HttpRequest profileRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.minecraftservices.com/minecraft/profile"))
                .header("Authorization", "Bearer " + newToken)
                .GET()
                .build();
        HttpResponse<String> profileResponse = HTTP.send(profileRequest, HttpResponse.BodyHandlers.ofString());
        if (profileResponse.statusCode() != 200) {
            throw new IOException("Failed to verify Minecraft profile: HTTP " + profileResponse.statusCode());
        }

        JsonObject profile = JsonParser.parseString(profileResponse.body()).getAsJsonObject();
        String verifiedName = profile.get("name").getAsString();
        String verifiedUuidRaw = profile.get("id").getAsString();
        UUID verifiedUuid = UUID.fromString(
                verifiedUuidRaw.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                        "$1-$2-$3-$4-$5"
                )
        );

        Minecraft minecraft = Minecraft.getInstance();
        User currentUser = minecraft.getUser();
        User newSession = new User(
                verifiedName,
                verifiedUuid,
                newToken,
                currentUser.getXuid(),
                currentUser.getClientId()
        );
        minecraft.execute(() -> ((MinecraftAccessor) minecraft).setSessionUser(newSession));
    }

    private static String loadRefreshToken() {
        try {
            Path path = getTokenStorePath();
            if (!Files.exists(path)) return null;

            byte[] encrypted = Files.readAllBytes(path);
            byte[] iv = Arrays.copyOfRange(encrypted, 0, 12);
            byte[] cipherText = Arrays.copyOfRange(encrypted, 12, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getEncryptionKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not load stored refresh token — may need to re-authenticate", e);
            return null;
        }
    }

    private static void saveRefreshToken(String refreshToken) {
        try {
            Path path = getTokenStorePath();
            Files.createDirectories(path.getParent());

            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));

            byte[] stored = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, stored, 0, iv.length);
            System.arraycopy(encrypted, 0, stored, iv.length, encrypted.length);

            Files.write(path, stored);
            try {
                Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(path, ownerOnly);
            } catch (UnsupportedOperationException ignored) {}

            LOGGER.info("Refresh token saved securely.");
        } catch (Exception e) {
            LOGGER.warn("Could not save refresh token", e);
        }
    }

    private static void clearRefreshToken() {
        try {
            Files.deleteIfExists(getTokenStorePath());
        } catch (Exception e) {
            LOGGER.warn("Could not clear refresh token", e);
        }
    }

    private static JsonObject postJson(String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url + ": " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static JsonObject postForm(String url, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 400) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url + ": " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}