package untitled.untitled.client;

import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
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
 * Client-only snowball burst with temporary snow-layer block displays.
 * No blocks are placed in the world and nothing is sent to the server.
 */
public final class SnowKillEffect {
    private static final int SNOWBALLS_PER_BURST = 28;
    private static final int MAX_SNOWBALLS = 112;
    private static final int MAX_PATCHES = 96;
    private static final long BALL_LIFETIME_MS = 2200L;
    private static final long PATCH_LIFETIME_MS = 1900L;
    private static final double GRAVITY = 0.035;
    private static final Random RANDOM = new Random();
    private static final List<Snowball> BALLS = new ArrayList<>();
    private static final List<SnowPatch> PATCHES = new ArrayList<>();
    private static int nextLocalId = -1521000;

    private SnowKillEffect() {}

    private static final class Snowball {
        final ClientWorld world;
        final long started;
        Vec3d pos;
        Vec3d velocity;

        Snowball(ClientWorld world, Vec3d pos, Vec3d velocity, long started) {
            this.world = world;
            this.pos = pos;
            this.velocity = velocity;
            this.started = started;
        }
    }

    private static final class SnowPatch {
        final ClientWorld world;
        final DisplayEntity.BlockDisplayEntity display;
        final long expires;

        SnowPatch(ClientWorld world, DisplayEntity.BlockDisplayEntity display, long expires) {
            this.world = world;
            this.display = display;
            this.expires = expires;
        }
    }

    public static void spawn(ClientWorld world, Vec3d killedPlayerPos, long nowMs) {
        if (world == null || killedPlayerPos == null) return;
        Vec3d origin = killedPlayerPos.add(0, 1.05, 0);
        world.playSound(origin.x, origin.y, origin.z,
                SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.9f, 0.90f, false);

        for (int i = 0; i < SNOWBALLS_PER_BURST; i++) {
            if (BALLS.size() >= MAX_SNOWBALLS) BALLS.remove(0);
            double direction = RANDOM.nextDouble() * Math.PI * 2.0;
            double speed = 0.10 + RANDOM.nextDouble() * 0.19;
            double lift = 0.09 + RANDOM.nextDouble() * 0.19;
            Vec3d velocity = new Vec3d(Math.cos(direction) * speed, lift, Math.sin(direction) * speed);
            BALLS.add(new Snowball(world, origin.add(
                    (RANDOM.nextDouble() - 0.5) * 0.35,
                    (RANDOM.nextDouble() - 0.5) * 0.35,
                    (RANDOM.nextDouble() - 0.5) * 0.35
            ), velocity, nowMs));
        }
    }

    public static void tick(ClientWorld currentWorld, long nowMs) {
        Iterator<Snowball> balls = BALLS.iterator();
        while (balls.hasNext()) {
            Snowball ball = balls.next();
            if (ball.world != currentWorld || nowMs - ball.started > BALL_LIFETIME_MS) {
                balls.remove();
                continue;
            }

            Vec3d next = ball.pos.add(ball.velocity);
            BlockHitResult hit = currentWorld.raycast(new RaycastContext(
                    ball.pos, next, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, null));

            if (hit.getType() == HitResult.Type.BLOCK) {
                if (hit.getSide() == Direction.UP) {
                    spawnPatch(currentWorld, hit.getPos(), nowMs);
                }
                for (int i = 0; i < 3; i++) {
                    currentWorld.addParticle(ParticleTypes.SNOWFLAKE,
                            hit.getPos().x, hit.getPos().y + 0.04, hit.getPos().z,
                            (RANDOM.nextDouble() - 0.5) * 0.10,
                            RANDOM.nextDouble() * 0.08,
                            (RANDOM.nextDouble() - 0.5) * 0.10);
                }
                balls.remove();
                continue;
            }

            ball.pos = next;
            ball.velocity = ball.velocity.multiply(0.985).add(0.0, -GRAVITY, 0.0);
            currentWorld.addParticle(ParticleTypes.ITEM_SNOWBALL,
                    next.x, next.y, next.z, 0.0, 0.0, 0.0);
            if (RANDOM.nextInt(4) == 0) {
                currentWorld.addParticle(ParticleTypes.SNOWFLAKE,
                        next.x, next.y, next.z, 0.0, 0.012, 0.0);
            }
        }

        Iterator<SnowPatch> patches = PATCHES.iterator();
        while (patches.hasNext()) {
            SnowPatch patch = patches.next();
            if (patch.world != currentWorld || nowMs >= patch.expires || patch.display.isRemoved()) {
                if (!patch.display.isRemoved()) patch.display.discard();
                patches.remove();
            }
        }
    }

    private static void spawnPatch(ClientWorld world, Vec3d hitPos, long nowMs) {
        if (PATCHES.size() >= MAX_PATCHES) {
            SnowPatch oldest = PATCHES.remove(0);
            if (!oldest.display.isRemoved()) oldest.display.discard();
        }

        // A one-layer snow block is rendered as a local display entity, never a real block.
        // Its NBT sets a smaller footprint while retaining vanilla snow texture and thickness.
        double scale = 0.42 + RANDOM.nextDouble() * 0.38;
        DisplayEntity.BlockDisplayEntity display =
                new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);

        NbtCompound root = new NbtCompound();
        NbtCompound blockState = new NbtCompound();
        blockState.putString("Name", "minecraft:snow");
        NbtCompound properties = new NbtCompound();
        properties.putString("layers", "1");
        blockState.put("Properties", properties);
        root.put("block_state", blockState);

        NbtCompound transformation = new NbtCompound();
        transformation.put("translation", floats(0.0f, 0.0f, 0.0f));
        transformation.put("left_rotation", floats(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("scale", floats((float) scale, 1.0f, (float) scale));
        transformation.put("right_rotation", floats(0.0f, 0.0f, 0.0f, 1.0f));
        root.put("transformation", transformation);
        root.putFloat("view_range", 1.5f);
        root.putFloat("display_width", 1.0f);
        root.putFloat("display_height", 0.25f);

        display.readNbt(root);
        display.setId(nextLocalId--);
        display.setPosition(
                hitPos.x - scale / 2.0,
                hitPos.y + 0.012,
                hitPos.z - scale / 2.0);
        world.addEntity(display);
        PATCHES.add(new SnowPatch(world, display, nowMs + PATCH_LIFETIME_MS));

        // Avoid playing dozens of loud overlapping sounds.
        if (RANDOM.nextInt(7) == 0) {
            world.playSound(hitPos.x, hitPos.y, hitPos.z,
                    SoundEvents.BLOCK_SNOW_HIT, SoundCategory.PLAYERS, 0.25f, 1.10f, false);
        }
    }

    private static NbtList floats(float... values) {
        NbtList list = new NbtList();
        for (float value : values) list.add(NbtFloat.of(value));
        return list;
    }
}
