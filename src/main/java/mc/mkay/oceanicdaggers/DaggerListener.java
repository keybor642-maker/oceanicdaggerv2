package mc.mkay.oceanicdaggers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class DaggerListener implements Listener {

    private final OceanicDaggers plugin;

    private static final long DASH_COOLDOWN = 8000;
    private static final long TIDE_COOLDOWN = 12000;

    private final Map<UUID, Long>   dashCooldowns = new HashMap<>();
    private final Map<UUID, Long>   tideCooldowns = new HashMap<>();
    private final Set<UUID>         inTide        = new HashSet<>();
    private final Map<UUID, Double> tideLaunchY   = new HashMap<>();

    public DaggerListener(OceanicDaggers plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!DaggerItems.isDualWielding(player)) return;
        event.setCancelled(true);
        if (player.isSneaking()) activateTide(player);
        else activateDash(player);
    }

    // ─── ABILITY 1: DAGGER DASH ───────────────────────────────────────────────

    private void activateDash(Player player) {
        UUID uid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (dashCooldowns.containsKey(uid) && now - dashCooldowns.get(uid) < DASH_COOLDOWN) {
            long left = (DASH_COOLDOWN - (now - dashCooldowns.get(uid))) / 1000;
            player.sendActionBar(Component.text("⚔ Dash on cooldown: " + left + "s", NamedTextColor.RED));
            return;
        }

        LivingEntity target = getNearestTarget(player, 10);
        if (target == null) {
            player.sendActionBar(Component.text("No target in range!", NamedTextColor.GRAY));
            return;
        }

        dashCooldowns.put(uid, now);

        World world = player.getWorld();
        Location eyeLoc = player.getEyeLocation();

        // Sounds
        world.playSound(eyeLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.8f);
        world.playSound(eyeLoc, Sound.ITEM_TRIDENT_THROW, 1f, 1.5f);

        // Spawn the two dagger display entities in front of the player
        // Positioned at eye level, 1.2 blocks in front
        Location centre = eyeLoc.clone().add(eyeLoc.getDirection().multiply(1.2));

        // Right (left dagger) starts upper-left, left (right dagger) starts upper-right
        // We use ItemDisplay entities holding the dagger item
        ItemDisplay leftDisplay  = spawnDaggerDisplay(world, centre, player, true);
        ItemDisplay rightDisplay = spawnDaggerDisplay(world, centre, player, false);

        // Animate the cross: sweep from open → closed X over 6 ticks
        new BukkitRunnable() {
            int tick = 0;
            final int TOTAL = 6;

            @Override
            public void run() {
                if (tick >= TOTAL) {
                    // Daggers fully crossed — flash particles at cross point then remove
                    world.spawnParticle(Particle.SWEEP_ATTACK, centre, 6, 0.1, 0.1, 0.1, 0);
                    world.spawnParticle(Particle.CRIT, centre, 20, 0.3, 0.3, 0.3, 0.15);
                    world.spawnParticle(Particle.FLASH, centre, 1, 0, 0, 0, 0);
                    world.playSound(centre, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 1.3f);
                    leftDisplay.remove();
                    rightDisplay.remove();

                    // Now dash through
                    performDash(player, target, world);
                    cancel();
                    return;
                }

                double progress = (double) tick / TOTAL; // 0.0 → 1.0

                // Left dagger: starts top-left (-0.5, 0.3), sweeps to bottom-right (+0.2, -0.3)
                // Right dagger: starts top-right (+0.5, 0.3), sweeps to bottom-left (-0.2, -0.3)
                // Both rotate 45° as they cross
                updateDaggerDisplay(leftDisplay,  player, centre, progress, true);
                updateDaggerDisplay(rightDisplay, player, centre, progress, false);

                tick++;
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    /**
     * Spawns an ItemDisplay holding the dagger texture at the cross point.
     */
    private ItemDisplay spawnDaggerDisplay(World world, Location centre, Player player, boolean isLeft) {
        ItemDisplay display = (ItemDisplay) world.spawnEntity(centre, EntityType.ITEM_DISPLAY);
        display.setItemStack(DaggerItems.makeMainhand());
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        display.setViewRange(0.3f); // only visible up close
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        // Initial transform will be set immediately by updateDaggerDisplay
        updateDaggerDisplay(display, player, centre, 0.0, isLeft);
        return display;
    }

    /**
     * Updates a dagger display's transformation for the given animation progress (0→1).
     * isLeft = the dagger that sweeps from upper-left to lower-right.
     */
    private void updateDaggerDisplay(ItemDisplay display, Player player, Location centre, double progress, boolean isLeft) {
        // Offset relative to cross centre in player-local space
        // At progress=0: wide apart; at progress=1: fully crossed
        float side = isLeft ? -1f : 1f;

        float offsetX = side * (float)(0.45 * (1.0 - progress) + 0.05 * progress);
        float offsetY = (float)(0.25 * (1.0 - progress) - 0.25 * progress); // top → bottom
        float offsetZ = 0f;

        // Rotation: start at ±45°, rotate to ∓45° as they cross
        float startAngle = isLeft ? (float) Math.toRadians(45)  : (float) Math.toRadians(-45);
        float endAngle   = isLeft ? (float) Math.toRadians(-45) : (float) Math.toRadians(45);
        float angle = (float)(startAngle + (endAngle - startAngle) * progress);

        // Scale — slightly bigger than default so it's visible
        float scale = 0.6f;

        Transformation transform = new Transformation(
            new Vector3f(offsetX, offsetY, offsetZ),
            new AxisAngle4f(angle, 0, 0, 1), // rotate around Z (screen-plane spin)
            new Vector3f(scale, scale, scale),
            new AxisAngle4f(0, 0, 0, 1)
        );

        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTransformation(transform);
    }

    private void performDash(Player player, LivingEntity target, World world) {
        Location targetLoc  = target.getLocation();
        Vector dir = targetLoc.toVector().subtract(player.getLocation().toVector()).normalize();
        Location dashEnd = targetLoc.clone().add(dir.multiply(1.5)).add(0, 0.1, 0);
        dashEnd.setYaw(player.getLocation().getYaw());
        dashEnd.setPitch(player.getLocation().getPitch());

        // Trail as we dash
        Location start = player.getLocation().clone();
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t >= 5) { cancel(); return; }
                double p = t / 5.0;
                Location trail = start.clone().add(dir.clone().multiply(p * targetLoc.distance(start)));
                world.spawnParticle(Particle.DRIPPING_WATER, trail, 5, 0.1, 0.1, 0.1, 0);
                world.spawnParticle(Particle.CRIT, trail, 3, 0.1, 0.1, 0.1, 0.2);
                t++;
            }
        }.runTaskTimer(plugin, 0, 1);

        new BukkitRunnable() {
            @Override
            public void run() {
                player.teleport(dashEnd);
                target.damage(10.0, player);
                world.spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0,1,0), 8, 0.4, 0.4, 0.4, 0);
                world.spawnParticle(Particle.CRIT, target.getLocation().add(0,1,0), 20, 0.5, 0.5, 0.5, 0.1);
                world.playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 1.2f);
                player.sendActionBar(Component.text("⚔ Dagger Dash!", NamedTextColor.AQUA));
            }
        }.runTaskLater(plugin, 3);
    }

    // ─── ABILITY 2: OCEANIC TIDE ──────────────────────────────────────────────

    private void activateTide(Player player) {
        UUID uid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (tideCooldowns.containsKey(uid) && now - tideCooldowns.get(uid) < TIDE_COOLDOWN) {
            long left = (TIDE_COOLDOWN - (now - tideCooldowns.get(uid))) / 1000;
            player.sendActionBar(Component.text("≋ Tide on cooldown: " + left + "s", NamedTextColor.BLUE));
            return;
        }

        tideCooldowns.put(uid, now);
        inTide.add(uid);
        tideLaunchY.put(uid, player.getLocation().getY());

        World world = player.getWorld();
        Location loc = player.getLocation();

        for (int i = 0; i < 16; i++) {
            double angle = (Math.PI * 2 / 16) * i;
            double x = Math.cos(angle) * 2;
            double z = Math.sin(angle) * 2;
            world.spawnParticle(Particle.SPLASH, loc.clone().add(x, 0.1, z), 8, 0.1, 0.1, 0.1, 0.2);
            world.spawnParticle(Particle.DRIPPING_WATER, loc.clone().add(x, 0.5, z), 3, 0, 0, 0, 0);
        }
        world.spawnParticle(Particle.SPLASH, loc.clone().add(0, 0.5, 0), 30, 1, 0.5, 1, 0.3);
        world.playSound(loc, Sound.ENTITY_DOLPHIN_SPLASH, 1.5f, 0.6f);
        world.playSound(loc, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1f, 0.8f);

        player.setVelocity(new Vector(0, 1.4, 0));
        player.sendActionBar(Component.text("≋ Oceanic Tide! Crash down on enemies!", NamedTextColor.AQUA));

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                if (!inTide.contains(uid) || ticks > 40) { cancel(); return; }
                world.spawnParticle(Particle.DRIPPING_WATER, player.getLocation(), 6, 0.2, 0.2, 0.2, 0);
                world.spawnParticle(Particle.SPLASH, player.getLocation(), 3, 0.2, 0.2, 0.2, 0.1);
                ticks++;
            }
        }.runTaskTimer(plugin, 0, 2);

        new BukkitRunnable() {
            @Override
            public void run() {
                inTide.remove(uid);
                tideLaunchY.remove(uid);
            }
        }.runTaskLater(plugin, 60);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();
        if (!inTide.contains(uid)) return;
        if (player.isOnGround() && player.getLocation().getY() <= (tideLaunchY.getOrDefault(uid, 0.0) + 2)) {
            inTide.remove(uid);
            tideLaunchY.remove(uid);
            performTideCrash(player);
        }
    }

    private void performTideCrash(Player player) {
        World world = player.getWorld();
        Location loc = player.getLocation();
        world.spawnParticle(Particle.SPLASH, loc.clone().add(0, 0.5, 0), 60, 2, 0.5, 2, 0.3);
        world.spawnParticle(Particle.DRIPPING_WATER, loc.clone().add(0, 1, 0), 40, 1.5, 0.5, 1.5, 0.2);
        world.spawnParticle(Particle.CRIT, loc.clone().add(0, 0.5, 0), 20, 1, 0.3, 1, 0.1);
        world.playSound(loc, Sound.ENTITY_GENERIC_SPLASH, 2f, 0.5f);
        world.playSound(loc, Sound.ENTITY_DOLPHIN_SPLASH, 1.5f, 0.4f);
        world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.7f);

        for (Entity entity : world.getNearbyEntities(loc, 3, 3, 3)) {
            if (entity instanceof LivingEntity target && entity != player) {
                if (target instanceof Monster || target instanceof Player) {
                    target.damage(6.0, player);
                    Vector kb = target.getLocation().toVector()
                            .subtract(loc.toVector()).normalize()
                            .multiply(0.8).setY(0.4);
                    target.setVelocity(kb);
                }
            }
        }
        player.sendActionBar(Component.text("≋ Oceanic Tide Crash! ≋", NamedTextColor.BLUE));
    }

    // ─── UTILS ────────────────────────────────────────────────────────────────

    private LivingEntity getNearestTarget(Player player, double range) {
        Location eyeLoc = player.getEyeLocation();
        Vector dir = eyeLoc.getDirection();
        LivingEntity nearest = null;
        double nearestDist = range;

        for (Entity entity : player.getWorld().getNearbyEntities(eyeLoc, range, range, range)) {
            if (entity == player) continue;
            if (!(entity instanceof LivingEntity living)) continue;
            Vector toEntity = entity.getLocation().add(0, 1, 0).toVector().subtract(eyeLoc.toVector());
            double dist = toEntity.length();
            double dot = toEntity.normalize().dot(dir);
            if (dot > 0.7 && dist < nearestDist) {
                nearest = living;
                nearestDist = dist;
            }
        }
        return nearest;
    }
}
