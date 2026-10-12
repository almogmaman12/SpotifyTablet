package com.almog.spotifytablet;

import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

/**
 * Lets Glide use its generated module (skips scanning the manifest for modules at startup).
 */
@GlideModule
public final class SpotifyTabletGlideModule extends AppGlideModule {
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
