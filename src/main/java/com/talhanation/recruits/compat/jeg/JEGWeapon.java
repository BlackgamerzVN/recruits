package com.talhanation.recruits.compat.jeg;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.compat.musketmod.IWeapon;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class JEGWeapon implements IWeapon {
    private final String gunItemName;
    private final float damage;
    private final float projectileSpeed;
    private final float accuracy;
    private final double moveSpeedAmp;
    private final int attackCooldown;

    // Package paths for JEG and MTEG - tried in order
    private static final String[][] CLASS_PATHS = {
        {"com.migami.justenoughguns.common.item.GunItem",    "com.migami.mteg.common.item.GunItem"},
        {"com.migami.justenoughguns.common.entity.BulletEntity", "com.migami.mteg.common.entity.BulletEntity"},
        {"com.migami.justenoughguns.common.init.ModEntities", "com.migami.mteg.common.init.ModEntities"},
        {"com.migami.justenoughguns.common.init.ModSounds",   "com.migami.mteg.common.init.ModSounds"},
        {"com.migami.justenoughguns.common.init.ModItems",    "com.migami.mteg.common.init.ModItems"},
    };
    private static final int IDX_GUN_ITEM = 0;
    private static final int IDX_BULLET = 1;
    private static final int IDX_ENTITIES = 2;
    private static final int IDX_SOUNDS = 3;
    private static final int IDX_ITEMS = 4;

    /** Tries to find a class from JEG first, then MTEG. */
    private static Class<?> findClass(int index) {
        for (String className : CLASS_PATHS[index]) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    public JEGWeapon(String gunItemName, float damage, float projectileSpeed,
                     float accuracy, double moveSpeedAmp, int attackCooldown) {
        this.gunItemName = gunItemName;
        this.damage = damage;
        this.projectileSpeed = projectileSpeed;
        this.accuracy = accuracy;
        this.moveSpeedAmp = moveSpeedAmp;
        this.attackCooldown = attackCooldown;
    }

    @Override
    @Nullable
    public Item getWeapon() {
        try {
            Class<?> itemClass = findClass(IDX_ITEMS);
            if (itemClass == null) return null;
            Field gunItemField = itemClass.getField(gunItemName);
            Object item = gunItemField.get(null);
            return (Item) item;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Main.LOGGER.error("JEG/MTEG gun item " + gunItemName + " was not found: " + e.getMessage());
            return null;
        }
    }

    @Override
    public double getMoveSpeedAmp() {
        return moveSpeedAmp;
    }

    @Override
    public int getAttackCooldown() {
        return attackCooldown;
    }

    @Override
    public int getWeaponLoadTime() {
        // This will be called with ItemStack context via overload
        return getWeaponLoadTime(ItemStack.EMPTY);
    }

    /**
     * Get reload time from JEG's actual weapon configuration.
     * Retrieves via reflection from the GunItem instance.
     */
    public int getWeaponLoadTime(ItemStack gunStack) {
        if (gunStack.isEmpty()) {
            Main.LOGGER.warn("Cannot get reload time: empty ItemStack");
            return 60; // Fallback default
        }

        Class<?> gunItemClass = findClass(IDX_GUN_ITEM);
        if (gunItemClass != null && gunItemClass.isInstance(gunStack.getItem())) {
            // Try common method names for reload/load time
            for (String methodName : new String[]{"getLoadTime", "getReloadTime", "getFireRateDelay", "getWeaponLoadTime"}) {
                try {
                    Method method = gunItemClass.getMethod(methodName);
                    int reloadTime = (int) method.invoke(gunStack.getItem());
                    Main.LOGGER.info("JEG/MTEG reload time via {}(): {}", methodName, reloadTime);
                    return reloadTime;
                } catch (NoSuchMethodException ignored) {}
                catch (Exception e) {
                    Main.LOGGER.warn("Error invoking {}: {}", methodName, e.getMessage());
                }
            }

            // Try field access
            for (String fieldName : new String[]{"loadTime", "reloadTime", "fireRateDelay", "weaponLoadTime"}) {
                try {
                    Field field = gunItemClass.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    int reloadTime = (int) field.get(gunStack.getItem());
                    Main.LOGGER.info("JEG/MTEG reload time via field {}: {}", fieldName, reloadTime);
                    return reloadTime;
                } catch (NoSuchFieldException ignored) {}
                catch (Exception e) {
                    Main.LOGGER.warn("Error reading field {}: {}", fieldName, e.getMessage());
                }
            }
        }

        // Fallback: use default based on gun type
        String gunType = detectGunType(gunStack);
        int fallbackReloadTime = switch (gunType) {
            case "RIFLE" -> 80;
            case "PISTOL" -> 40;
            case "SHOTGUN" -> 100;
            case "SMG" -> 50;
            default -> 60;
        };

        Main.LOGGER.warn("Using fallback reload time for {}: {}", gunStack.getDescriptionId(), fallbackReloadTime);
        return fallbackReloadTime;
    }

    @Override
    public float getProjectileSpeed() {
        return projectileSpeed;
    }

    @Override
    public boolean isLoaded(ItemStack stack) {
        try {
            if (stack.hasTag() && stack.getTag().contains("AmmoCount")) {
                int ammo = stack.getOrCreateTag().getInt("AmmoCount");
                return ammo > 0;
            }
            return false;
        } catch (Exception e) {
            Main.LOGGER.error("Error checking JEG/MTEG ammo: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void setLoaded(ItemStack stack, boolean loaded) {
        try {
            if (loaded) {
                int magSize = getMagazineSize(stack);
                stack.getOrCreateTag().putInt("MaxAmmo", magSize);
                // Validate: Ammo should never exceed magazine size or be negative
                int currentAmmo = stack.getOrCreateTag().getInt("AmmoCount");
                if (currentAmmo > magSize) {
                    Main.LOGGER.warn("JEG/MTEG AmmoCount (" + currentAmmo + ") exceeds mag size (" + magSize + "), clamping!");
                    stack.getOrCreateTag().putInt("AmmoCount", magSize);
                } else if (currentAmmo <= 0) {
                    // consumeAmmoFromInventory should have set this - if it's still 0, something went wrong
                    Main.LOGGER.error("JEG/MTEG setLoaded(true) called but AmmoCount is " + currentAmmo + "! Refusing to mark as loaded.");
                    return;
                }
                Main.LOGGER.debug("JEG/MTEG weapon LOADED - AmmoCount: " + stack.getOrCreateTag().getInt("AmmoCount") + "/" + magSize);
            } else {
                int currentAmmo = stack.getOrCreateTag().getInt("AmmoCount");
                int newAmmo = Math.max(0, currentAmmo - 1);
                stack.getOrCreateTag().putInt("AmmoCount", newAmmo);
                Main.LOGGER.debug("JEG/MTEG weapon FIRED - AmmoCount: " + currentAmmo + " -> " + newAmmo);
            }
        } catch (Exception e) {
            Main.LOGGER.error("Error setting JEG/MTEG ammo: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Consume ammo from inventory and add to magazine.
     * Properly respects JEG's reload system and NBT structure.
     * Only consumes ammo - JEG handles all the rest via setLoaded().
     * If no ammo is found, magazine is NOT refilled.
     */
    public void consumeAmmoFromInventory(LivingEntity entity) {
        ItemStack mainHand = entity.getMainHandItem();
        int currentAmmo = mainHand.getOrCreateTag().getInt("AmmoCount");
        int magSize = getMagazineSize(mainHand);
        int needed = magSize - currentAmmo;

        if (needed <= 0) {
            Main.LOGGER.info("JEG/MTEG magazine already full: {}/{}", currentAmmo, magSize);
            return;
        }

        net.minecraft.world.Container inventory = null;
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            inventory = player.getInventory();
        } else if (entity instanceof AbstractRecruitEntity recruit) {
            inventory = recruit.getInventory();
        }

        if (inventory == null) {
            Main.LOGGER.warn("Entity has no inventory");
            return;
        }

        int consumed = 0;
        Main.LOGGER.info("JEG/MTEG consuming ammo - Current: {}, Needed: {}, Inventory Size: {}",
            currentAmmo, needed, inventory.getContainerSize());

        for (int i = 0; i < inventory.getContainerSize() && consumed < needed; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            String descId = stack.getDescriptionId();

            if (isAmmoForGun(descId)) {
                int toTake = Math.min(stack.getCount(), needed - consumed);
                stack.shrink(toTake);
                consumed += toTake;

                Main.LOGGER.info("JEG/MTEG consumed {} from '{}' (slot {})", toTake, descId, i);
            }
        }

        if (consumed > 0) {
            int newAmount = currentAmmo + consumed;
            mainHand.getOrCreateTag().putInt("AmmoCount", newAmount);
            inventory.setChanged();
            Main.LOGGER.info("JEG/MTEG reload: {}/{} + {} consumed = {}/{}",
                currentAmmo, magSize, consumed, newAmount, magSize);
        } else {
            // Do NOT refill magazine if no ammo was found!
            Main.LOGGER.warn("JEG/MTEG reload failed: no ammo found in inventory, magazine remains at {}/{}", currentAmmo, magSize);
        }
    }
    
    /**
     * Check if item is valid ammo using the internal description ID.
     * Supports both JEG (item.jeg.*) and MTEG (item.mteg.*) ammo.
     */
    private boolean isAmmoForGun(String descId) {
        // descId is expected to be stack.getDescriptionId()
        String lower = descId.toLowerCase();
        return switch (this.gunItemName) {
            case "RIFLE" -> lower.contains("item.jeg.rifle_ammo") || lower.contains("item.mteg.rifle_ammo") || lower.contains("rifle_ammo");
            case "PISTOL", "SMG" -> lower.contains("item.jeg.pistol_ammo") || lower.contains("item.mteg.pistol_ammo") || lower.contains("pistol_ammo");
            case "SHOTGUN" -> lower.contains("item.jeg.shotgun_shell") || lower.contains("item.jeg.handmade_shell")
                || lower.contains("item.mteg.shotgun_shell") || lower.contains("item.mteg.handmade_shell") || lower.contains("shotgun_shell") || lower.contains("handmade_shell");
            default -> lower.contains("ammo") || lower.contains("shell");
        };
    }

    @Override
    public AbstractHurtingProjectile getProjectile(LivingEntity shooter) {
        try {
            Level level = shooter.getCommandSenderWorld();

            Class<?> bulletClass = findClass(IDX_BULLET);
            Class<?> modEntitiesClass = findClass(IDX_ENTITIES);

            if (bulletClass == null || modEntitiesClass == null) {
                Main.LOGGER.error("Could not find BulletEntity or ModEntities class for JEG/MTEG");
                return null;
            }

            Field bulletTypeField = modEntitiesClass.getField("BULLET");
            Object entityType = bulletTypeField.get(null);

            Constructor<?> constructor = bulletClass.getConstructor(
                net.minecraft.world.entity.EntityType.class,
                Level.class
            );

            Object bulletObj = constructor.newInstance(entityType, level);
            AbstractHurtingProjectile bullet = (AbstractHurtingProjectile) bulletObj;
            bullet.setOwner(shooter);

            try {
                Field damageField = bulletClass.getDeclaredField("damage");
                damageField.setAccessible(true);
                damageField.setFloat(bulletObj, this.damage);
            } catch (NoSuchFieldException e) {
                Main.LOGGER.warn("Could not set bullet damage field");
            }

            Vec3 lookAngle = shooter.getLookAngle();
            double eyeHeight = shooter.getEyeY();

            bullet.setPos(
                shooter.getX() + lookAngle.x * 0.5D,
                eyeHeight - 0.1D,
                shooter.getZ() + lookAngle.z * 0.5D
            );

            return bullet;

        } catch (Exception e) {
            Main.LOGGER.error("Failed to create JEG/MTEG BulletEntity: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public AbstractArrow getProjectileArrow(LivingEntity shooter) {
        return null;
    }

    @Override
    @Nullable
    public AbstractHurtingProjectile shoot(LivingEntity shooter, AbstractHurtingProjectile projectile, double x, double y, double z) {
        if (!shooter.getCommandSenderWorld().isClientSide() && projectile != null) {
            try {
                double deltaX = x;
                double deltaY = y;
                double deltaZ = z;

                double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
                double flightTime = horizontalDistance > 0 ? horizontalDistance / this.projectileSpeed : 0;

                double adjustedY = deltaY;

                if (horizontalDistance > 0 && this.projectileSpeed > 0) {
                    float gravity = 1280f;
                    double gravityCompensation = gravity * gravity * flightTime * flightTime;

                    if (deltaY < 0) {
                        gravityCompensation *= 2.15;
                    }

                    adjustedY = deltaY + gravityCompensation;
                }

                projectile.shoot(deltaX, adjustedY, deltaZ, this.projectileSpeed, this.accuracy/100);

                return projectile;

            } catch (Exception e) {
                Main.LOGGER.error("Error shooting JEG/MTEG bullet: " + e.getMessage());
            }
        }
        return projectile;
    }

    @Override
    public AbstractArrow shootArrow(LivingEntity shooter, AbstractArrow projectile, double x, double y, double z) {
        return null;
    }

    @Override
    public SoundEvent getShootSound() {
        Class<?> soundClass = findClass(IDX_SOUNDS);
        if (soundClass != null) {
            for (String fieldName : new String[]{"GUN_FIRE", "SHOOT", "FIRE", "GUN_SHOT"}) {
                try {
                    Field soundField = soundClass.getField(fieldName);
                    Object soundEvent = soundField.get(null);
                    if (soundEvent instanceof SoundEvent) {
                        return (SoundEvent) soundEvent;
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }
        }
        Main.LOGGER.warn("JEG/MTEG gun sound not found, using default");
        return SoundEvents.GENERIC_EXPLODE;
    }

    @Override
    public SoundEvent getLoadSound() {
        Class<?> soundClass = findClass(IDX_SOUNDS);
        if (soundClass != null) {
            for (String fieldName : new String[]{"GUN_RELOAD", "RELOAD", "RELOADING"}) {
                try {
                    Field soundField = soundClass.getField(fieldName);
                    Object soundEvent = soundField.get(null);
                    if (soundEvent instanceof SoundEvent) {
                        return (SoundEvent) soundEvent;
                    }
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }
        }
        Main.LOGGER.warn("JEG/MTEG reload sound not found, using default");
        return SoundEvents.ITEM_PICKUP;
    }

    @Override
    public boolean isGun() {
        return true;
    }

    @Override
    public boolean canMelee() {
        return false;
    }

    @Override
    public boolean isBow() {
        return false;
    }

    @Override
    public boolean isCrossBow() {
        return false;
    }

    public int getMagazineSize(ItemStack stack) {
        Class<?> gunItemClass = findClass(IDX_GUN_ITEM);
        if (gunItemClass != null && gunItemClass.isInstance(stack.getItem())) {
            try {
                Method getMagazineSizeMethod = gunItemClass.getMethod("getMagazineSize");
                int size = (int) getMagazineSizeMethod.invoke(stack.getItem());
                Main.LOGGER.info("JEG/MTEG getMagazineSize() via reflection: " + size);
                return size;
            } catch (Exception e) {
                Main.LOGGER.error("getMagazineSize() failed: " + e.getMessage());
            }
        }

        String id = stack.getDescriptionId().toLowerCase();
        int fallbackSize;

        if (id.contains("pistol")) fallbackSize = 15;
        else if (id.contains("rifle")) fallbackSize = 30;
        else if (id.contains("shotgun")) fallbackSize = 8;
        else if (id.contains("smg")) fallbackSize = 40;
        else fallbackSize = 30;

        Main.LOGGER.warn("Using fallback magazine size for " + id + ": " + fallbackSize);
        return fallbackSize;
    }

    public static String detectGunType(ItemStack gunStack) {
        Class<?> gunItemClass = findClass(IDX_GUN_ITEM);
        if (gunItemClass != null && gunItemClass.isInstance(gunStack.getItem())) {
            for (String methodName : new String[]{"getAmmoType", "getAmmo", "getAmmoPredicate"}) {
                try {
                    Method ammoMethod = gunItemClass.getMethod(methodName);
                    Object result = ammoMethod.invoke(gunStack.getItem());

                    String ammoId = null;
                    if (result instanceof Item ammoItem) {
                        ammoId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(ammoItem).toString();
                    } else if (result instanceof ItemStack ammoStack) {
                        ammoId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(ammoStack.getItem()).toString();
                    } else if (result != null) {
                        ammoId = result.toString();
                    }

                    if (ammoId != null) {
                        Main.LOGGER.info("JEG/MTEG ammo type detected via " + methodName + "(): " + ammoId);
                        String type = classifyByAmmo(ammoId);
                        if (type.equals("PISTOL_AMMO")) {
                            type = distinguishByMagSize(gunStack, gunItemClass);
                        }
                        return type;
                    }
                } catch (NoSuchMethodException ignored) {}
                catch (Exception e) {
                    Main.LOGGER.warn("Error invoking " + methodName + ": " + e.getMessage());
                }
            }

            for (String fieldName : new String[]{"ammoType", "ammo", "AMMO_TYPE"}) {
                try {
                    Field ammoField = gunItemClass.getDeclaredField(fieldName);
                    ammoField.setAccessible(true);
                    Object result = ammoField.get(gunStack.getItem());

                    String ammoId = null;
                    if (result instanceof Item ammoItem) {
                        ammoId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(ammoItem).toString();
                    } else if (result instanceof ItemStack ammoStack) {
                        ammoId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(ammoStack.getItem()).toString();
                    } else if (result != null) {
                        ammoId = result.toString();
                    }

                    if (ammoId != null) {
                        Main.LOGGER.info("JEG/MTEG ammo type detected via field " + fieldName + ": " + ammoId);
                        String type = classifyByAmmo(ammoId);
                        if (type.equals("PISTOL_AMMO")) {
                            type = distinguishByMagSize(gunStack, gunItemClass);
                        }
                        return type;
                    }
                } catch (NoSuchFieldException ignored) {}
                catch (Exception e) {
                    Main.LOGGER.warn("Error reading field " + fieldName + ": " + e.getMessage());
                }
            }
        }

        String id = gunStack.getDescriptionId();
        Main.LOGGER.info("JEG/MTEG falling back to name-based detection for: " + id);
        if (id.contains("rifle")) return "RIFLE";
        if (id.contains("pistol")) return "PISTOL";
        if (id.contains("shotgun")) return "SHOTGUN";
        if (id.contains("smg") || id.contains("submachine")) return "SMG";
        return "GUN";
    }

    private static String classifyByAmmo(String ammoId) {
        String lower = ammoId.toLowerCase();
        if (lower.contains("rifle") || lower.contains("762") || lower.contains("556")) return "RIFLE";
        if (lower.contains("shell") || lower.contains("shotgun") || lower.contains("buckshot")) return "SHOTGUN";
        if (lower.contains("smg") || lower.contains("submachine")) return "SMG";
        if (lower.contains("pistol") || lower.contains("9mm") || lower.contains("45acp")) return "PISTOL_AMMO";
        return "GUN";
    }

    private static String distinguishByMagSize(ItemStack gunStack, Class<?> gunItemClass) {
        int magSize = 0;
        try {
            Method getMagSize = gunItemClass.getMethod("getMagazineSize");
            magSize = (int) getMagSize.invoke(gunStack.getItem());
            Main.LOGGER.info("JEG/MTEG mag size for pistol/SMG distinction: " + magSize);
        } catch (Exception e) {
            String id = gunStack.getDescriptionId().toLowerCase();
            if (id.contains("smg") || id.contains("submachine")) return "SMG";
            return "PISTOL";
        }

        if (magSize >= 15) {
            Main.LOGGER.info("Classified as SMG (mag size " + magSize + " >= 15)");
            return "SMG";
        } else {
            Main.LOGGER.info("Classified as PISTOL (mag size " + magSize + " < 15)");
            return "PISTOL";
        }
    }

    @Override
    public void performRangedAttackIWeapon(AbstractRecruitEntity shooter, double x, double y, double z, float projectileSpeed) {
        AbstractHurtingProjectile projectileEntity = this.getProjectile(shooter);
        if (projectileEntity == null) {
            Main.LOGGER.error("Failed to create JEG/MTEG projectile");
            return;
        }

        double d0 = x - shooter.getX();
        double d1 = y - shooter.getEyeY();
        double d2 = z - shooter.getZ();

        AbstractHurtingProjectile shotProjectile = this.shoot(shooter, projectileEntity, d0, d1, d2);

        if (shotProjectile != null) {
            SoundEvent shootSound = this.getShootSound();
            if (shootSound != null) shooter.playSound(shootSound, 1.0F, 1.0F / (shooter.getRandom().nextFloat() * 0.4F + 0.8F));
            shooter.getCommandSenderWorld().addFreshEntity(shotProjectile);
            ItemStack weapon = shooter.getMainHandItem();
            this.setLoaded(weapon, false); // decrements AmmoCount
            shooter.damageMainHandItem();
            Main.LOGGER.debug("JEG/MTEG weapon fired at target: {},{},{}", x, y, z);
        } else {
            Main.LOGGER.error("JEG/MTEG projectile shoot failed");
        }
    }

    private Vec3 predictTargetPosition(LivingEntity target, double flightTime) {
        Vec3 currentPos = target.position();
        Vec3 velocity = target.getDeltaMovement();
        return currentPos.add(velocity.scale(flightTime * 20));
    }
}