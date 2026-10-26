package com.sandymandy.strut;

import com.sandymandy.strut.animation.override.ServerBlockOverrides;
import com.sandymandy.strut.armour.StrutArmour;
import com.sandymandy.strut.player.ServerEyes;
import com.sandymandy.strut.player.StrutPlayers;
import com.sandymandy.strut.scene.StrutScenes;
import net.fabricmc.api.ModInitializer;
import com.sandymandy.strut.animation.authority.ServerAnimationStates;
import com.sandymandy.strut.event.type.StrutEventTypes;
import com.sandymandy.strut.event.timeline.ClipTimelinesFabric;
import com.sandymandy.strut.assetsync.AssetSyncCommands;
import com.sandymandy.strut.assetsync.AssetSyncDemo;
import com.sandymandy.strut.animatable.StrutSyncedItems;
import com.sandymandy.strut.assetsync.server.ServerAssetSyncFabric;
import com.sandymandy.strut.event.StrutEventCommands;
import com.sandymandy.strut.service.StrutNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Main Strut entrypoint
 */
public final class Strut implements ModInitializer {
    @Override
    public void onInitialize() {
        StrutConstants.init();
        StrutNetworking.init();
        ServerAnimationStates.init();
        StrutPlayers.init();
        ServerEyes.init();
        StrutArmour.init();
        StrutScenes.init();
        ServerBlockOverrides.init();
        ClipTimelinesFabric.init();
        StrutEventCommands.init();
        ServerAssetSyncFabric.init();
        StrutSyncedItems.init();
        AssetSyncCommands.init();
        AssetSyncDemo.init();
        // Every mod's event types register during initialisation; freeze before any world (and so any
        // clip) loads, so a clip always resolves against the complete set
        ServerLifecycleEvents.SERVER_STARTING.register(server -> StrutEventTypes.freeze());
    }
}
