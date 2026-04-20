package com.talhanation.recruits.entities.ai.compat;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.compat.jeg.JEGWeapon;
import com.talhanation.recruits.compat.musketmod.IWeapon;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumSet;

/**
 * AI Goal for recruits using JEG (JustEnoughGuns) weapons.
 * Respects JEG's native reload tracking system using NBT tags (Ammo, MaxAmmo).
 */
public class RecruitRangedGunnerAttackGoal extends Goal {
    private final CrossBowmanEntity recruit;
    private final double stopRange;
    private final CombatController combatController;
    
    private LivingEntity target;
    private JEGWeapon weapon;
    private CombatState state;
    private int seeTime;
    private int reloadProgress;
    private int shotCooldown;

    public RecruitRangedGunnerAttackGoal(CrossBowmanEntity recruit, double stopRange) {
        this.recruit = recruit;
        this.stopRange = stopRange;
        this.combatController = new CombatController(recruit);
        this.state = CombatState.IDLE;
        this.reloadProgress = 0;
        this.shotCooldown = 0;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity currentTarget = recruit.getTarget();
        boolean shouldRanged = recruit.getShouldRanged();
        
        if (currentTarget != null && currentTarget.isAlive() && shouldRanged) {
            if (!ensureJEGWeapon()) {
                return false;
            }
            
            double distance = currentTarget.distanceTo(recruit);
            return distance >= stopRange 
                && canAttackMovePos() 
                && !recruit.needsToGetFood() 
                && !recruit.getShouldMount();
        }
        
        // Strategic fire always takes priority
        if (recruit.getShouldStrategicFire()) {
            return true;
        }
        
        // Don't interfere with formations
        return !recruit.isInFormation && !recruit.getShouldHoldPos() && ensureJEGWeapon();
    }

    @Override
    public boolean canContinueToUse() {
        // Keep goal active during combat states
        if (state == CombatState.RELOAD || state == CombatState.AIMING || state == CombatState.SHOOT) {
            return true;
        }
        return canUse();
    }

    @Override
    public void start() {
        super.start();
        recruit.setAggressive(true);
        state = CombatState.IDLE;
        seeTime = 0;
        reloadProgress = 0;
        shotCooldown = 0;
        target = recruit.getTarget();
        
        Main.LOGGER.debug("JEG Goal started for {} with weapon: {}", 
            recruit.getName(), recruit.getMainHandItem().getDescriptionId());
    }

    @Override
    public void stop() {
        super.stop();
        recruit.setAggressive(false);
        recruit.setChargingCrossbow(false);
        seeTime = 0;
        reloadProgress = 0;
        shotCooldown = 0;
        state = CombatState.IDLE;
    }

    @Override
    public void tick() {
        target = recruit.getTarget();
        
        // Update weapon reference
        if (!ensureJEGWeapon()) {
            stop();
            return;
        }
        
        // Decrement cooldowns
        if (shotCooldown > 0) {
            shotCooldown--;
        }
        
        handleMovement();
        handleCombat();
    }

    private void handleMovement() {
        if (target == null || !target.isAlive()) {
            handleNoTargetMovement();
            return;
        }
        
        double distance = target.distanceTo(recruit);
        
        // Out of range - clear target
        if (distance >= 4500) {
            recruit.setTarget(null);
            stop();
            return;
        }
        
        // Determine movement behavior
        if (shouldStandStill(target)) {
            recruit.getNavigation().stop();
        } else if (recruit.getShouldFollow() && recruit.getOwner() != null) {
            combatController.followWithTarget(recruit.getOwner(), target);
        } else if (recruit.getShouldHoldPos() && recruit.getHoldPos() != null) {
            combatController.holdPosition(recruit.getHoldPos());
        } else {
            handleCombatMovement(distance);
        }
    }

    private void handleNoTargetMovement() {
        if (recruit.getShouldFollow() && recruit.getOwner() != null) {
            combatController.follow(recruit.getOwner());
        } else if (recruit.getShouldHoldPos() && recruit.getHoldPos() != null) {
            combatController.holdPosition(recruit.getHoldPos());
        }
    }

