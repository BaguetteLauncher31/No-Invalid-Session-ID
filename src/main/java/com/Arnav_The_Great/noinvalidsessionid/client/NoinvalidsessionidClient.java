package com.Arnav_The_Great.noinvalidsessionid.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoinvalidsessionidClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("no_invalid_session_id");

    @Override
    public void onInitializeClient() {
        LOGGER.info("No Invalid Session ID initialized!");
    }
}