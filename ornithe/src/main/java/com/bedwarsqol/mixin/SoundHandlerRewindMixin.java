package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.export.ExportSoundTap;
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
 * catch-up vanilla runs. While a Rewind export renders, both ask {@link ExportSoundTap} first: it logs the sound for
 * the export's mix and the hook cancels it (Rewind Export PLAN Solution 3). The tap is off unless an export runs.
 */
@Mixin(SoundHandler.class)
public abstract class SoundHandlerRewindMixin {

    @Inject(method = "playSound(Lnet/minecraft/client/audio/ISound;)V", at = @At("HEAD"), cancellable = true,
            require = 1)
    private void bedwarsqol$rewindMuteSound(ISound sound, CallbackInfo ci) {
        if (ExportSoundTap.hook(sound, 0) || RewindPlayback.soundsMuted()) ci.cancel();
    }

    @Inject(method = "playDelayedSound(Lnet/minecraft/client/audio/ISound;I)V", at = @At("HEAD"),
            cancellable = true, require = 1)
    private void bedwarsqol$rewindMuteDelayedSound(ISound sound, int delay, CallbackInfo ci) {
        if (ExportSoundTap.hook(sound, delay) || RewindPlayback.soundsMuted()) ci.cancel();
    }
}