    private void handleCombatMovement(double distance) {
        double minRange = getIdealMinRange();
        double maxRange = getIdealMaxRange();
        
        if (distance < minRange) {
            combatController.retreat(target, minRange - distance);
        } else if (distance > maxRange) {
            combatController.advance(target);
        } else {
            recruit.getNavigation().stop();
        }
    }

    private void handleCombat() {
        if (!ensureJEGWeapon()) return;
        
        BlockPos strategicPos = recruit.getShouldStrategicFire() ? recruit.getStrategicFirePos() : null;
        
        if (strategicPos != null) {
            handleStrategicFire(strategicPos);
        } else if (target != null && target.isAlive()) {
            handleNormalCombat();
        } else {
            state = CombatState.IDLE;
        }
    }

    private void handleNormalCombat() {
        switch (state) {
            case IDLE -> transitionFromIdle(true);
            case RELOAD -> tickReload(true);
            case AIMING -> tickAiming();
            case SHOOT -> tickShoot();
        }
    }

    private void handleStrategicFire(BlockPos pos) {
        switch (state) {
            case IDLE -> transitionFromIdle(false);
            case RELOAD -> tickReload(false);
            case AIMING -> tickStrategicAiming(pos);
            case SHOOT -> tickStrategicShoot(pos);
        }
    }

    private void transitionFromIdle(boolean hasTarget) {
        recruit.setChargingCrossbow(false);
        seeTime = 0;
        reloadProgress = 0;
        
        int currentAmmo = getJEGCurrentAmmo();
        int capacity = getJEGMagazineCapacity();
        
        // Only trigger reload when magazine is empty AND we have ammo in inventory.
        if (currentAmmo == 0 && hasJEGInventoryAmmo()) {
            state = CombatState.RELOAD;
            return;
        }
        
        // Proceed to aiming if we have ammo, otherwise stay idle.
        if (currentAmmo > 0) {
            state = hasTarget && target != null && target.isAlive() ? CombatState.AIMING : CombatState.IDLE;
        } else {
            state = CombatState.IDLE;
        }
    }

    private void tickReload(boolean hasTarget) {
        recruit.setChargingCrossbow(true);
        reloadProgress++;

        int reloadTime = getJEGReloadTime();
        Main.LOGGER.info("Reload in progress: {}/{} ticks", reloadProgress, reloadTime);

        if (reloadProgress >= reloadTime) {
            Main.LOGGER.info("Reload time complete, consuming ammo and marking weapon as loaded...");
            playReloadSound();

            ItemStack mainHand = recruit.getMainHandItem();

            // Respect the server config: if ammo consumption is disabled, just fill magazine
            if (!RecruitsServerConfig.RangedRecruitsNeedArrowsToShoot.get() || recruitHasInfiniteAmmo()) {
                int magSize = getJEGMagazineCapacity();
                JEGWeapon.writeAmmoNormalized(mainHand, magSize);
                mainHand.getOrCreateTag().putInt("MaxAmmo", magSize);
                recruit.setChargingCrossbow(false);
                reloadProgress = 0;
                state = hasTarget && target != null && target.isAlive() ? CombatState.AIMING : CombatState.IDLE;
                Main.LOGGER.info("Reload (infinite ammo mode): magazine set to {}/{}", magSize, magSize);
                return;
            }

            // First: consume ammo from inventory to fill magazine
            weapon.consumeAmmoFromInventory(recruit);

            // Only mark as loaded if ammo was actually consumed (Ammo > 0)
            if (weapon.isLoaded(mainHand)) {
                weapon.setLoaded(mainHand, true);
                recruit.setChargingCrossbow(false);
                reloadProgress = 0;
                state = hasTarget && target != null && target.isAlive() ? CombatState.AIMING : CombatState.IDLE;
                Main.LOGGER.info("Reload successful: weapon is now loaded with {} ammo",
                    mainHand.getOrCreateTag().getInt("Ammo"));
            } else {
                // Reload failed - no ammo in inventory
                recruit.setChargingCrossbow(false);
                reloadProgress = 0;
                state = CombatState.IDLE;
                Main.LOGGER.warn("Reload failed: no ammo in inventory, magazine remains at {}",
                    mainHand.getOrCreateTag().getInt("Ammo"));
            }
        }
    }

