package treeone.waypointsmerger;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class WaypointsMerger extends ToggleableModule {

    private enum DimensionFilter {
        ALL, OVERWORLD, NETHER, END
    }

    private final EnumSetting<DimensionFilter> dimensionFilter = new EnumSetting<>("Dimension", DimensionFilter.ALL);
    private final BooleanSetting includeDeathpoints = new BooleanSetting("Deathpoints", false);
    private final BooleanSetting showInfo = new BooleanSetting("ShowInfo", true);
    private final BooleanSetting showDebug = new BooleanSetting("ShowDebug", false);

    private boolean isProcessing = false;
    private Set<String> processedNames = new HashSet<>();

    public WaypointsMerger() {
        super("WaypointsMerger", "Synchronizes waypoints from Xaero's to Rusher", ModuleCategory.MISC);

        dimensionFilter.setDescription("Which dimensions to sync");
        includeDeathpoints.setDescription("Include deathpoints in sync");
        showInfo.setDescription("Show result message");
        showDebug.setDescription("Show debug messages");

        this.registerSettings(dimensionFilter, includeDeathpoints, showInfo, showDebug);
    }

    @Override
    public void onEnable() {
        if (!isProcessing) {
            isProcessing = true;
            mergeWaypoints();
        }
    }

    @Subscribe
    public void onUpdate(EventUpdate event) {
        if (isProcessing) {
            isProcessing = false;
            this.toggle();
        }
    }

    private void mergeWaypoints() {
        try {
            Minecraft mc = Minecraft.getInstance();

            if (mc.level == null) {
                sendMessage(Component.literal("Not in a world").withStyle(ChatFormatting.RED));
                return;
            }

            processedNames.clear();

            List<WaypointData> waypoints = readWaypointsFromFile();

            if (waypoints.isEmpty()) {
                sendMessage(Component.literal("No waypoints found in Xaero's").withStyle(ChatFormatting.YELLOW));
                return;
            }

            String serverIP = getServerIP();

            if (serverIP == null) {
                sendMessage(Component.literal("Cannot determine server/world").withStyle(ChatFormatting.RED));
                return;
            }

            IWaypointManager waypointManager = RusherHackAPI.getWaypointManager();

            if (waypointManager == null) {
                sendMessage(Component.literal("Cannot access waypoint manager").withStyle(ChatFormatting.RED));
                return;
            }

            List<Waypoint> existingWaypoints = waypointManager.getWaypointsForServer(serverIP);
            for (Waypoint wp : existingWaypoints) {
                waypointManager.removeWaypoint(wp);
            }

            int added = 0;
            int skipped = 0;

            for (WaypointData wp : waypoints) {
                String name = sanitizeWaypointName(wp.name);

                String nameLower = name.toLowerCase();
                if (processedNames.contains(nameLower)) {
                    skipped++;
                    continue;
                }

                if (!shouldSyncDimension(wp.dimension)) {
                    skipped++;
                    continue;
                }

                processedNames.add(nameLower);

                Dimension dimension = parseDimension(wp.dimension);
                Vec3 pos = new Vec3(wp.x, wp.y, wp.z);

                Waypoint waypoint = new Waypoint(name, serverIP, pos, dimension);

                waypointManager.addWaypoint(waypoint);
                added++;
            }

            if (showInfo.getValue() || showDebug.getValue()) {
                Component message = Component.literal("Synchronized " + added + " waypoints")
                        .withStyle(ChatFormatting.GRAY);
                if (skipped > 0 && showDebug.getValue()) {
                    message = Component.literal("Synchronized " + added + " waypoints (skipped: " + skipped + ")")
                            .withStyle(ChatFormatting.GRAY);
                }
                sendMessage(message);
            }

        } catch (Exception e) {
            sendMessage(Component.literal("Export error: " + e.getMessage()).withStyle(ChatFormatting.RED));
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

    private Dimension parseDimension(String dimensionStr) {
        switch (dimensionStr.toLowerCase()) {
            case "nether":
                return Dimension.NETHER;
            case "end":
                return Dimension.END;
            case "overworld":
            default:
                return Dimension.OVERWORLD;
        }
    }

    private List<WaypointData> readWaypointsFromFile() {
        List<WaypointData> waypoints = new ArrayList<>();

        try {
            Minecraft mc = Minecraft.getInstance();

            File minecraftDir = mc.gameDirectory;
            File xaeroDir = new File(minecraftDir, "xaero/minimap");

            if (!xaeroDir.exists()) {
                sendMessage(Component.literal("Xaero folder not found").withStyle(ChatFormatting.RED));
                return waypoints;
            }

            String currentWorldName = null;
            if (mc.level != null && mc.getCurrentServer() != null) {
                String serverIP = mc.getCurrentServer().ip;
                String ipWithoutPort = serverIP.split(":")[0];
                currentWorldName = "Multiplayer_" + ipWithoutPort.replace(":", "_");
            } else if (mc.level != null && mc.getSingleplayerServer() != null) {
                currentWorldName = mc.getSingleplayerServer().getWorldData().getLevelName();
            }

            List<File> waypointFiles = new ArrayList<>();

            if (currentWorldName != null) {
                File currentWorldDir = new File(xaeroDir, currentWorldName);

                if (currentWorldDir.exists()) {
                    waypointFiles = findWaypointFilesWithDimension(currentWorldDir);
                    if (showDebug.getValue()) {
                        sendMessage(Component.literal("World: " + currentWorldName).withStyle(ChatFormatting.GRAY));
                    }
                } else {
                    sendMessage(Component.literal("World folder not found: " + currentWorldName)
                            .withStyle(ChatFormatting.RED));
                }
            }

            if (waypointFiles.isEmpty()) {
                sendMessage(Component.literal("Waypoint files not found").withStyle(ChatFormatting.RED));
                return waypoints;
            }

            for (File file : waypointFiles) {
                String dimension = getDimensionFromPath(file, xaeroDir);

                List<String> lines = Files.readAllLines(file.toPath());

                for (String line : lines) {
                    if (line.startsWith("#") || line.trim().isEmpty()) {
                        continue;
                    }

                    WaypointData wp = parseWaypointLine(line, dimension);
                    if (wp != null) {
                        if (!wp.disabled && !wp.isDeathpoint) {
                            if (showDebug.getValue()) {
                                sendMessage(Component.literal(String.format("Found: %s (%d, %d, %d) [%s]",
                                        wp.name, wp.x, wp.y, wp.z, wp.dimension)).withStyle(ChatFormatting.GREEN));
                            }
                            waypoints.add(wp);
                        } else if (!wp.disabled && wp.isDeathpoint && includeDeathpoints.getValue()) {
                            if (showDebug.getValue()) {
                                sendMessage(Component.literal(String.format("Found deathpoint: %s (%d, %d, %d) [%s]",
                                        wp.name, wp.x, wp.y, wp.z, wp.dimension)).withStyle(ChatFormatting.GREEN));
                            }
                            waypoints.add(wp);
                        }
                    }
                }
            }

        } catch (Exception e) {
            sendMessage(Component.literal("Error reading files: " + e.getMessage()).withStyle(ChatFormatting.RED));
            e.printStackTrace();
        }

        return waypoints;
    }

    private String getDimensionFromPath(File file, File xaeroDir) {
        String path = file.getParentFile().getAbsolutePath().toLowerCase();

        if (path.contains("dim%-1") || path.contains("dim-1")) {
            return "nether";
        } else if (path.contains("dim%1") || path.contains("dim1")) {
            return "end";
        } else if (path.contains("dim%0") || path.contains("dim0")) {
            return "overworld";
        }

        return "overworld";
    }

    private List<File> findWaypointFilesWithDimension(File dir) {
        List<File> files = new ArrayList<>();

        File[] contents = dir.listFiles();
        if (contents == null) return files;

        for (File file : contents) {
            if (file.isDirectory()) {
                files.addAll(findWaypointFilesWithDimension(file));
            } else if (file.getName().equals("waypoints.txt") ||
                    (file.getName().startsWith("mw$") && file.getName().endsWith(".txt"))) {
                files.add(file);
            }
        }

        return files;
    }

    private WaypointData parseWaypointLine(String line, String dimension) {
        try {
            String[] parts = line.split(":");

            if (parts.length < 7 || !parts[0].equals("waypoint")) {
                return null;
            }

            String name = parts[1];
            int x = parseCoordinate(parts[3]);
            int y = parseCoordinate(parts[4]);
            int z = parseCoordinate(parts[5]);
            boolean disabled = parts.length > 7 && parts[7].equals("true");

            boolean isDeathpoint = name.equals("gui.xaero_deathpoint")
                    || (parts.length > 8 && parts[8].equals("1"));

            return new WaypointData(name, x, y, z, disabled, dimension, isDeathpoint);

        } catch (Exception e) {
            return null;
        }
    }

    private int parseCoordinate(String coord) {
        if (coord.equals("~") || coord.trim().isEmpty()) {
            return 64;
        }
        return Integer.parseInt(coord);
    }

    private String sanitizeWaypointName(String name) {
        String sanitized = name.trim();

        if (sanitized.isEmpty()) {
            return "waypoint";
        }

        sanitized = sanitized
                .replace(" ", "_")
                .replace("\"", "")
                .replace("'", "");

        return sanitized;
    }

    private String getServerIP() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.getCurrentServer() != null) {
            return mc.getCurrentServer().ip;
        } else if (mc.getSingleplayerServer() != null) {
            return "localhost";
        }

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
        boolean disabled;
        String dimension;
        boolean isDeathpoint;

        WaypointData(String name, int x, int y, int z, boolean disabled, String dimension, boolean isDeathpoint) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.disabled = disabled;
            this.dimension = dimension;
            this.isDeathpoint = isDeathpoint;
        }
    }
}