package com.masteroebot.markov;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

class MarkovConfigTest {

    @Test
    void freshChannelsDefaultOffPerBot() {
        MarkovConfig config = new MarkovConfig();
        assertFalse(config.isEnabled(123L));
        assertFalse(config.isParaokaEnabled(123L));
        assertTrue(config.allowShortMessages(123L));
    }

    @Test
    void legacyEnabledChannelLeavesParaokaOff() {
        MarkovConfig config = new MarkovConfig();
        Properties props = new Properties();
        props.setProperty("123.enabled", "true");
        config.applyProperties(props);
        assertTrue(config.isEnabled(123L));
        assertFalse(config.isParaokaEnabled(123L));
    }

    @Test
    void explicitParaokaOnRespected() {
        MarkovConfig config = new MarkovConfig();
        Properties props = new Properties();
        props.setProperty("123.enabled", "true");
        props.setProperty("123.paraokaEnabled", "true");
        config.applyProperties(props);
        assertTrue(config.isEnabled(123L));
        assertTrue(config.isParaokaEnabled(123L));
    }

    @Test
    void explicitParaokaOffSurvivesMigration() {
        MarkovConfig config = new MarkovConfig();
        Properties props = new Properties();
        props.setProperty("123.enabled", "true");
        props.setProperty("123.paraokaEnabled", "false");
        config.applyProperties(props);
        assertTrue(config.isEnabled(123L));
        assertFalse(config.isParaokaEnabled(123L));
    }

    @Test
    void legacyBareKeyEnablesMasterOnly() {
        MarkovConfig config = new MarkovConfig();
        Properties props = new Properties();
        props.setProperty("123", "true");
        config.applyProperties(props);
        assertTrue(config.isEnabled(123L));
        assertFalse(config.isParaokaEnabled(123L));
    }

    @Test
    void disabledChannelLeavesParaokaOff() {
        MarkovConfig config = new MarkovConfig();
        Properties props = new Properties();
        props.setProperty("123.enabled", "false");
        config.applyProperties(props);
        assertFalse(config.isEnabled(123L));
        assertFalse(config.isParaokaEnabled(123L));
    }
}
