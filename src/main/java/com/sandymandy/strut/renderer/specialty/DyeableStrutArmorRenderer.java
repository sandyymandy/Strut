package com.sandymandy.strut.renderer.specialty;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumer;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.item.Item;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.sandymandy.strut.animatable.StrutItem;
import com.sandymandy.strut.cache.object.BakedStrutModel;
import com.sandymandy.strut.cache.object.GroupBoneDefinition;
import com.sandymandy.strut.animation.state.BoneVisuals;
import com.sandymandy.strut.cache.object.GroupBoneHandle;
import com.sandymandy.strut.renderer.StrutArmorRenderer;
import com.sandymandy.strut.util.Color;
import com.sandymandy.strut.model.StrutModel;

import java.util.Collection;
import java.util.Set;

/**
 * A shallow-level dyeable armour renderer for Strut armor models
 * <p>
 * This approach avoids needing to change the JSON object format to natively support dyes, which is a whole can of worms
 */
public abstract class DyeableStrutArmorRenderer<T extends Item & StrutItem> extends StrutArmorRenderer<T> {
    /**
     * Classified by {@link GroupBoneDefinition}, not {@link GroupBoneHandle} - dyeability is a property of
     * the bone's identity/geometry (typically its name), not of any one instance's pose, so it is
     * safe to cache once per {@link BakedStrutModel} and reuse across every wearer, exactly like the
     * definitions themselves
     */
    protected final Set<GroupBoneDefinition> dyeableBones = new ObjectArraySet<>();
    protected BakedStrutModel lastModel = null;

    public DyeableStrutArmorRenderer(StrutModel<T> model) {
        super(model);
    }

    @Override
    public void preRender(MatrixStack poseStack, T animatable, BakedStrutModel model, @Nullable VertexConsumerProvider bufferSource, @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);

        if (!isReRender)
            checkBoneDyeCache(animatable, model, partialTick, packedLight, packedOverlay, colour);
    }

    /**
     * Each dyeable bone is tinted by its dye on top of what the bone overrides gave it, cubes and meshes alike. A new set of per-bone colours for each draw: the frame's
     * batch reads it when it is flushed, so one wearer's dye must not be another's
     */
    @Override
    public BoneVisuals customBoneVisuals(T animatable, BakedStrutModel model, @Nullable BoneVisuals resolved) {
        if (this.dyeableBones.isEmpty())
            return resolved;

        BoneVisuals visuals = new BoneVisuals(model.flatBones().length);

        if (resolved != null && resolved.boneCount() <= visuals.boneCount()) {
            for (int i = 0; i < resolved.boneCount(); i++) {
                visuals.setColor(i, resolved.getColor(i));
                visuals.setUvOffset(i, resolved.getUOffset(i), resolved.getVOffset(i));
                visuals.setUvScale(i, resolved.getUScale(i), resolved.getVScale(i));
            }
        }

        for (GroupBoneDefinition definition : this.dyeableBones) {
            GroupBoneHandle handle = getModelInstanceState(animatable).handleFor(model, definition);

            if (handle != null)
                visuals.setColor(definition.getIndex(), ColorHelper.Argb.mixColor(visuals.getColor(definition.getIndex()), getColorForBone(handle).argbInt()));
        }

        return visuals;
    }

    /**
     * Whether the given bone should be considered dyeable or not
     * <p>
     * Note that values returned from here are cached for the last rendered {@link BakedStrutModel} and require a manual reset if you intend to change these results
     *
     * @return whether the bone should be dyed or not
     */
    protected abstract boolean isBoneDyeable(GroupBoneDefinition bone);

    /**
     * What color the given bone should be dyed as
     * <p>
     * Only bones that were marked as 'dyeable' in {@link DyeableStrutArmorRenderer#isBoneDyeable(GroupBoneDefinition)} are provided here
     */
    @NotNull
    protected abstract Color getColorForBone(GroupBoneHandle bone);

    /**
     * Check whether the dye cache should be considered dirty and recomputed
     * <p>
     * The less this forces re-computation, the better for performance
     */
    protected void checkBoneDyeCache(T animatable, BakedStrutModel model, float partialTick, int packedLight, int packedOverlay, int colour) {
        if (model != this.lastModel) {
            this.dyeableBones.clear();
            this.lastModel = model;
            collectDyeableBones(model.topLevelBones());
        }
    }

    /**
     * Recursively parse through the given bones collection, collecting and caching dyeable bones as applicable
     */
    protected void collectDyeableBones(Collection<GroupBoneDefinition> bones) {
        for (GroupBoneDefinition bone : bones) {
            if (isBoneDyeable(bone))
                this.dyeableBones.add(bone);

            collectDyeableBones(bone.getChildBones());
        }
    }
}
