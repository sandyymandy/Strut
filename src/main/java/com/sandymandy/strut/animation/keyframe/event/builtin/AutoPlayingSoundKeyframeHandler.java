package com.sandymandy.strut.animation.keyframe.event.builtin;

import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundCategory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.Vec3d;
import com.sandymandy.strut.animatable.StrutAnimatable;
import com.sandymandy.strut.animation.AnimationController;
import com.sandymandy.strut.animation.keyframe.event.SoundKeyframeEvent;
import com.sandymandy.strut.util.ClientUtil;

/**
 * Built-in helper for a {@link com.sandymandy.strut.animation.AnimationController.SoundKeyframeHandler SoundKeyframeHandler} that automatically plays the sound defined in the keyframe data
 * <p>
 * Due to an inability to determine the position of the sound for all animatables, this handler only supports {@link com.sandymandy.strut.animatable.StrutEntity StrutEntity} and {@link com.sandymandy.strut.animatable.StrutBlockEntity StrutBlockEntity}
 * <p>
 * The expected keyframe data format is one of the below:
 * <pre>{@code
 * namespace:soundid
 * namespace:soundid|volume|pitch
 * }</pre>
 */
public class AutoPlayingSoundKeyframeHandler<A extends StrutAnimatable> implements AnimationController.SoundKeyframeHandler<A> {
    @Override
    public void handle(SoundKeyframeEvent<A> event) {
        String[] segments = event.getKeyframeData().getSound().split("\\|");
        SoundEvent sound = Registries.SOUND_EVENT.get(Identifier.validate(segments[0]).getOrThrow());

        if (sound != null) {
            Entity entity = event.getAnimatable() instanceof Entity e ? e : null;
            Vec3d position = entity != null ? entity.getPos() : event.getAnimatable() instanceof BlockEntity blockEntity ? blockEntity.getPos().toCenterPos() : null;

            if (position != null) {
                float volume = segments.length > 1 ? Float.parseFloat(segments[1]) : 1;
                float pitch = segments.length > 2 ? Float.parseFloat(segments[2]) : 1;
                SoundCategory source = entity == null ? SoundCategory.BLOCKS : entity instanceof Monster ? SoundCategory.HOSTILE : SoundCategory.NEUTRAL;

                ClientUtil.getLevel().playSound(position.x, position.y, position.z, sound, source, volume, pitch, false);
            }
        }
    }
}
