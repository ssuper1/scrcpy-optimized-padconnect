package com.genymobile.scrcpy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppIconsOptionsTest {
    @Test
    public void originalListOutputDoesNotIncludeIconsByDefault() {
        Options options = Options.parse("4.1", "list_apps=true");
        assertTrue(options.getList());
        assertTrue(options.getListApps());
        assertFalse(options.getListAppIcons());
    }

    @Test
    public void phoneClientCanRequestIconsAlongsideItsAppList() {
        Options options = Options.parse("4.1", "list_apps=true", "list_app_icons=true");
        assertTrue(options.getList());
        assertTrue(options.getListAppIcons());
        assertFalse(Options.parse("4.1", "list_apps=true", "list_app_icons=false").getListAppIcons());
    }
}
