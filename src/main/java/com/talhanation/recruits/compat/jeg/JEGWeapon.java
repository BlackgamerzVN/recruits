// File: src/main/java/com/talhanation/recruits/compat/jeg/JEGWeapon.java
package com.talhanation.recruits.compat.jeg;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.compat.musketmod.IWeapon;
import com.talhanation.recruits.config.RecruitsServerConfig;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * JEG compatibility layer for Recruits.
 *
 * - Prefer ttv.migami.jeg.* packages
 * - Fallback to older package names (com.migami.justenoughguns.*, com.migami.mteg.*)
 * - Read runtime Gun/Gun.Projectile/reloads to determine magazine and ammo item.
 * - Normalize AmmoCount/Ammo NBT keys
 */
public class JEGWeapon implements IWeapon {
    private final String gunItemName;
    private final float damage;
    private final float projectileSpeed;
    private final float accuracy;
    private final double moveSpeedAmp;
    private final int attackCooldown;

    // Prefer ttv.migami.jeg.* then older packages as fallback
    private static final String[][] CLASS_PATHS = {
        {"ttv.migami.jeg.item.GunItem", "com.migami.justenoughguns.common.item.GunItem", "com.migami.mteg.common.item.GunItem"},
        {"ttv.migami.jeg.entity.projectile.ProjectileEntity", "com.migami.justenoughguns.common.entity.BulletEntity", "com.migami.mteg.common.entity.BulletEntity"},
        {"ttv.migami.jeg.init.ModEntities", "com.migami.justenoughguns.common.init.ModEntities", "com.migami.mteg.common.init.ModEntities"},
        {"ttv.migami.jeg.init.ModSounds", "com.migami.justenoughguns.common.init.ModSounds", "com.migami.mteg.common.init.ModSounds"},
        {"ttv.migami.jeg.init.ModItems", "com.migami.justenoughguns.common.init.ModItems", "com.migami.mteg.common.init.ModItems"},
    };
    private static final int IDX_GUN_ITEM = 0;
    private static final int IDX_BULLET = 1;
    private static final int IDX_ENTITIES = 2;
    private static final int IDX_SOUNDS = 3;
    private static final int IDX_ITEMS = 4;

