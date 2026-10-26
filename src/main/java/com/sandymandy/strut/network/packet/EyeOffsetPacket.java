package com.sandymandy.strut.network.packet;

import com.sandymandy.strut.StrutConstants;
import com.sandymandy.strut.player.ServerEyes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Client to server: where the eye bone of the sender's immersive body is, relative to where the body is drawn, so what they aim at is worked out from there
 * on the server too (see {@link ServerEyes}). {@code present} false says the sender no longer looks out of a body
 */
public record EyeOffsetPacket(boolean present, float x, float y, float z) implements MultiloaderPacket {
	public static final Id<EyeOffsetPacket> TYPE = new Id<>(StrutConstants.id("eye_offset"));
	public static final PacketCodec<PacketByteBuf, EyeOffsetPacket> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOL, EyeOffsetPacket::present,
			PacketCodecs.FLOAT, EyeOffsetPacket::x,
			PacketCodecs.FLOAT, EyeOffsetPacket::y,
			PacketCodecs.FLOAT, EyeOffsetPacket::z,
			EyeOffsetPacket::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return TYPE;
	}

	@Override
	public void receiveMessage(@Nullable PlayerEntity sender, Consumer<Runnable> workQueue) {
		workQueue.accept(() -> {
			if (sender instanceof ServerPlayerEntity player)
				ServerEyes.set(player, this.present ? new Vec3d(this.x, this.y, this.z) : null);
		});
	}
}
