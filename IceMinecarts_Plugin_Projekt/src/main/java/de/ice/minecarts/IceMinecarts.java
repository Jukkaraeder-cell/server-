package de.ice.minecarts;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class IceMinecarts extends JavaPlugin implements Listener {

    private record Car(Minecart cart, ItemDisplay display) {}

    private final Map<UUID, Car> cars = new HashMap<>();
    private final Map<String, String> models = new HashMap<>();
    private NamespacedKey markKey;
    private float scale = 1.0f;
    private double yOffset = 0.0;
    private float yawOffset = 0.0f;

    @Override
    public void onEnable() {
        markKey = new NamespacedKey(this, "ice_display");
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        attachAll();
    }

    @Override
    public void onDisable() {
        removeAll();
    }

    private void loadSettings() {
        reloadConfig();
        scale = (float) getConfig().getDouble("scale", 1.0);
        yOffset = getConfig().getDouble("y-offset", 0.0);
        yawOffset = (float) getConfig().getDouble("yaw-offset", 0.0);
        models.clear();
        models.put("furnace_minecart", "front");
        models.put("minecart", "middle");
        models.put("chest_minecart", "end");
        ConfigurationSection sec = getConfig().getConfigurationSection("car-models");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                models.put(key, sec.getString(key, "middle"));
            }
        }
    }

    private void attachAll() {
        for (World world : getServer().getWorlds()) {
            for (Minecart cart : world.getEntitiesByClass(Minecart.class)) {
                attach(cart);
            }
        }
    }

    private void removeAll() {
        for (Car car : cars.values()) {
            if (car.display().isValid()) {
                car.display().remove();
            }
        }
        cars.clear();
    }

    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            // eine Tick warten, bis die Entity vollständig in der Welt ist
            getServer().getScheduler().runTask(this, () -> attach(cart));
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            Car car = cars.remove(cart.getUniqueId());
            if (car != null && car.display().isValid()) {
                car.display().remove();
            }
        }
    }

    private void attach(Minecart cart) {
        if (!cart.isValid() || cars.containsKey(cart.getUniqueId())) {
            return;
        }
        String typeKey = cart.getType().getKey().getKey();
        String model = models.getOrDefault(typeKey, "vanilla");

        ItemDisplay display = cart.getWorld().spawn(cart.getLocation(), ItemDisplay.class, d -> {
            ItemStack item = new ItemStack(Material.STICK);
            item.editMeta(meta -> meta.setItemModel(new NamespacedKey("ice", model)));
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setViewRange(2.0f);
            d.setPersistent(false);
            d.setGravity(false);
            d.setInvulnerable(true);
            d.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
            d.setTransformation(transformation(0.0));
        });
        cart.addPassenger(display);
        cars.put(cart.getUniqueId(), new Car(cart, display));

        // Nach 2 Ticks die tatsächliche Sitzhöhe auslesen und das Modell darauf ausrichten
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!cart.isValid() || !display.isValid()) {
                return;
            }
            double dy = display.getLocation().getY() - cart.getLocation().getY();
            if (Math.abs(dy) > 2.0) {
                dy = 0.0;
            }
            display.setTransformation(transformation(dy));
        }, 2L);
    }

    private Transformation transformation(double seatHeight) {
        float ty = (float) (0.5 * scale - seatHeight + yOffset);
        return new Transformation(
                new Vector3f(0.0f, ty, 0.0f),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f());
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Car>> it = cars.entrySet().iterator();
        while (it.hasNext()) {
            Car car = it.next().getValue();
            if (!car.cart().isValid() || !car.display().isValid()) {
                if (car.display().isValid()) {
                    car.display().remove();
                }
                it.remove();
                continue;
            }
            if (car.display().getVehicle() == null) {
                car.cart().addPassenger(car.display());
            }
            float yaw = car.cart().getLocation().getYaw() + yawOffset;
            float pitch = car.cart().getLocation().getPitch();
            car.display().setRotation(yaw, pitch);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            removeAll();
            loadSettings();
            attachAll();
            sender.sendMessage("IceMinecarts neu geladen.");
            return true;
        }
        sender.sendMessage("Benutzung: /iceminecarts reload");
        return true;
    }
}
