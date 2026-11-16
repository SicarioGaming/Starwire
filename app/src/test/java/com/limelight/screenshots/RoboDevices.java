package com.limelight.screenshots;

/**
 * The screens Starwire's users stream on, as Robolectric resource qualifiers. Sizes are in dp
 * (pixels / density), which is what the layouts and values-w480dp switch on.
 */
enum RoboDevices {
    // 720 x 1280 px at 320 dpi: an old or budget phone (minSdk 21 users)
    SMALL_PHONE("w360dp-h640dp-normal-notlong-port-xhdpi", 1f),
    // Galaxy Z Fold5 cover screen, 904 x 2316 px at 420 dpi. The 0.2.0 blank-settings bug was here
    FOLD_COVER("w344dp-h882dp-normal-long-port-420dpi", 1f),
    // The same cover screen with Android's largest common font size
    FOLD_COVER_FONT_130("w344dp-h882dp-normal-long-port-420dpi", 1.3f),
    // Galaxy Z Fold5 inner screen, 1812 x 2176 px at 420 dpi
    FOLD_INNER("w690dp-h829dp-large-notlong-port-420dpi", 1f),
    // A 16:10 tablet held sideways, 2560 x 1600 px at 320 dpi
    TABLET_LANDSCAPE("w1280dp-h800dp-xlarge-notlong-land-xhdpi", 1f),
    // An 8.8-inch tablet held sideways, 2400 x 1504 px at 360 dpi (RedMagic Astra). The stream
    // menu shows seven across here, as on TABLET_LANDSCAPE
    SMALL_TABLET_LANDSCAPE("w1067dp-h668dp-large-notlong-land-360dpi", 1f),
    // A 16:9 Android handheld, 1920 x 1080 px at 320 dpi (AYN Odin 2 / Retroid Pocket 5 class)
    HANDHELD_16_9("w960dp-h540dp-normal-long-land-xhdpi", 1f),
    // A small 16:9 handheld, 1334 x 750 px at 320 dpi (Retroid Pocket 4 Pro class)
    HANDHELD_SMALL("w667dp-h375dp-normal-long-land-xhdpi", 1f),
    // Hebrew, the most complete right-to-left translation (values-iw)
    SMALL_PHONE_HEBREW("iw-w360dp-h640dp-normal-notlong-port-xhdpi", 1f);

    final String qualifiers;
    final float fontScale;

    RoboDevices(String qualifiers, float fontScale) {
        this.qualifiers = qualifiers;
        this.fontScale = fontScale;
    }
}
