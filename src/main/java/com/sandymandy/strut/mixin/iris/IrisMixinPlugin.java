package com.sandymandy.strut.mixin.iris;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates every mixin in {@code strut-iris.mixins.json} behind Iris actually being installed.
 * Those mixins target Iris's own internal classes
 * directly by string (never a class literal - see {@code ShadowRendererMixin}'s own doc comment on
 * why), unlike the rest of this mod's Iris integration ({@code IrisCompat}/{@code IrisApiAccess}),
 * which only ever touches Iris through its public API and is safe with Iris absent regardless.
 * Mixin resolves a string target lazily, only when it actually attempts to apply a mixin against
 * it - {@link #shouldApplyMixin} returning {@code false} here is what stops that attempt from
 * happening at all when Iris isn't loaded, which is what keeps a target class that flatly doesn't
 * exist from being a hard crash at mod load instead of the soft no-op this integration needs
 */
public class IrisMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return FabricLoader.getInstance().isModLoaded("iris");
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
