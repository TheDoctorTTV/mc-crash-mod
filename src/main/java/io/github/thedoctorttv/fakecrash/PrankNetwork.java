package io.github.thedoctorttv.fakecrash;

import java.util.function.Supplier;
import java.util.UUID;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.simple.SimpleChannel;

public final class PrankNetwork {
    private static final String PROTOCOL = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(FakeCrash.MOD_ID, "main"), () -> PROTOCOL,
            PROTOCOL::equals, PROTOCOL::equals);

    private PrankNetwork() { }

    public static void register() {
        CHANNEL.registerMessage(0, ShowImage.class, ShowImage::encode, ShowImage::decode,
                ShowImage::handle);
        CHANNEL.registerMessage(1, ImageDisplayed.class, ImageDisplayed::encode, ImageDisplayed::decode,
                ImageDisplayed::handle);
    }

    public static final class ShowImage {
        private final UUID token;
        private final byte[] image;

        public ShowImage(UUID token, byte[] image) {
            this.token = token;
            this.image = image;
        }

        private void encode(PacketBuffer buffer) {
            buffer.writeUniqueId(token);
            buffer.writeByteArray(image);
        }

        private static ShowImage decode(PacketBuffer buffer) {
            return new ShowImage(buffer.readUniqueId(), buffer.readByteArray(ImageLoader.MAX_PACKET_BYTES));
        }

        private void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> DistExecutor.runWhenOn(Dist.CLIENT,
                    () -> () -> ClientPrank.show(token, image)));
            context.setPacketHandled(true);
        }
    }

    public static final class ImageDisplayed {
        private final UUID token;

        public ImageDisplayed(UUID token) { this.token = token; }
        private void encode(PacketBuffer buffer) { buffer.writeUniqueId(token); }
        private static ImageDisplayed decode(PacketBuffer buffer) { return new ImageDisplayed(buffer.readUniqueId()); }
        private void handle(Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
                context.enqueueWork(() -> FakeCrash.imageDisplayed(context.getSender(), token));
            }
            context.setPacketHandled(true);
        }
    }
}
