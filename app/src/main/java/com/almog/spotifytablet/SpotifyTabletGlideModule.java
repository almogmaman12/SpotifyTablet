package com.almog.spotifytablet;

import android.content.Context;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Lets Glide use its generated module (skips scanning the manifest for modules at startup).
 */
@GlideModule
public final class SpotifyTabletGlideModule extends AppGlideModule {
    @Override
    public void applyOptions(Context context, GlideBuilder builder) {
        // The app shows two or three pictures at a time (cover, background, weather icon), so Glide's default of
        // keeping about two screens of old pictures in memory is far more than needed. One screen is plenty.
        builder.setMemorySizeCalculator(new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(1f)
                .setBitmapPoolScreens(1f)
                .build());
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
