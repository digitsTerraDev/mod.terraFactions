package dev.terrafactions.compat.toroidal;

import com.toroidalworld.api.v1.ToroidalWorldApi;
import dev.terrafactions.territory.TerritoryKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** Optional bridge between faction chunk geometry and Toroidal World's canonical coordinates. */
public final class ToroidalTerritoryCompat {
    private static final String MOD_ID = "toroidal_world";

    private ToroidalTerritoryCompat() {
    }

    public static TerritoryKey fold(MinecraftServer server, TerritoryKey key) {
        Level level = level(server, key.dimension());
        if (level == null || !ModList.get().isLoaded(MOD_ID)) return key;
        return Api.fold(level, key);
    }

    public static long distanceSquared(MinecraftServer server, TerritoryKey first, TerritoryKey second) {
        if (!first.dimension().equals(second.dimension())) return Long.MAX_VALUE;
        Level level = level(server, first.dimension());
        if (level == null || !ModList.get().isLoaded(MOD_ID)) return euclideanDistanceSquared(first, second);
        return Api.distanceSquared(level, first, second);
    }

    public static long chebyshevDistance(MinecraftServer server, TerritoryKey first, TerritoryKey second) {
        if (!first.dimension().equals(second.dimension())) return Long.MAX_VALUE;
        Level level = level(server, first.dimension());
        if (level == null || !ModList.get().isLoaded(MOD_ID)) {
            return Math.max(Math.abs((long) first.x() - second.x()),
                    Math.abs((long) first.z() - second.z()));
        }
        return Api.chebyshevDistance(level, first, second);
    }

    public static Vec3 nearestCopy(MinecraftServer server, String dimension, Vec3 reference, Vec3 target) {
        Level level = level(server, dimension);
        if (level == null || !ModList.get().isLoaded(MOD_ID)) return target;
        return Api.nearestCopy(level, reference, target);
    }

    public static double blockDistanceSquared(MinecraftServer server, String dimension,
                                              Vec3 first, Vec3 second) {
        Vec3 nearest = nearestCopy(server, dimension, first, second);
        return first.distanceToSqr(nearest);
    }

    private static Level level(MinecraftServer server, String dimension) {
        if (server == null) return null;
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        if (location == null) return null;
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    private static long euclideanDistanceSquared(TerritoryKey first, TerritoryKey second) {
        long dx = (long) first.x() - second.x();
        long dz = (long) first.z() - second.z();
        return dx * dx + dz * dz;
    }

    /** Kept nested so Toroidal classes are never resolved when the optional mod is absent. */
    private static final class Api {
        private static TerritoryKey fold(Level level, TerritoryKey key) {
            return ToroidalWorldApi.shapeOf(level).map(shape -> {
                ChunkPos folded = shape.fold(new ChunkPos(key.x(), key.z()));
                return new TerritoryKey(key.dimension(), folded.x, folded.z);
            }).orElse(key);
        }

        private static long distanceSquared(Level level, TerritoryKey first, TerritoryKey second) {
            return ToroidalWorldApi.shapeOf(level)
                    .map(shape -> {
                        Vec3 origin = chunkCenter(first);
                        Vec3 nearest = shape.nearestCopy(origin, chunkCenter(second));
                        double dx = (nearest.x - origin.x) / 16.0D;
                        double dz = (nearest.z - origin.z) / 16.0D;
                        return Math.round(dx * dx + dz * dz);
                    })
                    .orElseGet(() -> euclideanDistanceSquared(first, second));
        }

        private static long chebyshevDistance(Level level, TerritoryKey first, TerritoryKey second) {
            return ToroidalWorldApi.shapeOf(level)
                    .map(shape -> {
                        Vec3 origin = chunkCenter(first);
                        Vec3 nearest = shape.nearestCopy(origin, chunkCenter(second));
                        return Math.round(Math.max(Math.abs(nearest.x - origin.x),
                                Math.abs(nearest.z - origin.z)) / 16.0D);
                    })
                    .orElseGet(() -> Math.max(Math.abs((long) first.x() - second.x()),
                            Math.abs((long) first.z() - second.z())));
        }

        private static Vec3 nearestCopy(Level level, Vec3 reference, Vec3 target) {
            return ToroidalWorldApi.shapeOf(level)
                    .map(shape -> shape.nearestCopy(reference, target))
                    .orElse(target);
        }

        private static Vec3 chunkCenter(TerritoryKey key) {
            return new Vec3(key.x() * 16.0D + 8.0D, 0.0D, key.z() * 16.0D + 8.0D);
        }
    }
}
