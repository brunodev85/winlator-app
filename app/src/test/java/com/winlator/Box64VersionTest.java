package com.winlator;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Box64VersionTest {
    private static final List<String> NONE = Collections.emptyList();

    @Test public void builtinVersionIsKept() {
        assertTrue(SettingsFragment.isKnownBox64Version("0.4.4", Arrays.asList("0.4.4"), NONE));
    }

    @Test public void installedVersionIsKept() {
        assertTrue(SettingsFragment.isKnownBox64Version("0.4.5", Arrays.asList("0.4.4"), Arrays.asList("0.4.5")));
    }

    @Test public void missingVersionIsNotKnown() {
        assertFalse(SettingsFragment.isKnownBox64Version("0.3.6", Arrays.asList("0.4.4"), Arrays.asList("0.4.5")));
    }

    @Test public void builtinMatchIgnoresCase() {
        assertTrue(SettingsFragment.isKnownBox64Version("0.4.4-custom", Arrays.asList("0.4.4-Custom"), NONE));
    }

    @Test public void installedDisplayNameMatchesStoredIdentifier() {
        assertTrue(SettingsFragment.isKnownBox64Version("0.4.5-dev", NONE, Arrays.asList("0.4.5 Dev")));
    }

    @Test public void nullVersionIsNotKnown() {
        assertFalse(SettingsFragment.isKnownBox64Version(null, Arrays.asList("0.4.4"), Arrays.asList("0.4.5")));
    }
}
