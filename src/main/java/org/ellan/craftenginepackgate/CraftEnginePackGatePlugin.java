package org.ellan.craftenginepackgate;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Plugin(
        id = "craftengine-pack-gate",
        name = "CraftEnginePackGate",
        version = "1.4.0",
        description = "Sends mandatory CraftEngine resource packs after players join backend servers.",
        authors = {"Ellan"},
        dependencies = {@Dependency(id = "eaglerxserver", optional = true)}
)
public final class CraftEnginePackGatePlugin {
    private static final MinecraftChannelIdentifier EAGLER_STATUS_CHANNEL = MinecraftChannelIdentifier.from("craftenginepackgate:eagler");
    private static final String DEFAULT_CONFIG = """
            force=true
            prompt=Server resource pack updated. Please load it to display custom items and models.
            decline-message=You must accept the server resource pack to play on Ellan Network.
            auth-server=ellan-limbo
            minecraft-root=..
            auto-cache=true
            skip-eagler-players=true
            send-delay-millis=1200
            eagler-tab-footer-hide=false
            eagler-tab-footer-delay-millis=750
            eagler-tab-footer-repeat-millis=750
            eagler-tab-header=
            eagler-tab-footer=
            eagler-status-forward=true
            eagler-status-forward-delay-millis=250
            block-eagler-tpsbar-command=false
            block-eagler-tpsbar-message=TPS bar is disabled for Eaglercraft clients.
            packs=network
            pack.network.url=https://gitlab.com/-/project/81919457/uploads/5c170053d4b5ca4a6eb0af8e26a2dbee/resource_pack.zip
            pack.network.sha1=4af0034bbf720cffdd9a39b0e6cb8033f620b338
            pack.network.id=4a475da6-9760-4fc7-91af-10dd2d5725b4
            pack.network.servers=ellan-spawn,ellan-survival,ellan-redstone,ellan-adventure
            pack.network.cache-files=01-spawn/plugins/CraftEngine/cache/gitlab.json,02-survival/plugins/CraftEngine/cache/gitlab.json,03-redstone/plugins/CraftEngine/cache/gitlab.json,04-adventure/plugins/CraftEngine/cache/gitlab.json
            """;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Path configFile;
    private final Path appliedHashesFile;
    private final Properties config = new Properties();
    private final Map<UUID, String> appliedHashes = new ConcurrentHashMap<>();
    private final Map<UUID, PackDefinition> pendingPlayers = new ConcurrentHashMap<>();
    private final Set<String> appliedThisSession = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notifiedEaglerPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> eaglerTabFooterTasks = new ConcurrentHashMap<>();

    private String authServer;
    private boolean force;
    private boolean autoCache;
    private boolean skipEaglerPlayers;
    private boolean hideEaglerTabFooter;
    private boolean forwardEaglerStatus;
    private boolean blockEaglerTpsbarCommand;
    private boolean eaglerApiWarningLogged;
    private long sendDelayMillis;
    private long eaglerTabFooterDelayMillis;
    private long eaglerTabFooterRepeatMillis;
    private long eaglerStatusForwardDelayMillis;
    private String prompt;
    private String declineMessage;
    private String eaglerSkipMessage;
    private String eaglerTabHeader;
    private String eaglerTabFooter;
    private String blockEaglerTpsbarMessage;
    private Path minecraftRoot;
    private Map<String, PackConfig> packsByServer;

    @Inject
    public CraftEnginePackGatePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.configFile = dataDirectory.resolve("config.properties");
        this.appliedHashesFile = dataDirectory.resolve("applied-hashes.properties");
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        loadConfig();
        loadAppliedHashes();
        buildPackConfigs();
        proxy.getChannelRegistrar().register(EAGLER_STATUS_CHANNEL);
        logger.info("CraftEnginePackGate enabled: force={}, sendDelayMillis={}, skipEaglerPlayers={}, forwardEaglerStatus={}, hideEaglerTabFooter={}, server assignments={}", force, sendDelayMillis, skipEaglerPlayers, forwardEaglerStatus, hideEaglerTabFooter, packsByServer.keySet());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        cancelEaglerTabFooterOverride(player.getUniqueId());

