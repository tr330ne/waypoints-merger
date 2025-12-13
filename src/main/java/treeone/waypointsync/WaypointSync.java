package treeone.waypointsync;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.client.api.system.waypoint.IWaypointManager;
import org.rusherhack.client.api.system.waypoint.Waypoint;
import org.rusherhack.client.api.utils.objects.Dimension;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;
import org.rusherhack.core.setting.EnumSetting;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaeroplus.feature.waypoint.WaypointAPI;
import xaeroplus.mixin.client.AccessorWaypointSet;

import java.util.*;

public class WaypointSync extends ToggleableModule {

    private enum DimensionFilter {
        ALL, OVERWORLD, NETHER, END
    }

    private final EnumSetting<DimensionFilter> dimensionFilter = new EnumSetting<>("Dimension", DimensionFilter.ALL);
    private final BooleanSetting includeDeathpoints = new BooleanSetting("Deathpoints", true);
    private final BooleanSetting showInfo = new BooleanSetting("ShowInfo", true);

    private boolean isProcessing = false;
    private boolean hasJoinedWorld = false;
    private int lastXaeroCount = 0;
    private int lastRusherCount = 0;
    private DimensionFilter lastDimensionFilter = DimensionFilter.ALL;
    private boolean lastIncludeDeathpoints = false;

    public WaypointSync() {
        super("WaypointSync", "Automatically synchronizes waypoints from Xaero to Rusher", ModuleCategory.MISC);

        dimensionFilter.setDescription("Which dimension to sync");
        includeDeathpoints.setDescription("Include deathpoints in sync");
        showInfo.setDescription("Show result messages");

        this.registerSettings(dimensionFilter, includeDeathpoints, showInfo);
    }

    @Subscribe
    public void onUpdate(EventUpdate event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null) {
            hasJoinedWorld = false;
            lastXaeroCount = 0;
            lastRusherCount = 0;
            return;
        }

        if (!hasJoinedWorld) {
            hasJoinedWorld = true;
            lastDimensionFilter = dimensionFilter.getValue();
            lastIncludeDeathpoints = includeDeathpoints.getValue();
            performSync();
            updateCounts();
            return;
        }

        boolean settingsChanged = lastDimensionFilter != dimensionFilter.getValue() ||
                lastIncludeDeathpoints != includeDeathpoints.getValue();

        int currentXaeroCount = getXaeroWaypointCount();
        int currentRusherCount = getRusherWaypointCount();

