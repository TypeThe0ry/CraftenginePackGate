package org.ellan.craftenginepackgate;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Plugin(
        id = "craftengine-pack-gate",
        name = "CraftEnginePackGate",
        version = "1.2.0",
        description = "Sends the latest CraftEngine resource pack from Velocity using CraftEngine upload cache files.",
        authors = {"Ellan"}
)
public final class CraftEnginePackGatePlugin {
    private static final MinecraftChannelIdentifier REFRESH_CHANNEL = MinecraftChannelIdentifier.from("craftengine_pack_gate:refresh");
    private static final String DEFAULT_CONFIG = """
            force=false
            prompt=服务器材质包已更新，请加载以显示自定义物品和模型。
            auth-server=ellan-limbo
            minecraft-root=..
            auto-cache=true
            packs=network
            pack.network.url=https://example.invalid/resource_pack.zip
            pack.network.sha1=0000000000000000000000000000000000000000
            pack.network.id=4a475da6-9760-4fc7-91af-10dd2d5725b4
            pack.network.servers=ellan-spawn,ellan-survival,ellan-redstone,ellan-adventure
            pack.network.cache-files=01-spawn/plugins/CraftEngine/cache/gitlab.json,02-survival/plugins/CraftEngine/cache/gitlab.json,03-redstone/plugins/CraftEngine/cache/gitlab.json,04-adventure/plugins/CraftEngine/cache/gitlab.json
            """;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Path configFile;
    private final Properties config = new Properties();
    private final Map<UUID, PackDefinition> pendingPlayers = new ConcurrentHashMap<>();
    private final Set<String> appliedThisSession = ConcurrentHashMap.newKeySet();

    private String authServer;
    private boolean force;
    private String prompt;
    private Path minecraftRoot;
    private Map<String, PackConfig> packsByServer = Map.of();

    @Inject
    public CraftEnginePackGatePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configFile = dataDirectory.resolve("config.properties");
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(REFRESH_CHANNEL);
        loadConfig();
        buildPackConfigs();
        logger.info("CraftEnginePackGate enabled: server assignments={}", packsByServer.keySet());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(REFRESH_CHANNEL)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String message = new String(event.getData(), StandardCharsets.UTF_8).trim();
        String source = event.getSource() instanceof ServerConnection connection
                ? connection.getServerInfo().getName()
                : String.valueOf(event.getSource());
        loadConfig();
        buildPackConfigs();
        appliedThisSession.clear();

        int sent = 0;
        if (message.isBlank() || message.startsWith("resend")) {
            sent = resendToOnlinePlayers();
        }
        logger.info("CraftEnginePackGate refresh requested by {} message='{}'; resent={} online={}", source, message, sent, proxy.getAllPlayers().size());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (packsByServer.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        if (authServer.equals(serverName)) {
            return;
        }

        PackConfig packConfig = packsByServer.get(serverName);
        if (packConfig == null) {
            return;
        }

        Optional<PackDefinition> resolvedPack = resolvePack(packConfig);
        if (resolvedPack.isEmpty()) {
            return;
        }

        PackDefinition packDefinition = resolvedPack.get();
        if (hasApplied(player, packDefinition)) {
            return;
        }

        PackDefinition pendingPack = pendingPlayers.get(player.getUniqueId());
        if (pendingPack != null && pendingPack.sha1().equals(packDefinition.sha1())) {
            return;
        }

        appliedThisSession.add(key(player, packDefinition));
        pendingPlayers.put(player.getUniqueId(), packDefinition);
        try {
            player.sendResourcePackOffer(packDefinition.info());
            logger.info("Sent CraftEngine resource pack {} ({}) to {} on {} from {}", packDefinition.name(), packDefinition.sha1(), player.getUsername(), serverName, packDefinition.source());
        } catch (IllegalStateException exception) {
            pendingPlayers.remove(player.getUniqueId());
            logger.warn("Skipped CraftEngine resource pack {} ({}) for {} on {}: {}", packDefinition.name(), packDefinition.sha1(), player.getUsername(), serverName, exception.getMessage());
        }
    }