    /** Tries to find a class from JEG first, then older forks. */
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
     * Attempt to read magazine size from the GunItem -> Gun -> Reloads runtime objects.
     * Fallback to older heuristics on failure.
     */
    public int getMagazineSize(ItemStack stack) {
        // Try reflective JEG API: GunItem.getGun() -> Gun.getReloads() -> Reloads.getMaxAmmo() or field "maxAmmo"
        try {
            Class<?> gunItemClass = findClass(IDX_GUN_ITEM);
            if (gunItemClass != null && gunItemClass.isInstance(stack.getItem())) {
                try {
                    Method getGunMethod = gunItemClass.getMethod("getGun");
                    Object gunObj = getGunMethod.invoke(stack.getItem());
                    if (gunObj != null) {
                        // getReloads()
                        try {
                            Method getReloads = gunObj.getClass().getMethod("getReloads");
                            Object reloads = getReloads.invoke(gunObj);
                            if (reloads != null) {
                                try {
                                    Method getMaxAmmo = reloads.getClass().getMethod("getMaxAmmo");
                                    Object val = getMaxAmmo.invoke(reloads);
                                    if (val instanceof Number) {
                                        int size = ((Number) val).intValue();
                                        Main.LOGGER.info("Detected magazine size via getGun()->getReloads()->getMaxAmmo(): {}", size);
                                        return size;
                                    }
                                } catch (NoSuchMethodException e) {
                                    // fallback to field
                                    try {
                                        Field maxAmmoF = reloads.getClass().getDeclaredField("maxAmmo");
                                        maxAmmoF.setAccessible(true);
                                        Object val = maxAmmoF.get(reloads);
                                        if (val instanceof Number) {
                                            int size = ((Number) val).intValue();
                                            Main.LOGGER.info("Detected magazine size via reloads.maxAmmo: {}", size);
                                            return size;
                                        }
                                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                                }
                            }
                        } catch (NoSuchMethodException nsme) {
                            // if no getReloads method, try to access reloads field directly
                            try {
                                Field reloadsField = gunObj.getClass().getDeclaredField("reloads");
                                reloadsField.setAccessible(true);
                                Object reloads = reloadsField.get(gunObj);
                                if (reloads != null) {
                                    try {
                                        Method getMaxAmmo = reloads.getClass().getMethod("getMaxAmmo");
                                        Object val = getMaxAmmo.invoke(reloads);
                                        if (val instanceof Number) {
                                            int size = ((Number) val).intValue();
                                            Main.LOGGER.info("Detected magazine size via gun.reloads.getMaxAmmo(): {}", size);
                                            return size;
                                        }
                                    } catch (NoSuchMethodException e) {
                                        try {
                                            Field maxAmmoF = reloads.getClass().getDeclaredField("maxAmmo");
                                            maxAmmoF.setAccessible(true);
                                            Object val = maxAmmoF.get(reloads);
                                            if (val instanceof Number) {
                                                int size = ((Number) val).intValue();
                                                Main.LOGGER.info("Detected magazine size via gun.reloads.maxAmmo: {}", size);
                                                return size;
                                            }
                                        } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                                    }
                                }
                            } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                        }
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception e) {
            Main.LOGGER.warn("Reflective magazine size read failed: {}", e.getMessage());
        }

        // Fallback: use default based on gun type or name heuristics
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

    /**
     * Get reload time from JEG's actual weapon configuration (reflective).
     * Tries common method and field names, falls back to default.
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

            // Try field access on gunItem instance
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

        // Fallback
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

    /** Read ammo (normalized) from an ItemStack. Prefer AmmoCount then Ammo. */
    public static int readAmmoNormalized(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return 0;
            CompoundTag tag = stack.getTag();
            if (tag == null) return 0;
            if (tag.contains("AmmoCount")) return tag.getInt("AmmoCount");
            if (tag.contains("Ammo")) return tag.getInt("Ammo");
            if (tag.contains("ammo")) return tag.getInt("ammo");
            return 0;
        } catch (Exception e) {
            Main.LOGGER.warn("Error reading ammo NBT: {}", e.getMessage());
            return 0;
        }
    }

    /** Write ammo to the stack and normalize keys (write AmmoCount and Ammo). */
    public static void writeAmmoNormalized(ItemStack stack, int ammo) {
        if (stack == null || stack.isEmpty()) return;
        try {
            CompoundTag tag = stack.getOrCreateTag();
            tag.putInt("AmmoCount", ammo);
            tag.putInt("Ammo", ammo);
            // Keep MaxAmmo in sync if present or known elsewhere
        } catch (Exception e) {
            Main.LOGGER.warn("Error writing ammo NBT: {}", e.getMessage());
        }
    }

    @Override
    public boolean isLoaded(ItemStack stack) {
        try {
            int ammo = readAmmoNormalized(stack);
            return ammo > 0;
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
                // ensure MaxAmmo tag
                stack.getOrCreateTag().putInt("MaxAmmo", magSize);
                int currentAmmo = readAmmoNormalized(stack);
                if (currentAmmo > magSize) {
                    Main.LOGGER.warn("JEG/MTEG AmmoCount (" + currentAmmo + ") exceeds mag size (" + magSize + "), clamping!");
                    writeAmmoNormalized(stack, magSize);
                } else if (currentAmmo <= 0) {
                    Main.LOGGER.error("JEG/MTEG setLoaded(true) called but AmmoCount is " + currentAmmo + "! Refusing to mark as loaded.");
                    return;
                }
                Main.LOGGER.debug("JEG/MTEG weapon LOADED - AmmoCount: " + readAmmoNormalized(stack) + "/" + magSize);
            } else {
                int currentAmmo = readAmmoNormalized(stack);
                int newAmmo = Math.max(0, currentAmmo - 1);
                writeAmmoNormalized(stack, newAmmo);
                Main.LOGGER.debug("JEG/MTEG weapon FIRED - AmmoCount: " + currentAmmo + " -> " + newAmmo);
            }
        } catch (Exception e) {
            Main.LOGGER.error("Error setting JEG/MTEG ammo: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Consume ammo from inventory and add to magazine.
     * Tries to detect the projectile ammo item via GunItem.getGun()->projectile.item, otherwise falls back to description heuristics.
     */
    public void consumeAmmoFromInventory(LivingEntity entity) {
        ItemStack mainHand = entity.getMainHandItem();
        int currentAmmo = readAmmoNormalized(mainHand);
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

        // Try to detect authoritative ammo item id from GunItem -> Gun -> Projectile.item
        String projectileItemId = null;
        try {
            // Use reflection to extract item id
            Class<?> gunItemClass = findClass(IDX_GUN_ITEM);
            if (gunItemClass != null && gunItemClass.isInstance(mainHand.getItem())) {
                Method getGun = gunItemClass.getMethod("getGun");
                Object gunObj = getGun.invoke(mainHand.getItem());
                if (gunObj != null) {
                    Method getProjectile = gunObj.getClass().getMethod("getProjectile");
                    Object proj = getProjectile.invoke(gunObj);
                    if (proj != null) {
                        // Try getItem() or item field
                        try {
                            Method getItem = proj.getClass().getMethod("getItem");
                            Object itemObj = getItem.invoke(proj);
                            if (itemObj != null) {
                                if (itemObj instanceof Item) {
                                    ResourceLocation key = ForgeRegistries.ITEMS.getKey((Item) itemObj);
                                    if (key != null) projectileItemId = key.toString();
                                } else {
                                    projectileItemId = itemObj.toString();
                                }
                            }
                        } catch (NoSuchMethodException nsme) {
                            try {
                                Field itemField = proj.getClass().getDeclaredField("item");
                                itemField.setAccessible(true);
                                Object itemObj = itemField.get(proj);
                                if (itemObj instanceof Item) {
                                    ResourceLocation key = ForgeRegistries.ITEMS.getKey((Item) itemObj);
                                    if (key != null) projectileItemId = key.toString();
                                } else if (itemObj != null) {
                                    projectileItemId = itemObj.toString();
                                }
                            } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            Main.LOGGER.debug("Could not detect projectile item id via Gun reflection: {}", e.getMessage());
        }

        int consumed = 0;
        Main.LOGGER.info("JEG/MTEG consuming ammo - Current: {}, Needed: {}, Inventory Size: {}",
            currentAmmo, needed, inventory.getContainerSize());

        for (int i = 0; i < inventory.getContainerSize() && consumed < needed; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            boolean matches = false;
            if (projectileItemId != null) {
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (key != null && projectileItemId.equals(key.toString())) matches = true;
            } else {
                // fallback to description string heuristics
                String descId = stack.getDescriptionId().toLowerCase();
                matches = isProbablyAmmoForGunByName(descId);
            }

            if (matches) {
                int toTake = Math.min(stack.getCount(), needed - consumed);
                stack.shrink(toTake);
                consumed += toTake;

                Main.LOGGER.info("JEG/MTEG consumed {} from '{}' (slot {})", toTake, stack.getDescriptionId(), i);
            }
        }

        if (consumed > 0) {
            int newAmount = currentAmmo + consumed;
            writeAmmoNormalized(mainHand, newAmount);
            inventory.setChanged();
            Main.LOGGER.info("JEG/MTEG reload: {}/{} + {} consumed = {}/{}",
                currentAmmo, magSize, consumed, newAmount, magSize);
        } else {
            // Do NOT refill magazine if no ammo was found!
            Main.LOGGER.warn("JEG/MTEG reload failed: no ammo found in inventory, magazine remains at {}/{}", currentAmmo, magSize);
        }
    }

    private boolean isProbablyAmmoForGunByName(String lowerDesc) {
        switch (this.gunItemName) {
            case "RIFLE":
                return lowerDesc.contains("rifle") || lowerDesc.contains("762") || lowerDesc.contains("556") || lowerDesc.contains("rifle_ammo");
            case "PISTOL":
            case "SMG":
                return lowerDesc.contains("pistol") || lowerDesc.contains("9mm") || lowerDesc.contains("pistol_ammo");
            case "SHOTGUN":
                return lowerDesc.contains("shotgun") || lowerDesc.contains("shell") || lowerDesc.contains("handmade_shell");
            default:
                return lowerDesc.contains("ammo") || lowerDesc.contains("shell") || lowerDesc.contains("cartridge");
        }
    }

    /**
     * Detect gun type. Prefer reading Gun -> projectile item id; fallback to other reflection and name heuristics.
     */
    public static String detectGunType(ItemStack gunStack) {
        // Try runtime Gun-based detection
        try {
            Class<?> gunItemClass = findClass(IDX_GUN_ITEM);
            if (gunItemClass != null && gunItemClass.isInstance(gunStack.getItem())) {
                try {
                    Method getGun = gunItemClass.getMethod("getGun");
                    Object gunObj = getGun.invoke(gunStack.getItem());
                    if (gunObj != null) {
                        // getProjectile() then inspect its item
                        try {
                            Method getProjectile = gunObj.getClass().getMethod("getProjectile");
                            Object proj = getProjectile.invoke(gunObj);
                            if (proj != null) {
                                try {
                                    Method getItem = proj.getClass().getMethod("getItem");
                                    Object itemObj = getItem.invoke(proj);
                                    if (itemObj instanceof Item) {
                                        ResourceLocation key = ForgeRegistries.ITEMS.getKey((Item) itemObj);
                                        if (key != null) {
                                            String type = classifyByAmmo(key.toString());
                                            if ("PISTOL_AMMO".equals(type)) {
                                                // differentiate pistol vs smg via mag size if possible
                                                return distinguishByMagSize(gunStack, gunItemClass);
                                            }
                                            return type;
                                        }
                                    } else if (itemObj != null) {
                                        String type = classifyByAmmo(itemObj.toString());
                                        if ("PISTOL_AMMO".equals(type)) return distinguishByMagSize(gunStack, gunItemClass);
                                        return type;
                                    }
                                } catch (NoSuchMethodException nsme) {
                                    try {
                                        Field itemField = proj.getClass().getDeclaredField("item");
                                        itemField.setAccessible(true);
                                        Object itemObj = itemField.get(proj);
                                        if (itemObj instanceof Item) {
                                            ResourceLocation key = ForgeRegistries.ITEMS.getKey((Item) itemObj);
                                            if (key != null) {
                                                String type = classifyByAmmo(key.toString());
                                                if ("PISTOL_AMMO".equals(type)) return distinguishByMagSize(gunStack, gunItemClass);
                                                return type;
                                            }
                                        } else if (itemObj != null) {
                                            String type = classifyByAmmo(itemObj.toString());
                                            if ("PISTOL_AMMO".equals(type)) return distinguishByMagSize(gunStack, gunItemClass);
                                            return type;
                                        }
                                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                                }
                            }
                        } catch (NoSuchMethodException ignored) {}
                    }
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Exception e) {
            Main.LOGGER.debug("Gun-based type detection failed: {}", e.getMessage());
        }

        // Fallback to earlier reflection used by Recruits (ammo method/fields)
        try {
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
        } catch (Exception e) {
            Main.LOGGER.debug("Reflection-based type detection failed: {}", e.getMessage());
        }

        // Final fallback: use descriptionId/name-based detection
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
    public AbstractHurtingProjectile getProjectile(LivingEntity shooter) {
        try {
            Level level = shooter.getCommandSenderWorld();

            Class<?> bulletClass = findClass(IDX_BULLET);
            Class<?> modEntitiesClass = findClass(IDX_ENTITIES);

            if (bulletClass == null || modEntitiesClass == null) {
                Main.LOGGER.error("Could not find Bullet/Projectile class or ModEntities class for JEG/MTEG");
                return null;
            }

            // try common field names in ModEntities: PROJECTILE, BULLET
            Field bulletTypeField = null;
            for (String fname : new String[]{"PROJECTILE", "BULLET"}) {
                try {
                    bulletTypeField = modEntitiesClass.getField(fname);
                    if (bulletTypeField != null) break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (bulletTypeField == null) {
                Main.LOGGER.error("Could not find entity type field in ModEntities");
                return null;
            }

            Object entityType = bulletTypeField.get(null);

            Constructor<?> constructor = bulletClass.getConstructor(net.minecraft.world.entity.EntityType.class, Level.class);

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
        // Try to read from ModSounds class first (ttv.migami.jeg.init.ModSounds)
        Class<?> soundClass = findClass(IDX_SOUNDS);
        if (soundClass != null) {
            for (String fieldName : new String[]{"GUN_FIRE", "SHOOT", "FIRE", "GUN_SHOT", "REV_FIRE", "FIRE"}) {
                try {
                    Field soundField = soundClass.getField(fieldName);
                    Object soundEvent = soundField.get(null);
                    if (soundEvent instanceof SoundEvent) {
                        return (SoundEvent) soundEvent;
                    } else if (soundEvent instanceof ResourceLocation) {
                        SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue((ResourceLocation) soundEvent);
                        if (event != null) return event;
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
            for (String fieldName : new String[]{"GUN_RELOAD", "RELOAD", "RELOADING", "REV_RELOAD_BULLETS_OUT", "REV_CHAMBER_SPIN"}) {
                try {
                    Field soundField = soundClass.getField(fieldName);
                    Object soundEvent = soundField.get(null);
                    if (soundEvent instanceof SoundEvent) {
                        return (SoundEvent) soundEvent;
                    } else if (soundEvent instanceof ResourceLocation) {
                        SoundEvent event = ForgeRegistries.SOUND_EVENTS.getValue((ResourceLocation) soundEvent);
                        if (event != null) return event;
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

    // performRangedAttackIWeapon and predictTargetPosition remain unchanged from original implementation but
    // will use the normalized ammo helpers internally (readAmmoNormalized/writeAmmoNormalized).
    // For brevity, I left those original methods in place; if your current file cut them off,
    // copy the existing performRangedAttackIWeapon(...) from your current file into this class.

    @Override
    public void performRangedAttackIWeapon(AbstractRecruitEntity shooter, double x, double y, double z, float projectileSpeed) {
        AbstractHurtingProjectile projectileEntity = this.getProjectile(shooter);
        if (projectileEntity == null) return;

        double d0 = x - shooter.getX();
        double d1 = y + 0.5D - projectileEntity.getY();
        double d2 = z - shooter.getZ();

        // Use the shared shooting logic
        this.shoot(shooter, projectileEntity, d0, d1, d2);

        // Play sound and spawn projectile on server
        shooter.playSound(this.getShootSound(), 1.0F, 1.0F / (shooter.getRandom().nextFloat() * 0.4F + 0.8F));
        if (!shooter.getCommandSenderWorld().isClientSide()) {
            shooter.getCommandSenderWorld().addFreshEntity(projectileEntity);
        }

        // Decrement ammo (normalized) and damage the held item
        ItemStack mainHand = shooter.getMainHandItem();
        try {
            // If the server config requires explicit ammo consumption, use normalized setter
            if (RecruitsServerConfig.RangedRecruitsNeedArrowsToShoot.get()) {
                this.setLoaded(mainHand, false); // setLoaded(false) reduces ammo by 1 in JEGWeapon
            }
        } catch (Exception e) {
            Main.LOGGER.warn("Failed to decrement JEG/MTEG ammo: {}", e.getMessage());
        }

        shooter.damageMainHandItem();
    }
}