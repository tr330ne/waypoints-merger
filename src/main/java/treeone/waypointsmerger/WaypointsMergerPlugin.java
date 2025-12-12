package treeone.waypointsmerger;

import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.plugin.Plugin;

public class WaypointsMergerPlugin extends Plugin {

    @Override
    public void onLoad() {
        this.getLogger().info("WaypointsMerger plugin loaded");

        final WaypointsMerger waypointsMerger = new WaypointsMerger();
        RusherHackAPI.getModuleManager().registerFeature(waypointsMerger);
    }

    @Override
    public void onUnload() {
        this.getLogger().info("WaypointsMerger plugin unloaded");
    }
}