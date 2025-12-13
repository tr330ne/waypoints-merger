package treeone.waypointsync;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

public class WaypointSyncPlugin extends Plugin {

    @Override
    public void onLoad() {
        this.getLogger().info("WaypointSync plugin loaded");

        final WaypointSync waypointSync = new WaypointSync();
        RusherHackAPI.getModuleManager().registerFeature(waypointSync);
    }

    @Override
    public void onUnload() {
        this.getLogger().info("WaypointSync plugin unloaded");
    }
}