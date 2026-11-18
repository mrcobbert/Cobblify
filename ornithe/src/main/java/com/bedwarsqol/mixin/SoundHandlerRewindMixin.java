package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.watch.RewindPlayback;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind catch-ups and rebuilds are silent (PLAN section 2): sounds reach the sound system through
 * {@code playSound}, or {@code playDelayedSound} for distance-delayed ones, both cancelled while muted. Outside a
 * catch-up vanilla runs.
 */
@Mixin(SoundHandler.class)
public abstract class SoundHandlerRewindMixin {

    @Inject(method = "playSound(Lnet/minecraft/client/audio/ISound;)V", at = @At("HEAD"), cancellable = true,
            require = 1)
    private void bedwarsqol$rewindMuteSound(ISound sound, CallbackInfo ci) {
        if (RewindPlayback.soundsMuted()) ci.cancel();
    }

    @Inject(method = "playDelayedSound(Lnet/minecraft/client/audio/ISound;I)V", at = @At("HEAD"),
            cancellable = true, require = 1)
    private void bedwarsqol$rewindMuteDelayedSound(ISound sound, int delay, CallbackInfo ci) {
        if (RewindPlayback.soundsMuted()) ci.cancel();
    }
}
