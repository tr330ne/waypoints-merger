package treeone.waypointsmerger;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.rusherhack.client.api.events.client.EventUpdate;
import org.rusherhack.client.api.feature.module.ModuleCategory;
import org.rusherhack.client.api.feature.module.ToggleableModule;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

public class WaypointsMerger extends ToggleableModule {

    private final BooleanSetting includeDeathpoints = new BooleanSetting("Deathpoints", false);

    private boolean isProcessing = false;
    private Set<String> processedNames = new HashSet<>();

    public WaypointsMerger() {
        super("WaypointsMerger", "Synchronizes waypoints from Xaero's into Rusher", ModuleCategory.MISC);

        includeDeathpoints.setDescription("Include deathpoints while merging");

        this.registerSettings(includeDeathpoints);
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
            processedNames.clear();

            List<WaypointData> waypoints = readWaypointsFromFile();

            if (waypoints.isEmpty()) {
                sendMessage("§eNo waypoints found in Xaero's to export");
                return;
            }

            String serverIP = getServerIP();

            executeRusherCommand("waypoints clear");

            int added = 0;
            int skipped = 0;

            for (WaypointData wp : waypoints) {
                String name = sanitizeWaypointName(wp.name);

                String nameLower = name.toLowerCase();
                if (processedNames.contains(nameLower)) {
                    skipped++;
                    continue;
                }
                processedNames.add(nameLower);

                String command = String.format("waypoints add %s %d %d %d %s %s",
                        name,
                        wp.x,
                        wp.y,
                        wp.z,
                        wp.dimension,
                        serverIP
                );

                executeRusherCommand(command);
                added++;
            }

            String message = String.format("§7Synchronized %d waypoints", added);
            if (skipped > 0) {
                message += String.format(" (skipped: %d)", skipped);
            }
            sendMessage(message);

        } catch (Exception e) {
            sendMessage("§cExport error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private List<WaypointData> readWaypointsFromFile() {
        List<WaypointData> waypoints = new ArrayList<>();

        try {
            Minecraft mc = Minecraft.getInstance();

            File minecraftDir = mc.gameDirectory;
            File xaeroDir = new File(minecraftDir, "xaero/minimap");

            if (!xaeroDir.exists()) {
                sendMessage("§cXaero folder not found");
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
                    sendMessage("§7World: " + currentWorldName);
                } else {
                    sendMessage("§cWorld folder not found: " + currentWorldName);
                }
            }

            if (waypointFiles.isEmpty()) {
                sendMessage("§cWaypoint files not found");
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
                            sendMessage(String.format("§aFound: %s (%d, %d, %d) [%s]",
                                    wp.name, wp.x, wp.y, wp.z, wp.dimension));
                            waypoints.add(wp);
                        } else if (!wp.disabled && wp.isDeathpoint && includeDeathpoints.getValue()) {
                            sendMessage(String.format("§aFound deathpoint: %s (%d, %d, %d) [%s]",
                                    wp.name, wp.x, wp.y, wp.z, wp.dimension));
                            waypoints.add(wp);
                        }
                    }
                }
            }

        } catch (Exception e) {
            sendMessage("§cError reading files: " + e.getMessage());
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

            boolean isDeathpoint = (parts.length > 9 && parts[9].contains("deathpoint"))
                    || name.toLowerCase().contains("death");

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
        }

        return "localhost";
    }

    private void executeRusherCommand(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            String prefix = getCommandPrefix();
            mc.player.connection.sendChat(prefix + command);
        }
    }

    private String getCommandPrefix() {
        try {
            Minecraft mc = Minecraft.getInstance();
            File prefixFile = new File(mc.gameDirectory, "rusherhack/config/command_prefix.txt");

            if (prefixFile.exists()) {
                List<String> lines = Files.readAllLines(prefixFile.toPath());
                if (!lines.isEmpty()) {
                    String prefix = lines.get(0).trim();
                    if (!prefix.isEmpty()) {
                        return prefix;
                    }
                }
            }
        } catch (Exception e) {
        }

        return "*";
    }

    private void sendMessage(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.literal(message), false);
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