    @Subscribe
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PackDefinition pendingPack = pendingPlayers.get(player.getUniqueId());
        if (pendingPack == null || !pendingPack.info().getId().equals(event.getPackId())) {
            return;
        }

        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFUL) {
            appliedThisSession.add(key(player, pendingPack));
            pendingPlayers.remove(player.getUniqueId());
            logger.info("{} applied CraftEngine resource pack {} ({})", player.getUsername(), pendingPack.name(), pendingPack.sha1());
            return;
        }

        if (!status.isIntermediate()) {
            pendingPlayers.remove(player.getUniqueId());
            appliedThisSession.remove(key(player, pendingPack));
            logger.warn("{} did not apply CraftEngine resource pack {} ({}) (status: {})", player.getUsername(), pendingPack.name(), pendingPack.sha1(), status);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        String prefix = event.getPlayer().getUniqueId() + ":";
        appliedThisSession.removeIf(entry -> entry.startsWith(prefix));
        pendingPlayers.remove(event.getPlayer().getUniqueId());
    }

    private int resendToOnlinePlayers() {
        int sent = 0;
        for (Player player : proxy.getAllPlayers()) {
            Optional<ServerConnection> currentServer = player.getCurrentServer();
            if (currentServer.isEmpty()) {
                continue;
            }

            String serverName = currentServer.get().getServerInfo().getName();
            if (authServer.equals(serverName)) {
                continue;
            }

            PackConfig packConfig = packsByServer.get(serverName);
            if (packConfig == null) {
                continue;
            }

            Optional<PackDefinition> resolvedPack = resolvePack(packConfig);
            if (resolvedPack.isEmpty()) {
                continue;
            }

            PackDefinition packDefinition = resolvedPack.get();
            try {
                pendingPlayers.put(player.getUniqueId(), packDefinition);
                appliedThisSession.add(key(player, packDefinition));
                player.sendResourcePackOffer(packDefinition.info());
                sent++;
                logger.info("Resent CraftEngine resource pack {} ({}) to {} on {} from {}", packDefinition.name(), packDefinition.sha1(), player.getUsername(), serverName, packDefinition.source());
            } catch (IllegalStateException exception) {
                pendingPlayers.remove(player.getUniqueId());
                appliedThisSession.remove(key(player, packDefinition));
                logger.warn("Skipped CraftEngine resource pack resend {} ({}) for {} on {}: {}", packDefinition.name(), packDefinition.sha1(), player.getUsername(), serverName, exception.getMessage());
            }
        }
        return sent;
    }

    private void buildPackConfigs() {
        force = Boolean.parseBoolean(config.getProperty("force", "false"));
        prompt = config.getProperty("prompt", "Server resource pack updated.");
        authServer = config.getProperty("auth-server", "ellan-limbo").trim();
        minecraftRoot = resolveMinecraftRoot();
        boolean autoCache = Boolean.parseBoolean(config.getProperty("auto-cache", "true"));

        Map<String, PackConfig> assignments = new LinkedHashMap<>();
        for (String packName : splitList(config.getProperty("packs", ""))) {
            String prefix = "pack." + packName + ".";
            String staticUrl = requireConfig(prefix + "url");
            String staticSha1 = requireConfig(prefix + "sha1").toLowerCase(Locale.ROOT);
            UUID packId = UUID.fromString(requireConfig(prefix + "id"));
            List<Path> cacheFiles = autoCache ? resolveCacheFiles(prefix) : List.of();
            PackConfig packConfig = new PackConfig(packName, staticUrl, staticSha1, packId, cacheFiles);
            for (String serverName : splitList(config.getProperty(prefix + "servers", ""))) {
                assignments.put(serverName, packConfig);
            }
        }
        packsByServer = Map.copyOf(assignments);
    }

    private Optional<PackDefinition> resolvePack(PackConfig packConfig) {
        Optional<CachedPack> cachedPack = packConfig.cacheFiles().stream()
                .map(this::readCachedPack)
                .flatMap(Optional::stream)
                .max(Comparator.comparingLong(CachedPack::lastModified));

        String url = cachedPack.map(CachedPack::url).orElse(packConfig.staticUrl());
        String sha1 = cachedPack.map(CachedPack::sha1).orElse(packConfig.staticSha1()).toLowerCase(Locale.ROOT);
        String source = cachedPack.map(cached -> cached.path().toString()).orElse("config.properties");

        if (!isSha1(sha1) || url.isBlank()) {
            logger.warn("Invalid CraftEngine resource pack {} from {}: url='{}' sha1='{}'", packConfig.name(), source, url, sha1);
            return Optional.empty();
        }

        try {
            byte[] hash = HexFormat.of().parseHex(sha1);
            ResourcePackInfo info = proxy.createResourcePackBuilder(url)
                    .setId(packConfig.id())
                    .setHash(hash)
                    .setShouldForce(force)
                    .setPrompt(Component.text(prompt))
                    .build();
            return Optional.of(new PackDefinition(packConfig.name(), sha1, info, source));
        } catch (RuntimeException exception) {
            logger.warn("Failed to build CraftEngine resource pack {} from {}: {}", packConfig.name(), source, exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<CachedPack> readCachedPack(Path cacheFile) {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return Optional.empty();
            }
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            String url = extractJsonString(json, "url").orElse("").trim();
            String sha1 = extractJsonString(json, "sha1").orElse("").trim().toLowerCase(Locale.ROOT);
            if (url.isBlank() || !isSha1(sha1)) {
                return Optional.empty();
            }
            long lastModified = Files.getLastModifiedTime(cacheFile).toMillis();
            return Optional.of(new CachedPack(url, sha1, cacheFile, lastModified));
        } catch (IOException exception) {
            logger.warn("Failed to read CraftEngine upload cache {}: {}", cacheFile, exception.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> extractJsonString(String json, String key) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1).replace("\\/", "/"));
    }

    private List<Path> resolveCacheFiles(String prefix) {
        String configured = config.getProperty(prefix + "cache-files", "").trim();
        if (configured.isEmpty()) {
            configured = "01-spawn/plugins/CraftEngine/cache/gitlab.json,02-survival/plugins/CraftEngine/cache/gitlab.json,03-redstone/plugins/CraftEngine/cache/gitlab.json,04-adventure/plugins/CraftEngine/cache/gitlab.json";
        }
        return splitList(configured).stream()
                .map(Paths::get)
                .map(path -> path.isAbsolute() ? path : minecraftRoot.resolve(path).normalize())
                .toList();
    }

    private Path resolveMinecraftRoot() {
        Path absoluteDataDirectory = dataDirectory.toAbsolutePath().normalize();
        Path pluginsDirectory = absoluteDataDirectory.getParent();
        if (pluginsDirectory == null || pluginsDirectory.getParent() == null) {
            throw new IllegalStateException("Cannot resolve Velocity root from data directory: " + absoluteDataDirectory);
        }
        Path velocityRoot = pluginsDirectory.getParent();
        Path configuredRoot = Paths.get(config.getProperty("minecraft-root", "..").trim());
        if (!configuredRoot.isAbsolute()) {
            configuredRoot = velocityRoot.resolve(configuredRoot);
        }
        return configuredRoot.normalize();
    }

    private Set<String> splitList(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String requireConfig(String key) {
        String value = config.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Missing config value: " + key);
        }
        return value;
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(configFile)) {
                Files.writeString(configFile, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            }
            try (BufferedReader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                config.load(reader);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load CraftEnginePackGate config", exception);
        }
    }

    private boolean hasApplied(Player player, PackDefinition packDefinition) {
        return appliedThisSession.contains(key(player, packDefinition));
    }

    private String key(Player player, PackDefinition packDefinition) {
        return player.getUniqueId() + ":" + packDefinition.sha1();
    }

    private boolean isSha1(String value) {
        return value.matches("(?i)[0-9a-f]{40}");
    }

    private record PackConfig(String name, String staticUrl, String staticSha1, UUID id, List<Path> cacheFiles) {
    }

    private record CachedPack(String url, String sha1, Path path, long lastModified) {
    }

    private record PackDefinition(String name, String sha1, ResourcePackInfo info, String source) {
    }
}