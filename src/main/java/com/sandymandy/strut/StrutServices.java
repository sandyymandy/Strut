package com.sandymandy.strut;

import com.sandymandy.strut.service.StrutEvents;
import com.sandymandy.strut.service.StrutNetworking;
import com.sandymandy.strut.service.StrutPlatform;
import com.sandymandy.strut.service.StrutClient;

import java.util.ServiceLoader;

public final class StrutServices {
    public static final StrutPlatform PLATFORM = load(StrutPlatform.class);
    public static final StrutNetworking NETWORK = load(StrutNetworking.class);

    public static class Client {
        public static final StrutEvents EVENTS = load(StrutEvents.class);
        public static final StrutClient ITEM_RENDERING = load(StrutClient.class);
    }

    private static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        StrutConstants.LOGGER.debug("Loaded {} for service {}", loadedService, clazz);

        return loadedService;
    }
}
