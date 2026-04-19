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

import java.util.EnumSet;

/**
 * AI Goal for recruits using JEG (JustEnoughGuns) weapons.
 * Respects JEG's native reload tracking system using NBT tags (Ammo, MaxAmmo).
 * Implements premature reload when magazine isn't full and ammo is available.
 */
public class RecruitRangedGunnerAttackGoal extends Goal {
    private final CrossBowmanEntity recruit;
    private final double stopRange;
    private final CombatController combatController;
    
    // Reload threshold: reload when magazine falls below this percentage of capacity
    private static final float PREMATURE_RELOAD_THRESHOLD = 0.5F; // 50% of magazine
    
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
        
        // Check for premature reload first (when idle and magazine not full)
        if (shouldPrematurelyReload()) {
            Main.LOGGER.info("PREMATURE RELOAD TRIGGERED (IDLE) - current: {}, threshold: {}", 
                getJEGCurrentAmmo(), (int)(getJEGMagazineCapacity() * PREMATURE_RELOAD_THRESHOLD));
            state = CombatState.RELOAD;
            return;
        }
        
        // Magazine not full AND inventory has ammo - always reload first
        if (currentAmmo < capacity && hasJEGInventoryAmmo()) {
            state = CombatState.RELOAD;
        } else if (currentAmmo > 0) {
            state = hasTarget && target != null && target.isAlive() ? CombatState.AIMING : CombatState.IDLE;
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
            if (!RecruitsServerConfig.RangedRecruitsNeedArrowsToShoot.get()) {
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
        
        if (remainingAmmo < capacity && hasJEGInventoryAmmo()) {
            Main.LOGGER.info("After shot: ammo ({}) < capacity ({}), transitioning to RELOAD", remainingAmmo, capacity);
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
        
        if (remainingAmmo < capacity && hasJEGInventoryAmmo()) {
            Main.LOGGER.info("After strategic shot: ammo ({}) < capacity ({}), transitioning to RELOAD", remainingAmmo, capacity);
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
     * Perform reload: consume ammo from inventory and add to magazine.
     */
    private void performJEGReload() {
        ItemStack mainHand = recruit.getMainHandItem();
        int currentAmmo = getJEGCurrentAmmo();
        int capacity = getJEGMagazineCapacity();
        int needed = capacity - currentAmmo;
        
        Main.LOGGER.info("=== RELOAD START === Current: {}, Capacity: {}, Needed: {}", 
            currentAmmo, capacity, needed);
        
        if (needed <= 0) {
            Main.LOGGER.warn("Magazine already full or no capacity increase needed");
            return;
        }
        
        int consumed = 0;
        
        Main.LOGGER.info("Searching inventory slots 6 to {}", recruit.getInventory().items.size());
        
        // Start from slot 6 to skip armor/equipment slots
        for (int i = 6; i < recruit.getInventory().items.size() && consumed < needed; i++) {
            ItemStack stack = recruit.getInventory().items.get(i);
            
            Main.LOGGER.info("Slot {}: {} (count: {})", i, 
                stack.isEmpty() ? "EMPTY" : stack.getDescriptionId(), 
                stack.getCount());
            
            if (stack.isEmpty()) continue;
            
            if (isValidAmmo(stack)) {
                int toConsume = Math.min(stack.getCount(), needed - consumed);
                stack.shrink(toConsume);
                consumed += toConsume;
                
                Main.LOGGER.info("[OK] CONSUMED {} ammo from slot {}, {} remaining in stack", 
                    toConsume, i, stack.getCount());
            } else {
                Main.LOGGER.debug("[SKIP] Not ammo for current gun: {}", stack.getDescriptionId());
            }
        }
        
        Main.LOGGER.info("Total consumed from inventory: {}", consumed);
        
        if (consumed > 0) {
            int newAmount = currentAmmo + consumed;
            JEGWeapon.writeAmmoNormalized(mainHand, newAmount);
            mainHand.getOrCreateTag().putInt("MaxAmmo", capacity);
            Main.LOGGER.info("[SUCCESS] Reload SUCCESSFUL: consumed {} ammo, magazine now: {}/{}",
                consumed, newAmount, capacity);
        } else {
            Main.LOGGER.warn("[FAILED] Reload FAILED: no valid ammo found in inventory");
            Main.LOGGER.warn("Checking ALL slots for debugging:");
            for (int i = 0; i < recruit.getInventory().items.size(); i++) {
                ItemStack stack = recruit.getInventory().items.get(i);
                if (!stack.isEmpty()) {
                    Main.LOGGER.warn("  Slot {}: {} (count: {})", i, stack.getDescriptionId(), stack.getCount());
                }
            }
        }
    }

    /**
     * Determine if NPC should reload prematurely.
     * Returns true if:
     * - Magazine is below threshold percentage OR
     * - Magazine is empty
     * AND ammo is available in inventory
     */
    private boolean shouldPrematurelyReload() {
        int currentAmmo = getJEGCurrentAmmo();
        int capacity = getJEGMagazineCapacity();
        
        // Already has full magazine
        if (currentAmmo >= capacity) {
            return false;
        }
        
        // Inventory has no ammo to reload
        if (!hasJEGInventoryAmmo()) {
            return false;
        }
        
        // Calculate threshold: reload when magazine falls below this amount
        int thresholdAmmo = Math.max(1, (int) (capacity * PREMATURE_RELOAD_THRESHOLD));
        
        // Reload if empty OR below threshold
        return currentAmmo == 0 || currentAmmo <= thresholdAmmo;
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
        // If config says ammo not needed, always return true (infinite ammo)
        if (!RecruitsServerConfig.RangedRecruitsNeedArrowsToShoot.get()) {
            return true;
        }
        
        net.minecraft.world.Container inventory = recruit.getInventory();
        
        if (inventory == null) {
            Main.LOGGER.warn("Could not get inventory to check for ammo");
            return false;
        }
        
        // Scan inventory using gun-type-specific validation
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            
            if (isValidAmmo(stack)) {
                Main.LOGGER.info("Found valid ammo for current gun: {}", 
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()));
                return true;
            }
        }
        
        Main.LOGGER.warn("No valid JEG ammo found in inventory for gun type: {}", 
            JEGWeapon.detectGunType(recruit.getMainHandItem()));
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