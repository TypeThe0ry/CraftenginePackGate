package org.ellan.craftenginepackgate;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Plugin(
        id = "craftengine-pack-gate",
        name = "CraftEnginePackGate",
        version = "1.0.0",
        description = "Sends CraftEngine resource packs from Velocity once per online session and backend pack assignment.",
        authors = {"Ellan"}
)
public final class CraftEnginePackGatePlugin {
    private static final String DEFAULT_CONFIG = """
            force=false
            prompt=服务器材质包已更新，请加载以显示自定义物品和模型。
            auth-server=ellan-limbo
            packs=network,adventure
            pack.network.url=https://gitlab.com/-/project/81919457/uploads/ea10f2411750c469e43df8f13bf7a0f1/resource_pack.zip
            pack.network.sha1=cedc12d504103001c9f87700edf13786b93da7ab
            pack.network.id=a7cbba39-2d06-36f7-9098-5d6f2a00300a
            pack.network.servers=ellan-spawn,ellan-survival,ellan-redstone
            pack.adventure.url=https://gitlab.com/-/project/81919457/uploads/2105e6b3a7b4e3eff94c72fac4092882/resource_pack.zip
            pack.adventure.sha1=92c70754eca01eb1d7655444ee4aefbb8c205e35
            pack.adventure.id=a3d0d876-6431-3755-91fb-a90c9194ef4f
            pack.adventure.servers=ellan-adventure
            """;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Path configFile;
    private final Properties config = new Properties();
    private final Map<UUID, PackDefinition> pendingPlayers = new ConcurrentHashMap<>();
    private final Set<String> appliedThisSession = ConcurrentHashMap.newKeySet();

    private String authServer;
    private Map<String, PackDefinition> packsByServer;

    @Inject
    public CraftEnginePackGatePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configFile = dataDirectory.resolve("config.properties");
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        loadConfig();
        buildPackInfos();
        logger.info("CraftEnginePackGate enabled: server assignments={}", packsByServer.keySet());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (packsByServer == null || packsByServer.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        if (authServer.equals(serverName)) {
            return;
        }

        PackDefinition pack = packsByServer.get(serverName);
        if (pack == null || hasApplied(player, pack)) {
            return;
        }

        pendingPlayers.put(player.getUniqueId(), pack);
        player.sendResourcePackOffer(pack.info());
        logger.info("Sent CraftEngine resource pack {} ({}) to {} on {}", pack.name(), pack.sha1(), player.getUsername(), serverName);
    }

    @Subscribe
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PackDefinition pack = pendingPlayers.get(player.getUniqueId());
        if (pack == null || !pack.info().getId().equals(event.getPackId())) {
            return;
        }

        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFUL) {
            appliedThisSession.add(key(player, pack));
            pendingPlayers.remove(player.getUniqueId());
            logger.info("{} applied CraftEngine resource pack {} ({})", player.getUsername(), pack.name(), pack.sha1());
            return;
        }

        if (!status.isIntermediate()) {
            pendingPlayers.remove(player.getUniqueId());
            logger.warn("{} did not apply CraftEngine resource pack {} ({}) (status: {})", player.getUsername(), pack.name(), pack.sha1(), status);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        String prefix = event.getPlayer().getUniqueId() + ":";
        appliedThisSession.removeIf(key -> key.startsWith(prefix));
        pendingPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void buildPackInfos() {
        boolean force = Boolean.parseBoolean(config.getProperty("force", "false"));
        String prompt = config.getProperty("prompt", "Server resource pack updated.");
        authServer = config.getProperty("auth-server", "ellan-limbo").trim();

        Map<String, PackDefinition> assignments = new LinkedHashMap<>();
        for (String packName : splitList(config.getProperty("packs", ""))) {
            String prefix = "pack." + packName + ".";
            String url = requireConfig(prefix + "url");
            String sha1 = requireConfig(prefix + "sha1").toLowerCase(Locale.ROOT);
            UUID packId = UUID.fromString(requireConfig(prefix + "id"));
            byte[] hash = HexFormat.of().parseHex(sha1);
            ResourcePackInfo info = proxy.createResourcePackBuilder(url)
                    .setId(packId)
                    .setHash(hash)
                    .setShouldForce(force)
                    .setPrompt(Component.text(prompt))
                    .build();
            PackDefinition pack = new PackDefinition(packName, sha1, info);
            for (String serverName : splitList(config.getProperty(prefix + "servers", ""))) {
                assignments.put(serverName, pack);
            }
        }
        packsByServer = Map.copyOf(assignments);
    }

    private Set<String> splitList(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String requireConfig(String key) {
        String value = config.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return value;
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(configFile)) {
                Files.writeString(configFile, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            }
            try (var reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                config.load(reader);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load CraftEnginePackGate config", exception);
        }
    }

    private boolean hasApplied(Player player, PackDefinition pack) {
        return appliedThisSession.contains(key(player, pack));
    }

    private String key(Player player, PackDefinition pack) {
        return player.getUniqueId() + ":" + pack.sha1();
    }

    private record PackDefinition(String name, String sha1, ResourcePackInfo info) {
    }
}