    private void tickAiming() {
        if (!canSeeTarget()) {
            state = CombatState.IDLE;
            recruit.setAggressive(false);
            seeTime = 0;
            return;
        }
        
        recruit.getLookControl().setLookAt(target);
        recruit.setAggressive(true);
        seeTime++;
        
        int aimTime = getAimingTime();
        if (seeTime >= aimTime + recruit.getRandom().nextInt(5)) {
            state = CombatState.SHOOT;
            seeTime = 0;
        }
    }

    private void tickStrategicAiming(BlockPos pos) {
        recruit.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        recruit.setAggressive(true);
        seeTime++;
        
        if (seeTime >= 15 + recruit.getRandom().nextInt(15)) {
            state = CombatState.SHOOT;
            seeTime = 0;
        }
    }

    private void tickShoot() {
        if (shotCooldown > 0) {
            return;
        }
        
        if (getJEGCurrentAmmo() <= 0) {
            Main.LOGGER.info("No ammo in magazine, transitioning to IDLE");
            state = CombatState.IDLE;
            return;
        }
        
        if (target == null || !target.isAlive() || !recruit.canAttack(target)) {
            state = CombatState.IDLE;
            return;
        }
        
        if (weapon == null) {
            state = CombatState.IDLE;
            return;
        }
        
        if (shouldStandStill(target)) {
            recruit.getNavigation().stop();
        }
        
        recruit.getLookControl().setLookAt(target);
        Main.LOGGER.info("FIRING SHOT - ammo before: {}", getJEGCurrentAmmo());
        weapon.performRangedAttackIWeapon(recruit, target.getX(), target.getY(), target.getZ(), 
            weapon.getProjectileSpeed());
        
        // NOTE: performRangedAttackIWeapon already calls setLoaded(false) which decrements ammo
        
        recruit.setChargingCrossbow(false);
        shotCooldown = weapon.getAttackCooldown();
        
        Main.LOGGER.info("SHOT FIRED - ammo after: {}", getJEGCurrentAmmo());
        
        // Handle state transition based on remaining ammo
        int remainingAmmo = getJEGCurrentAmmo();
        int capacity = getJEGMagazineCapacity();
        
        // Only reload if magazine is completely empty and ammo present in inventory.
        if (remainingAmmo == 0 && hasJEGInventoryAmmo()) {
            Main.LOGGER.info("After shot: magazine empty, transitioning to RELOAD");
            state = CombatState.RELOAD;
        } else if (remainingAmmo > 0) {
            Main.LOGGER.info("After shot: ammo ({}) still available, transitioning to AIMING", remainingAmmo);
            state = CombatState.AIMING;
        } else {
            Main.LOGGER.info("After shot: no ammo left ({}), transitioning to IDLE", remainingAmmo);
            state = CombatState.IDLE;
        }
    }

    private void tickStrategicShoot(BlockPos pos) {
        if (shotCooldown > 0) {
            return;
        }
        
        if (weapon == null) {
            state = CombatState.IDLE;
            return;
        }
        
        if (getJEGCurrentAmmo() <= 0) {
            Main.LOGGER.info("No ammo in magazine, transitioning to IDLE");
            state = CombatState.IDLE;
            return;
        }
        
        recruit.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        Main.LOGGER.info("FIRING STRATEGIC SHOT - ammo before: {}", getJEGCurrentAmmo());
        weapon.performRangedAttackIWeapon(recruit, pos.getX(), pos.getY(), pos.getZ(), 
            weapon.getProjectileSpeed());
        recruit.setChargingCrossbow(false);
        shotCooldown = weapon.getAttackCooldown();
        
        // NOTE: performRangedAttackIWeapon already calls setLoaded(false) which decrements ammo
        
        Main.LOGGER.info("STRATEGIC SHOT FIRED - ammo after: {}", getJEGCurrentAmmo());
        
        // Handle state transition based on remaining ammo
        int remainingAmmo = getJEGCurrentAmmo();
        int capacity = getJEGMagazineCapacity();
        
        // Only reload if magazine is completely empty and ammo present in inventory.
        if (remainingAmmo == 0 && hasJEGInventoryAmmo()) {
            Main.LOGGER.info("After strategic shot: magazine empty, transitioning to RELOAD");
            state = CombatState.RELOAD;
        } else if (remainingAmmo > 0) {
            Main.LOGGER.info("After strategic shot: ammo ({}) still available, transitioning to AIMING", remainingAmmo);
            state = CombatState.AIMING;
        } else {
            Main.LOGGER.info("After strategic shot: no ammo left ({}), transitioning to IDLE", remainingAmmo);
            state = CombatState.IDLE;
        }
    }

