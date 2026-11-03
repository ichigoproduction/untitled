package untitled.untitled.client;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Glass version of Snow: a cloud of glass shards scatters, lands, and leaves
 * small temporary translucent glass tiles on the ground. Entirely client-side.
 */
public final class GlassKillEffect {
    private static final int SHARDS_PER_KILL = 27;
    private static final int MAX_SHARDS = 108;
    private static final int MAX_TILES = 90;
    private static final long FLIGHT_TIMEOUT_MS = 2300L;
    private static final long TILE_LIFETIME_MS = 1850L;
    private static final double GRAVITY = 0.035;
    private static final Random RANDOM = new Random();
    private static final BlockStateParticleEffect GLASS_SHARD =
            new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.GLASS.getDefaultState());
    private static final String[] GLASS_BLOCKS = {
            "minecraft:glass",
            "minecraft:light_blue_stained_glass",
            "minecraft:white_stained_glass"
    };
    private static final List<Shard> SHARDS = new ArrayList<>();
    private static final List<GlassTile> TILES = new ArrayList<>();
    private static int nextEntityId = -1523000;

    private GlassKillEffect() {}

    private static final class Shard {
        final ClientWorld world;
        final long startedAt;
        Vec3d position;
        Vec3d velocity;

        Shard(ClientWorld world, Vec3d position, Vec3d velocity, long startedAt) {
            this.world = world;
            this.position = position;
            this.velocity = velocity;
            this.startedAt = startedAt;
        }
    }

    private static final class GlassTile {
        final ClientWorld world;
        final DisplayEntity.BlockDisplayEntity display;
        final long expiresAt;

        GlassTile(ClientWorld world, DisplayEntity.BlockDisplayEntity display, long expiresAt) {
            this.world = world;
            this.display = display;
            this.expiresAt = expiresAt;
        }
    }

    public static void spawn(ClientWorld world, Vec3d victimPosition, long nowMs) {
        if (world == null || victimPosition == null) return;
        Vec3d center = victimPosition.add(0.0, 1.05, 0.0);
        world.playSound(center.x, center.y, center.z,
                resolveSound(SoundEvents.BLOCK_GLASS_BREAK),
                SoundCategory.PLAYERS, 0.88f, 1.18f, false);

        for (int i = 0; i < SHARDS_PER_KILL; i++) {
            if (SHARDS.size() >= MAX_SHARDS) SHARDS.remove(0);
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double horizontalSpeed = 0.10 + RANDOM.nextDouble() * 0.20;
            double rise = 0.10 + RANDOM.nextDouble() * 0.18;
            SHARDS.add(new Shard(world, center.add(
                    (RANDOM.nextDouble() - 0.5) * 0.32,
                    (RANDOM.nextDouble() - 0.5) * 0.32,
                    (RANDOM.nextDouble() - 0.5) * 0.32),
                    new Vec3d(Math.cos(angle) * horizontalSpeed, rise,
                            Math.sin(angle) * horizontalSpeed), nowMs));
        }
    }

    public static void tick(ClientWorld world, long nowMs) {
        Iterator<Shard> shardIterator = SHARDS.iterator();
        while (shardIterator.hasNext()) {
            Shard shard = shardIterator.next();
            if (shard.world != world || nowMs - shard.startedAt > FLIGHT_TIMEOUT_MS) {
                shardIterator.remove();
                continue;
            }

            Vec3d nextPosition = shard.position.add(shard.velocity);
            BlockHitResult collision = world.raycast(new RaycastContext(
                    shard.position,
                    nextPosition,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    MinecraftClient.getInstance().player));

            if (collision.getType() == HitResult.Type.BLOCK) {
                Vec3d contact = collision.getPos();
                if (collision.getSide() == Direction.UP) spawnTile(world, contact, nowMs);
                for (int i = 0; i < 4; i++) {
                    world.addParticle(GLASS_SHARD,
                            contact.x, contact.y + 0.03, contact.z,
                            (RANDOM.nextDouble() - 0.5) * 0.12,
                            0.03 + RANDOM.nextDouble() * 0.05,
                            (RANDOM.nextDouble() - 0.5) * 0.12);
                }
                shardIterator.remove();
                continue;
            }

            shard.position = nextPosition;
            shard.velocity = shard.velocity.multiply(0.985).add(0.0, -GRAVITY, 0.0);
            world.addParticle(GLASS_SHARD,
                    nextPosition.x, nextPosition.y, nextPosition.z,
                    0.0, 0.0, 0.0);
        }

        Iterator<GlassTile> tileIterator = TILES.iterator();
        while (tileIterator.hasNext()) {
            GlassTile tile = tileIterator.next();
            if (tile.world != world || nowMs >= tile.expiresAt || tile.display.isRemoved()) {
                if (!tile.display.isRemoved()) tile.display.discard();
                tileIterator.remove();
            }
        }
    }

    private static void spawnTile(ClientWorld world, Vec3d hitPosition, long nowMs) {
        if (TILES.size() >= MAX_TILES) {
            GlassTile oldest = TILES.remove(0);
            if (!oldest.display.isRemoved()) oldest.display.discard();
        }

        // Block displays are visual only. A scaled full-size glass cube is a thin
        // transparent tile, resembling scattered glass rather than placed blocks.
        double side = 0.35 + RANDOM.nextDouble() * 0.43;
        DisplayEntity.BlockDisplayEntity tile =
                new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
        NbtCompound nbt = new NbtCompound();
        NbtCompound blockState = new NbtCompound();
        blockState.putString("Name", GLASS_BLOCKS[RANDOM.nextInt(GLASS_BLOCKS.length)]);
        nbt.put("block_state", blockState);

        NbtCompound transform = new NbtCompound();
        transform.put("translation", floats(0.0f, 0.0f, 0.0f));
        transform.put("left_rotation", floats(0.0f, 0.0f, 0.0f, 1.0f));
        transform.put("scale", floats((float) side, 0.12f, (float) side));
        transform.put("right_rotation", floats(0.0f, 0.0f, 0.0f, 1.0f));
        nbt.put("transformation", transform);
        nbt.putFloat("view_range", 1.5f);
        nbt.putFloat("display_width", 1.0f);
        nbt.putFloat("display_height", 0.20f);

        tile.readNbt(nbt);
        tile.setId(nextEntityId--);
        tile.setPosition(
                hitPosition.x - side / 2.0,
                hitPosition.y + 0.015,
                hitPosition.z - side / 2.0);
        world.addEntity(tile);
        TILES.add(new GlassTile(world, tile, nowMs + TILE_LIFETIME_MS));

        if (RANDOM.nextInt(8) == 0) {
            world.playSound(hitPosition.x, hitPosition.y, hitPosition.z,
                    resolveSound(SoundEvents.BLOCK_GLASS_HIT),
                    SoundCategory.PLAYERS, 0.20f, 1.40f, false);
        }
    }

    private static NbtList floats(float... values) {
        NbtList list = new NbtList();
        for (float value : values) list.add(NbtFloat.of(value));
        return list;
    }

    private static SoundEvent resolveSound(Object sound) {
        if (sound instanceof SoundEvent event) return event;
        if (sound instanceof RegistryEntry<?> entry && entry.value() instanceof SoundEvent event) return event;
        throw new IllegalArgumentException("Unknown sound event type: " + sound);
    }
}
