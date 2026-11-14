/* Adapted from Comet. SPDX-License-Identifier: GPL-3.0-only */
package dev.argus.client.modules.misc;

import dev.argus.client.ClientContext;
import dev.argus.client.chat.ChatSender;
import dev.argus.client.chat.LobbyAccounts;
import dev.argus.client.chat.LobbyLocation;
import dev.argus.client.chat.LobbyLogin;
import dev.argus.client.chat.LobbyLoginHold;
import dev.argus.client.chat.LobbyPortalFinder;
import dev.argus.client.chat.SetHomeStore;
import dev.argus.client.events.EventHandler;
import dev.argus.client.events.MinecraftDisconnectEvent;
import dev.argus.client.events.MinecraftTickEvent;
import dev.argus.client.events.PacketEvent;
import dev.argus.client.integrations.baritone.BaritoneBridge;
import dev.argus.client.modules.Module;
import dev.argus.client.network.ConnectionProfiles;
import dev.argus.client.network.ProfileRuntime;
import dev.argus.client.modules.ModuleCategory;
import dev.argus.client.settings.BooleanSetting;
import dev.argus.client.settings.NumberSetting;
import dev.argus.client.settings.Setting;
import dev.argus.client.settings.StringSetting;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AutoLobbyModule extends Module {
    private final Minecraft mc = Minecraft.getInstance();
    private final Setting<String> loginServer = add(new StringSetting("Login Server", ""));
    private final Setting<String> loginAccount = add(new StringSetting("Login Account", ""));
    private final Setting<String> loginPassword = add(new StringSetting("Login Password", ""));
    private final Setting<Integer> loginTimeout = add(new NumberSetting<>("Login Timeout", 200, 20, 1200));
    private final Setting<Integer> postLoginDelay = add(new NumberSetting<>("Post Login Delay", 60, 0, 600));
    private final Setting<Boolean> enterPortal = add(new BooleanSetting("Enter Portal", true));
    private final Setting<Boolean> useBaritone = add(new BooleanSetting("Use Baritone", true));
    private final Setting<Integer> searchRadius = add(new NumberSetting<>("Search Radius", 64, 8, 128));
    private final Setting<Integer> searchHeight = add(new NumberSetting<>("Search Height", 32, 4, 128));
    private final Setting<Integer> joinDelay = add(new NumberSetting<>("Join Delay", 40, 0, 200));
    private final Setting<Integer> rescanDelay = add(new NumberSetting<>("Rescan Delay", 100, 20, 600));

    private final LobbyLoginHold loginHold = new LobbyLoginHold();
    private final Map<BlockPos, Long> failedPortals = new HashMap<>();
    private ClientPacketListener connection;
    private ClientLevel world;
    private String brand;
    private LobbyLocation location = LobbyLocation.NONE;
    private LobbyLogin.Result loginResult = LobbyLogin.Result.IGNORED;
    private boolean loginSent;
    private boolean warnedMissingLogin;
    private int loginTicks;
    private int settleTicks;
    private int scanTicks;
    private BlockPos portal;
    private BlockPos pathGoal;
    private int pathTicks;
    private int stuckTicks;
    private Vec3 lastPosition;
    private boolean holdingMovement;

    private final LobbyPortalFinder.Probe probe = new LobbyPortalFinder.Probe() {
        @Override
        public boolean loaded(int chunkX, int chunkZ) {
            return world.hasChunk(chunkX, chunkZ);
        }

        @Override
        public boolean maybePortal(int chunkX, int sectionY, int chunkZ) {
            var chunk = world.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) return false;
            int index = chunk.getSectionIndexFromSectionY(sectionY);
            var sections = chunk.getSections();
            return index >= 0 && index < sections.length
                && sections[index].maybeHas(state -> state.is(Blocks.NETHER_PORTAL));
        }

        @Override
        public boolean portal(BlockPos pos) {
            return loaded(pos.getX() >> 4, pos.getZ() >> 4) && world.getBlockState(pos).is(Blocks.NETHER_PORTAL)
                && failedPortals.keySet().stream().noneMatch(failed -> failed.distSqr(pos) <= 64);
        }
    };

    public AutoLobbyModule() {
        super("AutoLobby", "Logs in and enters a Nether portal from supported server lobbies.", ModuleCategory.MISC);
    }

    /** Name of the built-in preset that mirrors Comet AutoLobby's defaults for 6b6t. */
    public static final String PRESET_6B6T = "6b6t";
    private static final String SERVER_6B6T = "6b6t.org";
    /** Comet's AutoLobby reconnects after 3.5 s by default; Argus does that through AutoReconnect. */
    private static final float RECONNECT_6B6T_SECONDS = 3.5F;

    /**
     * Sets AutoLobby up the way Comet's AutoLobby is by default, aimed at 6b6t: same timings, scan sizes and portal
     * handling, plus AutoReconnect on at 3.5 s. Login Account and Login Password are personal and left alone.
     */
    public void apply6b6tPreset() {
        loginServer.setValue(SERVER_6B6T);
        loginTimeout.setValue(200);
        postLoginDelay.setValue(60);
        enterPortal.setValue(true);
        useBaritone.setValue(true);
        searchRadius.setValue(64);
        searchHeight.setValue(32);
        joinDelay.setValue(40);
        rescanDelay.setValue(100);
        if (ClientContext.moduleRegistry.getModule(AutoReconnectModule.class) instanceof AutoReconnectModule reconnect) {
            reconnect.minutesSetting.setValue(0);
            reconnect.secondsSetting.setValue(RECONNECT_6B6T_SECONDS);
            reconnect.enable();
        }
        ClientContext.configurationManager.saveAsync();
    }

    public boolean matches6b6tPreset() {
        boolean reconnectMatches = !(ClientContext.moduleRegistry.getModule(AutoReconnectModule.class) instanceof AutoReconnectModule reconnect)
            || reconnect.isToggled() && reconnect.minutesSetting.getValue() == 0
                && reconnect.secondsSetting.getValue() == RECONNECT_6B6T_SECONDS;
        return SERVER_6B6T.equalsIgnoreCase(loginServer.getValue().strip()) && loginTimeout.getValue() == 200
            && postLoginDelay.getValue() == 60 && enterPortal.getValue() && useBaritone.getValue()
            && searchRadius.getValue() == 64 && searchHeight.getValue() == 32 && joinDelay.getValue() == 40
            && rescanDelay.getValue() == 100 && reconnectMatches;
    }

    @Override
    public void onDisable() {
        stopWalking();
        connection = null;
        world = null;
        brand = null;
        location = LobbyLocation.NONE;
        loginHold.clear();
        loginResult = LobbyLogin.Result.IGNORED;
        loginSent = false;
        warnedMissingLogin = false;
        loginTicks = 0;
    }

    @EventHandler
    private void onDisconnect(MinecraftDisconnectEvent event) {
        onDisable();
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.getCurrentServer() == null) {
            onDisable();
            return;
        }

        if (connection != mc.getConnection()) {
            onDisable();
            connection = mc.getConnection();
        }
        updateLocation();
        if (!location.lobby() || location == LobbyLocation.WAITING_AREA) return;
        if (location.requiresLogin(LobbyAccounts.cracked(mc.getUser().getProfileId(), mc.getUser().getAccessToken()))
            && !finishLogin()) return;
        if (loginHold.hold(connection)) return;

        if (!enterPortal.getValue() || mc.screen != null || !mc.player.isAlive() || mc.player.isPassenger()
            || mc.player.isFallFlying()) {
            stopWalking();
            return;
        }
        if (settleTicks++ < joinDelay.getValue()) return;
        if (insidePortal()) {
            stopWalking();
            return;
        }
        if (portal == null && scanTicks-- <= 0) {
            scanTicks = rescanDelay.getValue();
            findPortal();
        }
        if (portal != null) walkToPortal();
    }

    private void updateLocation() {
        String currentBrand = connection.serverBrand();
        var player = mc.player;
        LobbyLocation detected = LobbyLocation.detect(currentBrand, mc.level.dimension().identifier().toString(),
            player.getX(), player.getY(), player.getZ());
        boolean newWorld = world != mc.level;
        LobbyLocation next = newWorld || !Objects.equals(brand, currentBrand) ? detected : location.afterRescan(detected);
        if (newWorld || next != location) {
            stopWalking();
            failedPortals.clear();
            scanTicks = 0;
            settleTicks = 0;
            if (newWorld) loginHold.worldChanged(connection, postLoginDelay.getValue());
        }
        world = mc.level;
        brand = currentBrand;
        location = next;
    }

    private boolean finishLogin() {
        if (loginResult == LobbyLogin.Result.SUCCESS
            || loginSent && loginResult == LobbyLogin.Result.IGNORED && loginTicks >= loginTimeout.getValue()) {
            if (!loginHold.loggedInOn(connection)) {
                loginHold.loggedIn(connection, postLoginDelay.getValue());
            }
            return true;
        }
        if (loginSent) {
            loginTicks++;
            return false;
        }
        String server = loginServer.getValue();
        String command = null;
        if (!server.isBlank()
            && SetHomeStore.normaliseServer(server).equals(SetHomeStore.normaliseServer(mc.getCurrentServer().ip))) {
            String password = passwordFor(mc.getUser().getName());
            if (password != null && password.chars().noneMatch(Character::isISOControl)) command = LobbyLogin.command(password);
        }
        if (command == null) {
            if (!warnedMissingLogin) {
                notice("Log in manually, or set Login Server and give " + mc.getUser().getName()
                    + " a lobby password in Accounts (or use Login Account and Login Password).");
                warnedMissingLogin = true;
            }
            return false;
        }
        if (mc.screen != null || ++loginTicks < Math.max(20, joinDelay.getValue())) return false;
        loginSent = true;
        loginTicks = 0;
        connection.sendCommand(command);
        return false;
    }

    /**
     * The /login password for the account being played: the lobby password saved with that cracked account in the
     * account switcher first, so switching accounts switches passwords, then the single Login Account / Password pair.
     */
    private String passwordFor(String username) {
        ConnectionProfiles profiles = ProfileRuntime.profiles();
        String saved = profiles == null ? null : profiles.lobbyPasswordFor(username);
        if (saved != null) return saved;
        return loginAccount.getValue().strip().equalsIgnoreCase(username) ? loginPassword.getValue() : null;
    }

    /** How many saved cracked accounts have a lobby password. */
    public static int accountsWithLobbyPassword() {
        ConnectionProfiles profiles = ProfileRuntime.profiles();
        if (profiles == null) return 0;
        int count = 0;
        for (var account : profiles.accounts()) if (profiles.lobbyPassword(account) != null) count++;
        return count;
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.getPacket() instanceof ClientboundSystemChatPacket packet) || packet.overlay()) return;
        ClientPacketListener receivedOn = mc.getConnection();
        mc.execute(() -> {
            if (!isToggled() || receivedOn == null || receivedOn != connection || receivedOn != mc.getConnection()
                || location != LobbyLocation.CRACKED_LOBBY || loginResult == LobbyLogin.Result.SUCCESS) return;
            String message = packet.content().getString();
            if (ChatSender.fromPlayer(message, name -> receivedOn.getPlayerInfoIgnoreCase(name) != null)) return;
            LobbyLogin.Result result = LobbyLogin.classify(message);
            if (result == LobbyLogin.Result.SUCCESS
                || loginSent && loginResult != LobbyLogin.Result.FAILURE && result == LobbyLogin.Result.FAILURE) {
                loginResult = result;
                if (result == LobbyLogin.Result.FAILURE) {
                    loginHold.rejected();
                    notice("The server rejected /login. Log in manually to continue.");
                } else {
                    loginHold.loggedIn(receivedOn, postLoginDelay.getValue());
                }
            }
        });
    }

    private void findPortal() {
        failedPortals.entrySet().removeIf(entry -> System.nanoTime() - entry.getValue() >= 0);
        var found = LobbyPortalFinder.nearest(mc.player.position(), searchRadius.getValue(), searchHeight.getValue(),
            world.getMinY(), world.getMaxY() - 1, probe);
        if (found.isEmpty()) return;
        portal = found.get().pos();
        lastPosition = mc.player.position();
        stuckTicks = 0;
        pathTicks = 0;
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (useBaritone.getValue() && bridge != null && bridge.isAvailable()
            && !bridge.isPathingOrCalculating() && Vec3.atCenterOf(portal).distanceToSqr(lastPosition) > 16) {
            bridge.pathTo(portal);
            pathGoal = portal;
        }
    }

    private void walkToPortal() {
        var player = mc.player;
        Vec3 center = Vec3.atCenterOf(portal);
        if (pathGoal != null) {
            BaritoneBridge bridge = ClientContext.baritoneBridge;
            if (++pathTicks <= 400 && center.distanceToSqr(player.position()) > 16
                && (pathTicks <= 20 || bridge.isPathingOrCalculating())) return;
            stopPathing();
            lastPosition = player.position();
        }
        if (player.position().distanceToSqr(lastPosition) >= 0.0025) {
            lastPosition = player.position();
            stuckTicks = 0;
        } else if (++stuckTicks > 100) {
            if (failedPortals.size() >= 16) failedPortals.clear();
            failedPortals.put(portal, System.nanoTime() + 120_000_000_000L);
            stopWalking();
            scanTicks = 0;
            return;
        }
        double dx = center.x - player.getX();
        double dz = center.z - player.getZ();
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(player.onGround() && (player.horizontalCollision || center.y > player.getY() + 1.1));
        holdingMovement = true;
    }

    private boolean insidePortal() {
        var body = mc.player.getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
            BlockPos.containing(body.maxX, body.maxY, body.maxZ))) {
            if (probe.loaded(pos.getX() >> 4, pos.getZ() >> 4)
                && world.getBlockState(pos).is(Blocks.NETHER_PORTAL)
                && body.intersects(new AABB(pos))) return true;
        }
        return false;
    }

    private void stopPathing() {
        if (pathGoal == null) return;
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge != null && bridge.isAvailable() && pathGoal.equals(bridge.getGoalPosition())) bridge.cancelPathing();
        pathGoal = null;
    }

    private void stopWalking() {
        stopPathing();
        portal = null;
        if (!holdingMovement) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        holdingMovement = false;
    }

    private void notice(String message) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[AutoLobby] " + message), false);
    }
}
