package com.sandymandy.strut.animatable;

import com.sandymandy.strut.animation.override.BoneOverride;
import com.sandymandy.strut.animation.override.ClientBoneOverrideState;
import com.sandymandy.strut.animation.override.ClientBoneOverrideStates;
import com.sandymandy.strut.animation.override.OverrideProperty;
import com.sandymandy.strut.animation.override.OverrideScope;
import com.sandymandy.strut.animation.override.OverrideValue;
import com.sandymandy.strut.animation.override.ServerBlockOverrides;
import com.sandymandy.strut.animation.override.ServerBoneOverrideState;
import net.minecraft.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * The bone-override API of a block entity: {@code overrides()} on {@link StrutBlockEntity}. The setters are on {@link BoneOverrides}, and work as an entity's do
 * <p>
 * Called on the server, an override is authoritative and every player that can see the block gets it (a player who comes into range later is
 * sent it as the block loads). Called on a client it is that client's own, never sent anywhere. Neither is saved: a block entity that loads
 * again starts without overrides, so what the block looks like is set from its own state when it loads and when that changes
 * <p>
 * An override is kept for the block's position, so it is gone when the block entity is removed or unloaded
 */
public final class BlockEntityBoneOverrides extends BoneOverrides {
	private final BlockEntity blockEntity;

	BlockEntityBoneOverrides(BlockEntity blockEntity) {
		this.blockEntity = blockEntity;
	}

	private boolean isClient() {
		if (this.blockEntity.getWorld() == null)
			throw new IllegalStateException("bone overrides were used on a block entity that is not in a world yet (" + this.blockEntity.getClass().getName() + ")");

		return this.blockEntity.getWorld().isClient();
	}

	@Override
	public void clear(String bone, OverrideProperty property) {
		if (isClient())
			ClientBoneOverrideStates.forBlock(this.blockEntity.getPos()).clearLocal(bone, property);
		else
			ServerBlockOverrides.overridesFor(this.blockEntity).clear(bone, property);
	}

	@Override
	@Nullable
	public BoneOverride get(String bone, OverrideProperty property) {
		if (isClient()) {
			ClientBoneOverrideState state = ClientBoneOverrideStates.getBlock(this.blockEntity.getPos());

			return state == null ? null : state.resolve(bone, property);
		}

		ServerBoneOverrideState state = ServerBlockOverrides.get(this.blockEntity);

		return state == null ? null : state.get(bone, property);
	}

	@Override
	void apply(String bone, OverrideProperty property, OverrideValue value, OverrideScope scope, int priority, float blendWeight, float transitionTicks) {
		if (isClient())
			ClientBoneOverrideStates.forBlock(this.blockEntity.getPos()).setLocal(bone, property, value, scope, priority, blendWeight, transitionTicks);
		else
			ServerBlockOverrides.overridesFor(this.blockEntity).set(bone, property, value, scope, priority, blendWeight, transitionTicks);
	}
}
