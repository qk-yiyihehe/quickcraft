package com.yiyihehe.quickcraft.litematica;

import fi.dy.masa.malilib.util.data_syncer.EntityDataCache;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(com.yiyihehe.quickcraft.QuickCraftMinecraftTestBootstrap.class)
class QuickLitematicaVerifierAccessCacheTest {
    private static final BlockPos POS = new BlockPos(1, 64, 2);

    @Test
    void missingCacheEntryDoesNotBecomeAnEmptyServerResponse() {
        EntityDataCache cache = new EntityDataCache("quickcraft-test", 60_000L);
        assertThat(cache.getBlockEntityNbtFromCache(POS)).isNotNull();
        assertThat(cache.getBlockEntityNbtFromCache(POS).isEmpty()).isTrue();

        assertThat(QuickLitematicaVerifierAccess.getReceivedBlockEntityNbtFromCache(cache, POS)).isNull();
    }

    @Test
    void existingCacheEntryKeepsItsReturnedNbt() {
        EntityDataCache cache = new EntityDataCache("quickcraft-test", 60_000L);
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", "minecraft:chest");
        var payload = DataConverterNbt.fromVanillaCompound(nbt);
        cache.addToCache(POS, null, payload);
        try (var minecraft = org.mockito.Mockito.mockStatic(net.minecraft.client.MinecraftClient.class)) {
            var client = org.mockito.Mockito.mock(net.minecraft.client.MinecraftClient.class);
            client.world = org.mockito.Mockito.mock(net.minecraft.client.world.ClientWorld.class);
            minecraft.when(net.minecraft.client.MinecraftClient::getInstance).thenReturn(client);
            assertThat(QuickLitematicaVerifierAccess.getReceivedBlockEntityNbtFromCache(cache, POS)).isNull();
            QuickLitematicaContainerVerifier.rememberReceivedBlockEntityData(client.world, POS, payload);
            assertThat(QuickLitematicaVerifierAccess.getReceivedBlockEntityNbtFromCache(cache, POS)).isEqualTo(nbt);
        } finally {
            QuickLitematicaContainerInventory.wasReceivedBlockEntityData(null, POS, null);
        }
    }
}
