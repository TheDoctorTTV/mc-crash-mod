package io.github.thedoctorttv.fakecrash;

import com.mojang.blaze3d.platform.GlStateManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;

@OnlyIn(Dist.CLIENT)
public final class ClientPrank {
    private ClientPrank() { }

    public static void show(UUID token, byte[] image) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.world != null) {
            List<DynamicTexture> textures = new ArrayList<>();
            List<ResourceLocation> locations = new ArrayList<>();
            try {
                ImageAnimation animation = ImageAnimation.decode(image);
                int width = 0, height = 0;
                for (byte[] frame : animation.frames) {
                    NativeImage pixels = NativeImage.read(new ByteArrayInputStream(frame));
                    width = pixels.getWidth(); height = pixels.getHeight();
                    DynamicTexture texture;
                    try {
                        texture = new DynamicTexture(pixels);
                    } catch (RuntimeException ex) {
                        pixels.close();
                        throw ex;
                    }
                    textures.add(texture);
                    locations.add(minecraft.getTextureManager().getDynamicTextureLocation("ped_display", texture));
                }
                minecraft.displayGuiScreen(new PrankScreen(token, locations, textures, animation, width, height));
            } catch (IOException | RuntimeException ex) {
                textures.forEach(DynamicTexture::close);
                locations.forEach(minecraft.getTextureManager()::deleteTexture);
                LogManager.getLogger().warn("ped could not display the received image", ex);
            }
        }
    }

    private static final class PrankScreen extends Screen {
        private final List<ResourceLocation> images;
        private final UUID token;
        private final List<DynamicTexture> textures;
        private final ImageAnimation animation;
        private long started;
        private final int imageWidth;
        private final int imageHeight;
        private boolean acknowledged;

        PrankScreen(UUID token, List<ResourceLocation> images, List<DynamicTexture> textures,
                    ImageAnimation animation, int imageWidth, int imageHeight) {
            super(new StringTextComponent("Unexpected error"));
            this.token = token;
            this.images = images;
            this.textures = textures;
            this.animation = animation;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }

        @Override
        public void render(int mouseX, int mouseY, float partialTicks) {
            fill(0, 0, width, height, 0xFF000000);
            if (!acknowledged) started = System.nanoTime();
            int frame = animation.frameAt((System.nanoTime() - started) / 1_000_000L);
            minecraft.getTextureManager().bindTexture(images.get(frame));
            GlStateManager.color4f(1, 1, 1, 1);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(770, 771);
            double scale = Math.max(0.01, Math.min((width - 24.0) / imageWidth, (height - 48.0) / imageHeight));
            int drawWidth = Math.max(1, (int) (imageWidth * scale));
            int drawHeight = Math.max(1, (int) (imageHeight * scale));
            blit((width - drawWidth) / 2, (height - drawHeight) / 2 - 8,
                    0, 0, drawWidth, drawHeight, drawWidth, drawHeight);
            GlStateManager.disableBlend();
            drawCenteredString(font, "An unexpected error occurred.", width / 2, height - 22, 0xFFFFFF);
            if (!acknowledged) {
                acknowledged = true;
                PrankNetwork.CHANNEL.sendToServer(new PrankNetwork.ImageDisplayed(token));
            }
        }

        @Override
        public void removed() {
            textures.forEach(DynamicTexture::close);
            images.forEach(minecraft.getTextureManager()::deleteTexture);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