    /**
    * Get current ammo using normalized helper (AmmoCount preferred).
    */
    private int getJEGCurrentAmmo() {
        ItemStack mainHand = recruit.getMainHandItem();
        return JEGWeapon.readAmmoNormalized(mainHand);
    }

    /**
     * Get magazine capacity via JEG's getMagazineSize()
     */
    private int getJEGMagazineCapacity() {
        if (weapon == null) return 30;
        return weapon.getMagazineSize(recruit.getMainHandItem());
    }

    /**
     * Get reload time from JEG's actual weapon configuration
     */
    private int getJEGReloadTime() {
        if (weapon == null) return 60;
        return weapon.getWeaponLoadTime(recruit.getMainHandItem());
    }

    /**
     * Check if inventory has valid ammo for current weapon.
     * Respects RangedRecruitsNeedArrowsToShoot config.
     */
    private boolean hasJEGInventoryAmmo() {
        if (!RecruitsServerConfig.RangedRecruitsNeedArrowsToShoot.get() || recruitHasInfiniteAmmo()) {
            return true;
        }
        net.minecraft.world.Container inventory = recruit.getInventory();
        if (inventory == null) {
            Main.LOGGER.warn("Could not get inventory to check for ammo");
            return false;
        }
        ItemStack mainHand = recruit.getMainHandItem();
        String projectileItemId = JEGWeapon.getProjectileItemId(mainHand);

        boolean fallbackAttempted = false;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            boolean matches = false;
            if (projectileItemId != null) {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (key != null && projectileItemId.equals(key.toString())) matches = true;
            } else {
                fallbackAttempted = true;
                // String descId = stack.getDescriptionId().toLowerCase();
                // matches = isValidAmmo(stack);
            }

            if (matches && projectileItemId != null) {
                Main.LOGGER.info("[RELOAD_STRICT] Found STRICT ammo match for current gun: {}", stack.getDescriptionId());
                return true;
            }
        }
        if (projectileItemId == null && fallbackAttempted) {
            Main.LOGGER.warn("[RELOAD_STRICT] hasJEGInventoryAmmo(): Fallback name-based ammo check denied for gun '{}'. Correct ammo item type could not be determined!",
                mainHand.getDisplayName().getString());
        }
        Main.LOGGER.warn("[RELOAD_STRICT] No strict ammo match found in recruit inventory for gun type: {}", JEGWeapon.detectGunType(mainHand));
        return false;
    }

    /**
     * Check if item is valid ammo for the current weapon's gun type.
     * Supports both JEG (jeg:) and MTEG (mteg:) registry prefixes.
     */
    private boolean isValidAmmo(ItemStack stack) {
        if (stack.isEmpty()) return false;

        String gunType = JEGWeapon.detectGunType(recruit.getMainHandItem());
        String descId = stack.getDescriptionId();

        boolean isValid = switch (gunType) {
            case "RIFLE" -> descId.equals("item.jeg.rifle_ammo") || descId.equals("item.mteg.rifle_ammo");
            case "PISTOL" -> descId.equals("item.jeg.pistol_ammo") || descId.equals("item.mteg.pistol_ammo");
            case "SMG" -> descId.equals("item.jeg.pistol_ammo") || descId.equals("item.mteg.pistol_ammo");
            case "SHOTGUN" -> descId.equals("item.jeg.shotgun_shell") || descId.equals("item.jeg.handmade_shell")
                || descId.equals("item.mteg.shotgun_shell") || descId.equals("item.mteg.handmade_shell");
            default -> descId.contains("ammo") || descId.contains("shell") || descId.contains("cartridge");
        };

        if (!isValid && !descId.isEmpty()) {
            Main.LOGGER.debug("isValidAmmo: '{}' does not match gun type '{}'", descId, gunType);
        }
        return isValid;
    }

    // Return true if this recruit should be treated as having infinite ammo.
    // Infinite if:
    //  - explicit persistent NBT flag "recruits:infinite_ammo" is true OR
    //  - the recruit is not owned by a player (owner == null || owner is not a Player)
    private boolean recruitHasInfiniteAmmo() {
    try {
        boolean nbtFlag = recruit.getPersistentData().getBoolean("recruits:infinite_ammo");
        LivingEntity owner = recruit.getOwner();
        String ownerInfo = (owner == null) ? "null" : owner.getClass().getSimpleName() + "/" + owner.getName().getString();
        Main.LOGGER.info("[RELOAD_DEBUG] recruitHasInfiniteAmmo: recruit={}, NBT={}, owner={}",
            recruit.getUUID(), nbtFlag, ownerInfo);
        if (nbtFlag) return true;
        return owner == null || !(owner instanceof net.minecraft.world.entity.player.Player);
    } catch (Exception e) {
        Main.LOGGER.warn("[RELOAD_DEBUG] recruitHasInfiniteAmmo exception: {}", e.getMessage());
        return false;
    }
}

    private boolean ensureJEGWeapon() {
        ItemStack mainHand = recruit.getMainHandItem();
        
        // Already holding a JEG weapon
        if (IWeapon.isJEGWeapon(mainHand)) {
            if (weapon == null) {
                weapon = createJEGWeapon(mainHand);
            }
            return weapon != null;
        }
        
        // Not holding JEG weapon - try to switch to one
        recruit.switchMainHandItem(RecruitRangedGunnerAttackGoal::isJEGWeapon);
        
        // Check if switch was successful
        mainHand = recruit.getMainHandItem();
        if (IWeapon.isJEGWeapon(mainHand)) {
            if (weapon == null) {
                weapon = createJEGWeapon(mainHand);
            }
            return weapon != null;
        }
        
        // Switch failed or no JEG weapon available
        weapon = null;
        return false;
    }

    private JEGWeapon createJEGWeapon(ItemStack itemStack) {
        String gunType = JEGWeapon.detectGunType(itemStack);
        
        return switch (gunType) {
            case "RIFLE" -> new JEGWeapon("RIFLE", 12.0F, 3.5F, 0.05F, 0.4D, 30);
            case "PISTOL" -> new JEGWeapon("PISTOL", 8.0F, 2.5F, 0.2F, 0.8D, 15);
            case "SHOTGUN" -> new JEGWeapon("SHOTGUN", 15.0F, 2.0F, 0.3F, 0.3D, 40);
            case "SMG" -> new JEGWeapon("SMG", 6.0F, 3.0F, 0.15F, 1.0D, 5);
            default -> new JEGWeapon("GUN", 10.0F, 3.0F, 0.1F, 0.5D, 20);
        };
    }

    private boolean shouldStandStill(LivingEntity target) {
        if (target == null || !isHoldingRifle()) return false;
        
        double distance = target.distanceTo(recruit);
        boolean inGoodRange = distance >= 10.0D && distance <= 40.0D;
        boolean hasLineOfSight = recruit.getSensing().hasLineOfSight(target);
        boolean inCombatStance = state == CombatState.AIMING || state == CombatState.SHOOT;
        
        return inGoodRange && hasLineOfSight && inCombatStance;
    }

    private boolean canSeeTarget() {
        return target != null 
            && target.isAlive() 
            && recruit.getSensing().hasLineOfSight(target);
    }

    private boolean isHoldingRifle() {
        return weapon != null && JEGWeapon.detectGunType(recruit.getMainHandItem()).equals("RIFLE");
    }

    private void playReloadSound() {
        if (weapon != null) {
            recruit.playSound(weapon.getLoadSound(), 1.0F, 
                1.0F / (recruit.getRandom().nextFloat() * 0.4F + 0.8F));
        }
    }

    private double getIdealMinRange() {
        if (weapon == null) return 8.0D;
        return switch (JEGWeapon.detectGunType(recruit.getMainHandItem())) {
            case "PISTOL" -> 5.0D;
            case "SMG" -> 6.0D;
            case "SHOTGUN" -> 3.0D;
            case "RIFLE" -> 12.0D;
            default -> 8.0D;
        };
    }

    private double getIdealMaxRange() {
        if (weapon == null) return 25.0D;
        return switch (JEGWeapon.detectGunType(recruit.getMainHandItem())) {
            case "PISTOL" -> 15.0D;
            case "SMG" -> 18.0D;
            case "SHOTGUN" -> 10.0D;
            case "RIFLE" -> 35.0D;
            default -> 25.0D;
        };
    }

    private int getAimingTime() {
        if (target == null) return 10;
        double distance = target.distanceTo(recruit);
        
        return switch ((int) distance / 10) {
            case 0 -> 5;
            case 1 -> 10;
            case 2, 3 -> 15;
            default -> 20;
        };
    }

    private boolean canAttackMovePos() {
        LivingEntity currentTarget = recruit.getTarget();
        BlockPos pos = recruit.getMovePos();
        
        if (currentTarget == null || pos == null || !recruit.getShouldMovePos()) {
            return true;
        }
        
        boolean targetIsFar = currentTarget.distanceTo(recruit) >= 21.5D;
        boolean posIsClose = pos.distSqr(recruit.getOnPos()) <= 15.0D;
        boolean posIsFar = pos.distSqr(recruit.getOnPos()) > 15.0D;
        
        return !posIsFar && !(posIsClose && targetIsFar);
    }

    public static boolean isJEGWeapon(ItemStack itemStack) {
        return IWeapon.isJEGWeapon(itemStack);
    }

    enum CombatState {
        IDLE, RELOAD, AIMING, SHOOT
    }

    /**
     * Encapsulates all movement logic
     */
    private class CombatController {
        private final CrossBowmanEntity recruit;

        CombatController(CrossBowmanEntity recruit) {
            this.recruit = recruit;
        }

        void advance(LivingEntity target) {
            double speed = weapon != null ? weapon.getMoveSpeedAmp() : 0.6D;
            recruit.getNavigation().moveTo(target, speed);
        }

        void retreat(LivingEntity target, double distance) {
            Vec3 awayDir = recruit.position().subtract(target.position()).normalize();
            Vec3 retreatPos = recruit.position().add(awayDir.scale(Math.min(distance + 3.0D, 10.0D)));
            double speed = weapon != null ? weapon.getMoveSpeedAmp() : 0.6D;
            recruit.getNavigation().moveTo(retreatPos.x, retreatPos.y, retreatPos.z, speed);
        }

        void followWithTarget(@NotNull LivingEntity owner, @NotNull LivingEntity target) {
            double distToOwnerSqr = owner.distanceToSqr(recruit);
            double distToTarget = target.distanceTo(recruit);
            double speed = weapon != null ? weapon.getMoveSpeedAmp() : 0.6D;
            double ownerLeashSqr = 625.0D;

            if (distToOwnerSqr > ownerLeashSqr) {
                recruit.getNavigation().moveTo(owner, speed);
            } else if (distToTarget < getIdealMinRange()) {
                retreat(target, getIdealMinRange() - distToTarget);
            } else if (distToTarget > getIdealMaxRange()) {
                advance(target);
            } else {
                recruit.getNavigation().stop();
            }
        }

        void follow(@NotNull LivingEntity owner) {
            double distToOwnerSqr = owner.distanceToSqr(recruit);
            double speed = weapon != null ? weapon.getMoveSpeedAmp() : 0.6D;
            
            if (distToOwnerSqr > 400.0D) {
                recruit.getNavigation().moveTo(owner, speed);
            } else if (distToOwnerSqr <= 100.0D) {
                recruit.getNavigation().stop();
            }
        }

        void holdPosition(@NotNull Vec3 pos) {
            double distToPosSqr = pos.distanceToSqr(recruit.position());
            double speed = weapon != null ? weapon.getMoveSpeedAmp() : 0.6D;
            
            if (distToPosSqr > 50.0D) {
                recruit.getNavigation().moveTo(pos.x, pos.y, pos.z, speed);
            } else if (distToPosSqr <= 4.0D) {
                recruit.getNavigation().stop();
            }
        }
    }
}