        if (authServer.equals(serverName)) {
            return;
        }

        if (forwardEaglerStatus) {
            scheduleEaglerStatusForward(player, serverName);
        }

        if (hideEaglerTabFooter) {
            scheduleEaglerTabFooterOverride(player, serverName);
        }

        if (packsByServer == null || packsByServer.isEmpty()) {
            return;
        }

        PackConfig pack = packsByServer.get(serverName);
        if (pack == null) {
            return;
        }

        proxy.getScheduler()
                .buildTask(this, () -> sendPackIfStillNeeded(player.getUniqueId(), serverName, pack))
                .delay(Duration.ofMillis(sendDelayMillis))
                .schedule();
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
            appliedHashes.put(player.getUniqueId(), pack.sha1());
            saveAppliedHashes();
            pendingPlayers.remove(player.getUniqueId());
            logger.info("{} applied mandatory CraftEngine resource pack {} ({})", player.getUsername(), pack.name(), pack.sha1());
            return;
        }

        if (status.isIntermediate()) {
            logger.info("{} resource pack {} status: {}", player.getUsername(), pack.name(), status);
            return;
        }

        pendingPlayers.remove(player.getUniqueId());
        appliedThisSession.remove(key(player, pack));
        logger.warn("{} did not apply mandatory CraftEngine resource pack {} ({}) (status: {})", player.getUsername(), pack.name(), pack.sha1(), status);
        if (force) {
            player.disconnect(Component.text(declineMessage));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        String prefix = event.getPlayer().getUniqueId() + ":";
        appliedThisSession.removeIf(key -> key.startsWith(prefix));
        pendingPlayers.remove(event.getPlayer().getUniqueId());
        notifiedEaglerPlayers.remove(event.getPlayer().getUniqueId());
        cancelEaglerTabFooterOverride(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!blockEaglerTpsbarCommand || !(event.getCommandSource() instanceof Player player)) {
            return;
        }

        String command = event.getCommand().trim().toLowerCase(Locale.ROOT);
        if (!command.equals("tpsbar") && !command.startsWith("tpsbar ")
                && !command.equals("purpur:tpsbar") && !command.startsWith("purpur:tpsbar ")) {
            return;
        }

        if (!isEaglerPlayer(player)) {
            return;
        }

        event.setResult(CommandExecuteEvent.CommandResult.denied());
        if (!blockEaglerTpsbarMessage.isBlank()) {
            player.sendMessage(Component.text(blockEaglerTpsbarMessage));
        }
        logger.info("Blocked TPS bar command '{}' for Eagler player {}", event.getCommand(), player.getUsername());
    }

    private void sendPackIfStillNeeded(UUID playerId, String serverName, PackConfig packConfig) {
        Optional<Player> optionalPlayer = proxy.getPlayer(playerId);
        if (optionalPlayer.isEmpty()) {
            return;
        }

        Player player = optionalPlayer.get();
        String currentServer = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        if (!serverName.equals(currentServer)) {
            return;
        }

        Optional<PackDefinition> resolvedPack = resolvePack(packConfig);
        if (resolvedPack.isEmpty()) {
            logger.warn("No valid CraftEngine resource pack available for {} on {}", player.getUsername(), serverName);
            return;
        }

        PackDefinition pack = resolvedPack.get();
        if (appliedThisSession.contains(key(player, pack)) || pack.sha1().equals(appliedHashes.get(player.getUniqueId()))) {
            return;
        }

        if (skipEaglerPlayers && isEaglerPlayer(player)) {
            if (!eaglerSkipMessage.isBlank() && notifiedEaglerPlayers.add(player.getUniqueId())) {
                player.sendMessage(Component.text(eaglerSkipMessage));
            }
            logger.info("Skipped mandatory Java resource pack {} ({}) for Eagler player {} on {}", pack.name(), pack.sha1(), player.getUsername(), serverName);
            return;
        }

        pendingPlayers.put(player.getUniqueId(), pack);
        try {
            player.sendResourcePackOffer(pack.info());
            logger.info("Sent mandatory CraftEngine resource pack {} ({}, force={}) to {} on {} from {}", pack.name(), pack.sha1(), force, player.getUsername(), serverName, pack.source());
        } catch (IllegalStateException exception) {
            pendingPlayers.remove(player.getUniqueId());
            logger.warn("Skipped mandatory CraftEngine resource pack {} ({}) for {} on {}: {}", pack.name(), pack.sha1(), player.getUsername(), serverName, exception.getMessage());
        }
    }

    private void scheduleEaglerTabFooterOverride(Player player, String serverName) {
        if (!isEaglerPlayer(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        ScheduledTask task = proxy.getScheduler()
                .buildTask(this, () -> applyEaglerTabFooterOverride(playerId, serverName))
                .delay(Duration.ofMillis(eaglerTabFooterDelayMillis))
                .repeat(Duration.ofMillis(eaglerTabFooterRepeatMillis))
                .schedule();
        eaglerTabFooterTasks.put(playerId, task);
        logger.info("Hiding TAB TPS footer for Eagler player {} on {}", player.getUsername(), serverName);
    }

    private void applyEaglerTabFooterOverride(UUID playerId, String serverName) {
        Optional<Player> optionalPlayer = proxy.getPlayer(playerId);
        if (optionalPlayer.isEmpty()) {
            cancelEaglerTabFooterOverride(playerId);
            return;
        }

        Player player = optionalPlayer.get();
        String currentServer = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        if (!serverName.equals(currentServer)) {
            cancelEaglerTabFooterOverride(playerId);
            return;
        }

        player.sendPlayerListHeaderAndFooter(componentOrEmpty(eaglerTabHeader), componentOrEmpty(eaglerTabFooter));
    }

    private void cancelEaglerTabFooterOverride(UUID playerId) {
        ScheduledTask task = eaglerTabFooterTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void scheduleEaglerStatusForward(Player player, String serverName) {
        boolean eaglerPlayer = isEaglerPlayer(player);
        forwardEaglerStatusIfStillConnected(player.getUniqueId(), serverName, eaglerPlayer);
        proxy.getScheduler()
                .buildTask(this, () -> forwardEaglerStatusIfStillConnected(player.getUniqueId(), serverName, eaglerPlayer))
                .delay(Duration.ofMillis(eaglerStatusForwardDelayMillis))
                .schedule();
        proxy.getScheduler()
                .buildTask(this, () -> forwardEaglerStatusIfStillConnected(player.getUniqueId(), serverName, eaglerPlayer))
                .delay(Duration.ofMillis(Math.max(1000L, eaglerStatusForwardDelayMillis * 4L)))
                .schedule();
    }

    private void forwardEaglerStatusIfStillConnected(UUID playerId, String serverName, boolean eaglerPlayer) {
        Optional<Player> optionalPlayer = proxy.getPlayer(playerId);
        if (optionalPlayer.isEmpty()) {
            return;
        }

        Player player = optionalPlayer.get();
        Optional<ServerConnection> currentServer = player.getCurrentServer();
        if (currentServer.isEmpty() || !serverName.equals(currentServer.get().getServerInfo().getName())) {
            return;
        }

        currentServer.get().sendPluginMessage(EAGLER_STATUS_CHANNEL, new byte[] { (byte) (eaglerPlayer ? 1 : 0) });
        logger.debug("Forwarded Eagler status {} for {} to {}", eaglerPlayer, player.getUsername(), serverName);
    }

    private Component componentOrEmpty(String text) {
        if (text == null || text.isBlank()) {
            return Component.empty();
        }
        return Component.text(text);
    }

    private boolean isEaglerPlayer(Player player) {
        try {
            Class<?> velocityApiClass = Class.forName("net.lax1dude.eaglercraft.backend.server.api.velocity.EaglerXServerAPI");
            Object api = velocityApiClass.getMethod("instance").invoke(null);
            if (api == null) {
                return false;
            }
            Class<?> apiInterface = Class.forName("net.lax1dude.eaglercraft.backend.server.api.IEaglerXServerAPI");
            Object result = apiInterface.getMethod("isEaglerPlayerByUUID", UUID.class).invoke(api, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (ClassNotFoundException exception) {
            return false;
        } catch (ReflectiveOperationException | LinkageError exception) {
            if (!eaglerApiWarningLogged) {
                eaglerApiWarningLogged = true;
                logger.warn("Could not query EaglerXServer API; Java resource packs will be sent to all players until this is fixed: {}", exception.getMessage());
            }
            return false;
        }
    }

    private void buildPackConfigs() {
        force = Boolean.parseBoolean(config.getProperty("force", "true"));
        autoCache = Boolean.parseBoolean(config.getProperty("auto-cache", "true"));
        skipEaglerPlayers = Boolean.parseBoolean(config.getProperty("skip-eagler-players", "true"));
        hideEaglerTabFooter = Boolean.parseBoolean(config.getProperty("eagler-tab-footer-hide", "false"));
        forwardEaglerStatus = Boolean.parseBoolean(config.getProperty("eagler-status-forward", "true"));
        blockEaglerTpsbarCommand = Boolean.parseBoolean(config.getProperty("block-eagler-tpsbar-command", "false"));
        sendDelayMillis = Math.max(0L, Long.parseLong(config.getProperty("send-delay-millis", "1200")));
        eaglerTabFooterDelayMillis = Math.max(0L, Long.parseLong(config.getProperty("eagler-tab-footer-delay-millis", "750")));
        eaglerTabFooterRepeatMillis = Math.max(250L, Long.parseLong(config.getProperty("eagler-tab-footer-repeat-millis", "750")));
        eaglerStatusForwardDelayMillis = Math.max(0L, Long.parseLong(config.getProperty("eagler-status-forward-delay-millis", "250")));
        prompt = config.getProperty("prompt", "Server resource pack updated.");
        declineMessage = config.getProperty("decline-message", "You must accept the server resource pack to play.");
        eaglerSkipMessage = config.getProperty("eagler-skip-message", "Eaglercraft does not support this Java resource pack prompt. Use Java Edition for the full resource pack.");
        eaglerTabHeader = config.getProperty("eagler-tab-header", "");
        eaglerTabFooter = config.getProperty("eagler-tab-footer", "");
        blockEaglerTpsbarMessage = config.getProperty("block-eagler-tpsbar-message", "TPS bar is disabled for Eaglercraft clients.");
        authServer = config.getProperty("auth-server", "ellan-limbo").trim();
        minecraftRoot = resolveMinecraftRoot();

        Map<String, PackConfig> assignments = new LinkedHashMap<>();
        for (String packName : splitList(config.getProperty("packs", ""))) {
            String prefix = "pack." + packName + ".";
            PackConfig pack = new PackConfig(
                    packName,
                    requireConfig(prefix + "url"),
                    requireConfig(prefix + "sha1").toLowerCase(Locale.ROOT),
                    UUID.fromString(requireConfig(prefix + "id")),
                    splitList(config.getProperty(prefix + "cache-files", ""))
            );
            for (String serverName : splitList(config.getProperty(prefix + "servers", ""))) {
                assignments.put(serverName, pack);
            }
        }
        packsByServer = Map.copyOf(assignments);
    }

    private Optional<PackDefinition> resolvePack(PackConfig packConfig) {
        String url = packConfig.url();
        String sha1 = packConfig.sha1();
        String source = "config";

        if (autoCache) {
            for (Path cacheFile : resolveCacheFiles(packConfig)) {
                Optional<CachedPack> cachedPack = readCachedPack(cacheFile);
                if (cachedPack.isPresent() && isSha1(cachedPack.get().sha1())) {
                    url = cachedPack.get().url();
                    sha1 = cachedPack.get().sha1().toLowerCase(Locale.ROOT);
                    source = cacheFile.toString();
                    break;
                }
            }
        }

        if (!isSha1(sha1)) {
            logger.warn("Invalid SHA-1 for CraftEngine resource pack {}: {}", packConfig.name(), sha1);
            return Optional.empty();
        }

        ResourcePackInfo info = proxy.createResourcePackBuilder(url)
                .setId(packConfig.id())
                .setHash(HexFormat.of().parseHex(sha1))
                .setShouldForce(force)
                .setPrompt(Component.text(prompt))
                .build();
        return Optional.of(new PackDefinition(packConfig.name(), sha1, info, source));
    }

    private Optional<CachedPack> readCachedPack(Path cacheFile) {
        if (!Files.isRegularFile(cacheFile)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            Optional<String> url = extractJsonString(json, "url");
            Optional<String> sha1 = extractJsonString(json, "sha1").or(() -> extractJsonString(json, "hash"));
            if (url.isPresent() && sha1.isPresent()) {
                return Optional.of(new CachedPack(url.get(), sha1.get()));
            }
        } catch (IOException exception) {
            logger.warn("Failed to read CraftEngine pack cache {}: {}", cacheFile, exception.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIndex = json.indexOf(pattern);
        if (keyIndex < 0) {
            return Optional.empty();
        }
        int colonIndex = json.indexOf(':', keyIndex + pattern.length());
        if (colonIndex < 0) {
            return Optional.empty();
        }
        int firstQuote = json.indexOf('"', colonIndex + 1);
        if (firstQuote < 0) {
            return Optional.empty();
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int index = firstQuote + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (escaped) {
                value.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                continue;
            }
            if (character == '"') {
                return Optional.of(value.toString());
            }
            value.append(character);
        }
        return Optional.empty();
    }

    private Set<Path> resolveCacheFiles(PackConfig packConfig) {
        return packConfig.cacheFiles().stream()
                .map(Path::of)
                .map(path -> path.isAbsolute() ? path : minecraftRoot.resolve(path).normalize())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Path resolveMinecraftRoot() {
        String configuredRoot = config.getProperty("minecraft-root", "..").trim();
        Path root = Path.of(configuredRoot);
        if (!root.isAbsolute()) {
            root = dataDirectory.resolve(root).normalize();
        }
        return root;
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

    private void loadAppliedHashes() {
        if (!Files.isRegularFile(appliedHashesFile)) {
            return;
        }
        Properties hashes = new Properties();
        try (var reader = Files.newBufferedReader(appliedHashesFile, StandardCharsets.UTF_8)) {
            hashes.load(reader);
            for (String playerId : hashes.stringPropertyNames()) {
                String sha1 = hashes.getProperty(playerId, "").toLowerCase(Locale.ROOT);
                if (isSha1(sha1)) {
                    appliedHashes.put(UUID.fromString(playerId), sha1);
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            logger.warn("Failed to load applied resource-pack hashes: {}", exception.getMessage());
        }
    }

    private void saveAppliedHashes() {
        Properties hashes = new Properties();
        appliedHashes.forEach((playerId, sha1) -> hashes.setProperty(playerId.toString(), sha1));
        try (var writer = Files.newBufferedWriter(appliedHashesFile, StandardCharsets.UTF_8)) {
            hashes.store(writer, "Resource packs successfully applied by player UUID");
        } catch (IOException exception) {
            logger.warn("Failed to save applied resource-pack hashes: {}", exception.getMessage());
        }
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

    private boolean isSha1(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{40}");
    }

    private String key(Player player, PackDefinition pack) {
        return player.getUniqueId() + ":" + pack.sha1();
    }

    private record PackConfig(String name, String url, String sha1, UUID id, Set<String> cacheFiles) {
    }

    private record CachedPack(String url, String sha1) {
    }

    private record PackDefinition(String name, String sha1, ResourcePackInfo info, String source) {
    }
}
