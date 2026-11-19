package com.bedwarsqol.mixin;

import com.bedwarsqol.rewind.ActionCapture;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewind's SOUND record (format v2, PLAN section A, A6): every audible client sound ends in
 * {@code SoundHandler.playSound(ISound)}; one played inside a local scope is the player's own. Reads the sound at HEAD;
 * not cancellable, changes nothing.
 */
@Mixin(SoundHandler.class)
public abstract class SoundHandlerRewindRecordMixin {

    @Inject(method = "playSound(Lnet/minecraft/client/audio/ISound;)V", at = @At("HEAD"), require = 1)
    private void bedwarsqol$rewindRecordSound(ISound sound, CallbackInfo ci) {
        ActionCapture.onSound(sound);
    }
}
