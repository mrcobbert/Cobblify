package com.bedwarsqol.mixin;

import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Toggle Sprint holds one binding down. {@code KeyBinding.setKeyBindState} works by key code, and every
 * unbound binding shares code 0, so it could press the wrong one; this sets the binding's own field.
 */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {

    @Accessor("pressed")
    void bedwarsqol$setPressed(boolean pressed);
}