        if (settingsChanged || currentXaeroCount != lastXaeroCount || currentRusherCount != lastRusherCount) {
            lastDimensionFilter = dimensionFilter.getValue();
            lastIncludeDeathpoints = includeDeathpoints.getValue();
            performSync();
            updateCounts();
        }
    }

    private void updateCounts() {
        lastXaeroCount = getXaeroWaypointCount();
        lastRusherCount = getRusherWaypointCount();
    }

    private int getXaeroWaypointCount() {
        try {
            List<WaypointData> waypoints = readWaypointsFromXaero();
            return waypoints.size();
        } catch (Exception e) {
            return 0;
        }
    }

    private int getRusherWaypointCount() {
        try {
            String serverIP = getServerIP();
            if (serverIP == null) return 0;

            IWaypointManager waypointManager = RusherHackAPI.getWaypointManager();
            if (waypointManager == null) return 0;

            return waypointManager.getWaypointsForServer(serverIP).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private void performSync() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        try {
            String serverIP = getServerIP();
            if (serverIP == null) return;

            IWaypointManager waypointManager = RusherHackAPI.getWaypointManager();
            if (waypointManager == null) return;

            List<WaypointData> xaeroWaypoints = readWaypointsFromXaero();
            List<Waypoint> rusherWaypoints = new ArrayList<>(waypointManager.getWaypointsForServer(serverIP));

            Set<String> xaeroKeys = new HashSet<>();
            for (WaypointData wp : xaeroWaypoints) {
                if (shouldSyncDimension(wp.dimension) && (includeDeathpoints.getValue() || !wp.isDeathpoint)) {
                    xaeroKeys.add(createKey(wp.name, wp.x, wp.y, wp.z, wp.dimension));
                }
            }

            Set<String> rusherKeys = new HashSet<>();
            for (Waypoint wp : rusherWaypoints) {
                rusherKeys.add(createKey(wp.getName(), (int)wp.getPos().x, (int)wp.getPos().y, (int)wp.getPos().z, wp.getDimension().name()));
            }

            int rusherRemoved = 0;
            for (Waypoint wp : rusherWaypoints) {
                boolean isDeathpoint = wp.getName().equals("gui.xaero_deathpoint") ||
                        wp.getName().equals("gui.xaero_deathpoint_old");

                if (!shouldSyncDimension(wp.getDimension().name()) || (!includeDeathpoints.getValue() && isDeathpoint)) {
                    waypointManager.removeWaypoint(wp);
                    rusherRemoved++;
                }
            }

            List<WaypointData> xaeroToAdd = new ArrayList<>();
            for (WaypointData wp : xaeroWaypoints) {
                if (shouldSyncDimension(wp.dimension) && (includeDeathpoints.getValue() || !wp.isDeathpoint)) {
                    if (!rusherKeys.contains(createKey(wp.name, wp.x, wp.y, wp.z, wp.dimension))) {
                        xaeroToAdd.add(wp);
                    }
                }
            }

            List<Waypoint> rusherToAdd = new ArrayList<>();
            for (Waypoint wp : rusherWaypoints) {
                boolean isDeathpoint = wp.getName().equals("gui.xaero_deathpoint") ||
                        wp.getName().equals("gui.xaero_deathpoint_old");

                if (shouldSyncDimension(wp.getDimension().name()) && (includeDeathpoints.getValue() || !isDeathpoint)) {
                    if (!xaeroKeys.contains(createKey(wp.getName(), (int)wp.getPos().x, (int)wp.getPos().y, (int)wp.getPos().z, wp.getDimension().name()))) {
                        rusherToAdd.add(wp);
                    }
                }
            }

            int xaeroToRusher = syncXaeroToRusher(xaeroToAdd, serverIP, waypointManager);
            int rusherToXaero = syncRusherToXaero(rusherToAdd);

            int totalSynced = xaeroToRusher + rusherToXaero + rusherRemoved;

            if (showInfo.getValue() && totalSynced > 0) {
                sendMessage(Component.literal("Synchronized waypoints: " + totalSynced).withStyle(ChatFormatting.GRAY));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean shouldSyncDimension(String dimension) {
        DimensionFilter filter = dimensionFilter.getValue();

        if (filter == DimensionFilter.ALL) {
            return true;
        }

        String dimLower = dimension.toLowerCase();

        switch (filter) {
            case OVERWORLD:
                return dimLower.equals("overworld");
            case NETHER:
                return dimLower.equals("nether");
            case END:
                return dimLower.equals("end");
            default:
                return true;
        }
    }

    private int syncXaeroToRusher(List<WaypointData> waypoints, String serverIP, IWaypointManager waypointManager) {
        int added = 0;
        for (WaypointData wp : waypoints) {
            boolean exists = false;
            for (Waypoint existing : waypointManager.getWaypointsForServer(serverIP)) {
                if (existing.getName().equals(wp.name) &&
                        (int)existing.getPos().x == wp.x &&
                        (int)existing.getPos().y == wp.y &&
                        (int)existing.getPos().z == wp.z) {
                    exists = true;
                    break;
                }
            }

            if (exists) continue;

            Dimension dimension = parseDimension(wp.dimension);
            Vec3 pos = new Vec3(wp.x, wp.y, wp.z);
            waypointManager.addWaypoint(new Waypoint(wp.name, serverIP, pos, dimension));
            added++;
        }
        return added;
    }

    @SuppressWarnings("deprecation")
    private int syncRusherToXaero(List<Waypoint> rusherWaypoints) {
        int added = 0;
        MinimapWorld lastWorld = null;

        for (Waypoint wp : rusherWaypoints) {
            ResourceKey<Level> dimensionKey = getDimensionKey(wp.getDimension());
            MinimapWorld minimapWorld = WaypointAPI.getMinimapWorld(dimensionKey);
            if (minimapWorld == null) continue;

            WaypointSet waypointSet = WaypointAPI.getOrCreateWaypointSetInWorld(minimapWorld, "gui.xaero_default");
            if (waypointSet == null) continue;

            String name = wp.getName();
            int x = (int) wp.getPos().x;
            int y = (int) wp.getPos().y;
            int z = (int) wp.getPos().z;

            boolean exists = false;
            for (xaero.common.minimap.waypoints.Waypoint existing : ((AccessorWaypointSet) waypointSet).getList()) {
                if (existing.getName().equals(name) && existing.getX() == x && existing.getY() == y && existing.getZ() == z) {
                    exists = true;
                    break;
                }
            }

            if (exists) continue;

            String symbol = name.isEmpty() ? "W" : String.valueOf(name.charAt(0)).toUpperCase();
            xaero.common.minimap.waypoints.Waypoint xaeroWaypoint = new xaero.common.minimap.waypoints.Waypoint(x, y, z, name, symbol, 0);
            xaeroWaypoint.setDisabled(false);

            ((AccessorWaypointSet) waypointSet).getList().add(xaeroWaypoint);
            lastWorld = minimapWorld;
            added++;
        }

        if (added > 0 && lastWorld != null) {
            try {
                xaero.hud.minimap.BuiltInHudModules.MINIMAP.getCurrentSession().getWorldManagerIO().saveWorld(lastWorld);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return added;
    }

    private List<WaypointData> readWaypointsFromXaero() {
        List<WaypointData> waypoints = new ArrayList<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return waypoints;

            @SuppressWarnings("unchecked")
            ResourceKey<Level>[] dimensions = new ResourceKey[]{Level.OVERWORLD, Level.NETHER, Level.END};

            for (ResourceKey<Level> dimension : dimensions) {
                String dimensionStr = getDimensionStringFromKey(dimension);

                MinimapWorld world = WaypointAPI.getMinimapWorld(dimension);
                if (world == null) continue;

                WaypointSet defaultSet = world.getWaypointSet("gui.xaero_default");
                if (defaultSet == null) continue;

                for (xaero.common.minimap.waypoints.Waypoint wp : ((AccessorWaypointSet) defaultSet).getList()) {
                    if (wp.isDisabled()) continue;

                    boolean isDeathpoint = wp.getName().equals("gui.xaero_deathpoint") ||
                            wp.getName().equals("gui.xaero_deathpoint_old");

                    waypoints.add(new WaypointData(wp.getName(), wp.getX(), wp.getY(), wp.getZ(), dimensionStr, isDeathpoint));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return waypoints;
    }

    private String createKey(String name, int x, int y, int z, String dimension) {
        return name + "_" + x + "_" + y + "_" + z + "_" + dimension.toUpperCase();
    }

    private ResourceKey<Level> getDimensionKey(Dimension dimension) {
        switch (dimension) {
            case NETHER: return Level.NETHER;
            case END: return Level.END;
            default: return Level.OVERWORLD;
        }
    }

    private String getDimensionStringFromKey(ResourceKey<Level> key) {
        if (key == Level.NETHER) return "nether";
        if (key == Level.END) return "end";
        return "overworld";
    }

    private Dimension parseDimension(String dimensionStr) {
        switch (dimensionStr.toLowerCase()) {
            case "nether": return Dimension.NETHER;
            case "end": return Dimension.END;
            default: return Dimension.OVERWORLD;
        }
    }

    private String getServerIP() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip;
        if (mc.getSingleplayerServer() != null) return "localhost";
        return null;
    }

    private void sendMessage(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    private static class WaypointData {
        String name;
        int x, y, z;
        String dimension;
        boolean isDeathpoint;

        WaypointData(String name, int x, int y, int z, String dimension, boolean isDeathpoint) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.isDeathpoint = isDeathpoint;
        }
    }
}