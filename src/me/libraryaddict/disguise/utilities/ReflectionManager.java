package me.libraryaddict.disguise.utilities;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import org.bukkit.Art;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.comphenix.protocol.wrappers.WrappedGameProfile;

public class ReflectionManager {
    public enum LibVersion {
        V1_6, V1_7;
        private static LibVersion currentVersion = LibVersion.V1_7;
        static {
            if (getBukkitVersion().startsWith("v1_")) {
                try {
                    int version = Integer.parseInt(getBukkitVersion().split("_")[1]);
                    if (version == 7) {
                        currentVersion = LibVersion.V1_7;
                    } else {
                        if (version < 7) {
                            currentVersion = LibVersion.V1_6;
                        } else {
                            currentVersion = LibVersion.V1_7;
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        public static LibVersion getGameVersion() {
            return currentVersion;
        }

        public static boolean is1_6() {
            return getGameVersion() == V1_6;
        }

        public static boolean is1_7() {
            return getGameVersion() == V1_7;
        }
    }

    private static String bukkitVersion = Bukkit.getServer().getClass().getName().split("\\.")[3];
    private static Method damageAndIdleSoundMethod;
    private static Class itemClass;
    private static Field pingField;
    private static boolean isForge = Bukkit.getServer().getName().equalsIgnoreCase("Cauldron");
    /**
     * Map of mc-dev simple class name to fully qualified Forge class name.
     */
    private static Map<String, String> ForgeClassMappings;
    /**
     * Map of Forge fully qualified class names to a map from mc-dev field names to Forge field names.
     */
    private static Map<String, Map<String, String>> ForgeFieldMappings;
    /**
     * Map of Forge fully qualified class names to a map from mc-dev method names to Forge method names.
     */
    private static Map<String, Map<String, String>> ForgeMethodMappings;

    private static String dir2fqn(String s) {
        return s.replaceAll("/", ".");
    }

    static {
        if (isForge) {
            // Initialize the maps by reading the srg file
            ForgeClassMappings = new HashMap<String, String>();
            ForgeFieldMappings = new HashMap<String, Map<String, String>>();
            //ForgeMethodMappings = new HashMap<String, Map<String, String>>();
            try {
                InputStream stream = Class.forName("net.minecraftforge.common.MinecraftForge").getClassLoader()
                        .getResourceAsStream("mappings/" + getBukkitVersion() + "/cb2numpkg.srg");
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                // 1: cb-simpleName
                // 2: forge-fullName (Needs dir2fqn())
                Pattern classPattern = Pattern.compile("^CL: net/minecraft/server/(\\w+) ([a-zA-Z0-9$/_]+)$");
                // 1: cb-simpleName
                // 2: cb-fieldName
                // 3: forge-fullName (Needs dir2fqn())
                // 4: forge-fieldName
                Pattern fieldPattern = Pattern.compile("^FD: net/minecraft/server/(\\w+)/(\\w+) ([a-zA-Z0-9$/_]+)/([a-zA-Z0-9$/_]+)$");

                Pattern methodPattern = Pattern.compile("!XXX todo");

                String line;
                System.out.println("Reading");
                while ((line = reader.readLine()) != null) {
                    Matcher classMatcher = classPattern.matcher(line);
                    if (classMatcher.matches()) {
                        ForgeClassMappings.put(classMatcher.group(1), dir2fqn(classMatcher.group(2)));
                        continue;
                    }
                    Matcher fieldMatcher = fieldPattern.matcher(line);
                    if (fieldMatcher.matches()) {
                        Map<String, String> innerMap = ForgeFieldMappings.get(dir2fqn(fieldMatcher.group(3)));
                        if (innerMap == null) {
                            innerMap = new HashMap<String, String>();
                            ForgeFieldMappings.put(dir2fqn(fieldMatcher.group(3)), innerMap);
                        }
                        innerMap.put(fieldMatcher.group(2), fieldMatcher.group(4));
                        continue;
                    }
                    Matcher methodMatcher = methodPattern.matcher(line);
                    if (methodMatcher.matches()) {
                        // todo
                    }
                }
                System.out.println(ForgeClassMappings.size() + " class mappings loaded");
                System.out.println(ForgeFieldMappings.size() + " field mappings loaded");
                System.out.println(ForgeMethodMappings.size() + " method mappings loaded");
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
                System.err.println("Warning: Running on Cauldron server, but couldn't load mappings file");
            } catch (IOException e) {
                e.printStackTrace();
                System.err.println("Warning: Running on Cauldron server, but couldn't load mappings file");
            }

            System.out.println(ForgeClassMappings.get("EntityLiving"));
            System.out.println(getNmsClass("EntityLiving"));
        }

        for (Method method : getNmsClass("EntityLiving").getDeclaredMethods()) {
            try {
                if (method.getReturnType() == float.class && Modifier.isProtected(method.getModifiers())
                        && method.getParameterTypes().length == 0) {
                    Object entity = createEntityInstance("Cow");
                    method.setAccessible(true);
                    float value = (Float) method.invoke(entity);
                    if (value == 0.4F) {
                        damageAndIdleSoundMethod = method;
                        break;
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        try {
            itemClass = getCraftClass("inventory.CraftItemStack");
            pingField = getNmsField("EntityPlayer", "ping");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Object createEntityInstance(String entityName) {
        try {
            Class entityClass = getNmsClass("Entity" + entityName);
            Object entityObject;
            Object world = getWorld(Bukkit.getWorlds().get(0));
            if (entityName.equals("Player")) {
                Object minecraftServer = getNmsClass("MinecraftServer").getMethod("getServer").invoke(null);
                Object playerinteractmanager = getNmsClass("PlayerInteractManager").getConstructor(getNmsClass("World"))
                        .newInstance(world);
                if (LibVersion.is1_7()) {
                    WrappedGameProfile gameProfile = getGameProfile(null, "LibsDisguises");
                    entityObject = entityClass.getConstructor(getNmsClass("MinecraftServer"), getNmsClass("WorldServer"),
                            gameProfile.getHandleType(), playerinteractmanager.getClass()).newInstance(minecraftServer, world,
                            gameProfile.getHandle(), playerinteractmanager);
                } else {
                    entityObject = entityClass.getConstructor(getNmsClass("MinecraftServer"), getNmsClass("World"), String.class,
                            playerinteractmanager.getClass()).newInstance(minecraftServer, world, "LibsDisguises",
                            playerinteractmanager);
                }
            } else {
                entityObject = entityClass.getConstructor(getNmsClass("World")).newInstance(world);
            }
            return entityObject;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static FakeBoundingBox getBoundingBox(Entity entity) {
        try {
            Object boundingBox = getNmsField("Entity", "boundingBox").get(getNmsEntity(entity));
            double x = 0, y = 0, z = 0;
            int stage = 0;
            for (Field field : boundingBox.getClass().getFields()) {
                if (field.getType().getSimpleName().equals("double")) {
                    stage++;
                    switch (stage) {
                    case 1:
                        x -= field.getDouble(boundingBox);
                        break;
                    case 2:
                        y -= field.getDouble(boundingBox);
                        break;
                    case 3:
                        z -= field.getDouble(boundingBox);
                        break;
                    case 4:
                        x += field.getDouble(boundingBox);
                        break;
                    case 5:
                        y += field.getDouble(boundingBox);
                        break;
                    case 6:
                        z += field.getDouble(boundingBox);
                        break;
                    default:
                        throw new Exception("Error while setting the bounding box, more doubles than I thought??");
                    }
                }
            }
            return new FakeBoundingBox(x, y, z);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static Entity getBukkitEntity(Object nmsEntity) {
        try {
            return (Entity) ReflectionManager.getNmsClass("Entity").getMethod("getBukkitEntity").invoke(nmsEntity);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static ItemStack getBukkitItem(Object nmsItem) {
        try {
            return (ItemStack) itemClass.getMethod("asBukkitCopy", getNmsClass("ItemStack")).invoke(null, nmsItem);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getBukkitVersion() {
        return bukkitVersion;
    }

    public static Class getCraftClass(String className) {
        try {
            return Class.forName("org.bukkit.craftbukkit." + getBukkitVersion() + "." + className);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getCraftSound(Sound sound) {
        try {
            Class c = getCraftClass("CraftSound");
            return (String) c.getMethod("getSound", Sound.class).invoke(null, sound);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static String getEnumArt(Art art) {
        try {
            Class craftArt = Class.forName("org.bukkit.craftbukkit." + getBukkitVersion() + ".CraftArt");
            Object enumArt = craftArt.getMethod("BukkitToNotch", Art.class).invoke(null, art);
            for (Field field : enumArt.getClass().getFields()) {
                if (field.getType() == String.class) {
                    return (String) field.get(enumArt);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static WrappedGameProfile getGameProfile(Player player) {
        if (LibVersion.is1_7()) {
            return WrappedGameProfile.fromPlayer(player);
        }
        return null;
    }

    public static WrappedGameProfile getGameProfile(UUID uuid, String playerName) {
        try {
            return new WrappedGameProfile(uuid != null ? uuid : UUID.randomUUID(), playerName);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static WrappedGameProfile getGameProfileWithThisSkin(UUID uuid, String playerName, WrappedGameProfile profileWithSkin) {
        try {
            WrappedGameProfile gameProfile = new WrappedGameProfile(uuid != null ? uuid : UUID.randomUUID(), playerName);
            gameProfile.getProperties().putAll(profileWithSkin.getProperties());
            return gameProfile;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static Class getNmsClass(String className) {
        try {
            if (isForge) {
                String forgeName = ForgeClassMappings.get(className);
                if (forgeName == null) {
                    throw new RuntimeException("Missing Forge mapping for " + className);
                }
                return Class.forName(forgeName);
            }

            return Class.forName("net.minecraft.server." + getBukkitVersion() + "." + className);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static Object getNmsEntity(Entity entity) {
        try {
            return getCraftClass("entity.CraftEntity").getMethod("getHandle").invoke(entity);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static Object getNmsItem(ItemStack itemstack) {
        try {
            return itemClass.getMethod("asNMSCopy", ItemStack.class).invoke(null, itemstack);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static Field getNmsField(String className, String fieldName) {
        return getNmsField(getNmsClass(className), fieldName);
    }

    public static Field getNmsField(Class clazz, String fieldName) {
        try {
            if (isForge) {
                return clazz.getField(ForgeFieldMappings.get(clazz.getName()).get(fieldName));
            }
            return clazz.getField(fieldName);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static Method getNmsMethod(String className, String methodName, Class<?>... parameters) {
        return getNmsMethod(getNmsClass(className), methodName, parameters);
    }

    public static Method getNmsMethod(Class clazz, String methodName, Class<?>... parameters) {
        try {
            if (isForge) {
                return clazz.getMethod(ForgeMethodMappings.get(clazz.getName()).get(methodName), parameters);
            }
            return clazz.getMethod(methodName, parameters);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static double getPing(Player player) {
        try {
            return (double) pingField.getInt(ReflectionManager.getNmsEntity(player));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return 0D;
    }

    public static float[] getSize(Entity entity) {
        try {
            float length = getNmsField("Entity", "length").getFloat(getNmsEntity(entity));
            float width = getNmsClass("Entity").getField("width").getFloat(getNmsEntity(entity));
            float height = getNmsClass("Entity").getField("height").getFloat(getNmsEntity(entity));
            return new float[] { length, width, height };
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static WrappedGameProfile getSkullBlob(WrappedGameProfile gameProfile) {
        try {
            Object minecraftServer = getNmsClass("MinecraftServer").getMethod("getServer").invoke(null);
            for (Method method : getNmsClass("MinecraftServer").getMethods()) {
                if (method.getReturnType().getSimpleName().equals("MinecraftSessionService")) {
                    Object session = method.invoke(minecraftServer);
                    return WrappedGameProfile.fromHandle(session.getClass()
                            .getMethod("fillProfileProperties", gameProfile.getHandleType(), boolean.class)
                            .invoke(session, gameProfile.getHandle(), true));
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static Float getSoundModifier(Object entity) {
        try {
            damageAndIdleSoundMethod.setAccessible(true);
            return (Float) damageAndIdleSoundMethod.invoke(entity);
        } catch (Exception ex) {
        }
        return null;
    }

    public static Object getWorld(World world) {
        try {
            return getCraftClass("CraftWorld").getMethod("getHandle").invoke(world);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static WrappedGameProfile grabProfileAddUUID(String playername) {
        try {
            Object minecraftServer = getNmsClass("MinecraftServer").getMethod("getServer").invoke(null);
            for (Method method : getNmsClass("MinecraftServer").getMethods()) {
                if (method.getReturnType().getSimpleName().equals("GameProfileRepository")) {
                    Object profileRepo = method.invoke(minecraftServer);
                    Object agent = Class.forName("net.minecraft.util.com.mojang.authlib.Agent").getField("MINECRAFT").get(null);
                    LibsProfileLookupCaller callback = new LibsProfileLookupCaller();
                    profileRepo
                            .getClass()
                            .getMethod("findProfilesByNames", String[].class, agent.getClass(),
                                    Class.forName("net.minecraft.util.com.mojang.authlib.ProfileLookupCallback"))
                            .invoke(profileRepo, new String[] { playername }, agent, callback);
                    return callback.getGameProfile();
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static boolean hasSkinBlob(WrappedGameProfile gameProfile) {
        return !gameProfile.getProperties().isEmpty();
    }

    public static void setAllowSleep(Player player) {
        try {
            Object nmsEntity = getNmsEntity(player);
            Object connection = nmsEntity.getClass().getField("playerConnection").get(nmsEntity);
            Field check = connection.getClass().getField("checkMovement");
            check.setBoolean(connection, true);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public static void setBoundingBox(Entity entity, FakeBoundingBox newBox) {
        try {
            Object boundingBox = getNmsClass("Entity").getField("boundingBox").get(getNmsEntity(entity));
            int stage = 0;
            Location loc = entity.getLocation();
            for (Field field : boundingBox.getClass().getFields()) {
                if (field.getType().getSimpleName().equals("double")) {
                    stage++;
                    switch (stage) {
                    case 1:
                        field.setDouble(boundingBox, loc.getX() - newBox.getX());
                        break;
                    case 2:
                        // field.setDouble(boundingBox, loc.getY() - newBox.getY());
                        break;
                    case 3:
                        field.setDouble(boundingBox, loc.getZ() - newBox.getZ());
                        break;
                    case 4:
                        field.setDouble(boundingBox, loc.getX() + newBox.getX());
                        break;
                    case 5:
                        field.setDouble(boundingBox, loc.getY() + newBox.getY());
                        break;
                    case 6:
                        field.setDouble(boundingBox, loc.getZ() + newBox.getZ());
                        break;
                    default:
                        throw new Exception("Error while setting the bounding box, more doubles than I thought??");
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
