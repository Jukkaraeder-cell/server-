package de.ice.minecarts;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class IceMinecarts extends JavaPlugin implements Listener {

    private static final class Car {
        final Minecart cart;
        final ItemDisplay display;
        final String model;
        ArmorStand seat;
        final Set<UUID> links = new HashSet<>();
        UUID parent;
        float lastYaw;

        Car(Minecart cart, ItemDisplay display, String model) {
            this.cart = cart;
            this.display = display;
            this.model = model;
            this.lastYaw = cart.getLocation().getYaw();
        }

        boolean coupleable() {
            return model.equals("front") || model.equals("middle") || model.equals("end");
        }
    }

    private final Map<UUID, Car> cars = new HashMap<>();
    private final Map<String, String> models = new HashMap<>();
    private NamespacedKey markKey;
    private int tickCounter = 0;

    private float scale = 1.0f;
    private double yOffset = 0.0;
    private float yawOffset = 0.0f;
    private boolean couplingEnabled = true;
    private double connectDistance = 1.35;
    private double followDistance = 1.0;
    private double breakDistance = 3.0;
    private double pull = 0.5;
    private double maxSpeed = 0.4;
    private boolean seatsEnabled = true;
    private boolean hideBlocks = true;

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
        couplingEnabled = getConfig().getBoolean("coupling.enabled", true);
        connectDistance = getConfig().getDouble("coupling.connect-distance", 1.35);
        followDistance = getConfig().getDouble("coupling.follow-distance", 1.0);
        breakDistance = getConfig().getDouble("coupling.break-distance", 3.0);
        pull = getConfig().getDouble("coupling.pull", 0.5);
        maxSpeed = getConfig().getDouble("coupling.max-speed", 0.4);
        seatsEnabled = getConfig().getBoolean("seats-enabled", true);
        hideBlocks = getConfig().getBoolean("hide-blocks", true);

        models.clear();
        models.put("furnace_minecart", "front");
        models.put("minecart", "middle");
        models.put("chest_minecart", "end");
        ConfigurationSection sec = getConfig().getConfigurationSection("car-models");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                models.put(key, sec.getString(key, "vanilla"));
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
            discard(car);
        }
        cars.clear();
    }

    private void discard(Car car) {
        if (car.display.isValid()) {
            car.display.remove();
        }
        if (car.seat != null && car.seat.isValid()) {
            car.seat.remove();
        }
    }

    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            getServer().getScheduler().runTask(this, () -> attach(cart));
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof Minecart cart) {
            detach(cart.getUniqueId());
        }
    }

    private void detach(UUID id) {
        Car car = cars.remove(id);
        if (car == null) {
            return;
        }
        for (UUID other : car.links) {
            Car o = cars.get(other);
            if (o != null) {
                o.links.remove(id);
            }
        }
        discard(car);
        rebuildChains();
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

        Car car = new Car(cart, display, model);
        cars.put(cart.getUniqueId(), car);

        if (hideBlocks && car.coupleable()) {
            // Block im Minecart (Kiste, Ofen) ausblenden
            cart.setDisplayBlockData(Material.AIR.createBlockData());
        }

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

    // ---------------------------------------------------------------- Sitzen

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!seatsEnabled || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Minecart cart)) {
            return;
        }
        Car car = cars.get(cart.getUniqueId());
        boolean rideable = cart instanceof RideableMinecart;
        if (car == null || !(car.coupleable() || rideable)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking() && !rideable) {
            return; // Schleichen + Rechtsklick = normale Funktion (Kiste öffnen, Ofen befüllen)
        }
        Material held = player.getInventory().getItemInMainHand().getType();
        if (car.model.equals("front") && (held == Material.COAL || held == Material.CHARCOAL)) {
            return; // Ofen-Minecart mit Kohle befüllen
        }
        event.setCancelled(true);
        ArmorStand seat = ensureSeat(car);
        if (!seat.getPassengers().isEmpty()) {
            return; // Platz ist besetzt
        }
        seat.addPassenger(player);
    }

    private ArmorStand ensureSeat(Car car) {
        if (car.seat != null && car.seat.isValid()) {
            return car.seat;
        }
        ArmorStand stand = car.cart.getWorld().spawn(car.cart.getLocation(), ArmorStand.class, a -> {
            a.setVisible(false);
            a.setMarker(true);
            a.setSmall(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(false);
        });
        car.cart.addPassenger(stand);
        car.seat = stand;
        return stand;
    }

    // ----------------------------------------------------------- Verbinden

    private void tryCouple() {
        List<Car> list = new ArrayList<>();
        for (Car c : cars.values()) {
            if (c.coupleable() && c.cart.isValid()) {
                list.add(c);
            }
        }
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Car a = list.get(i);
            for (int j = i + 1; j < list.size(); j++) {
                Car b = list.get(j);
                if (a.links.size() >= 2 || b.links.size() >= 2) {
                    continue;
                }
                if (a.links.contains(b.cart.getUniqueId())) {
                    continue;
                }
                if (!a.cart.getWorld().equals(b.cart.getWorld())) {
                    continue;
                }
                if (horizontalDistance(a.cart.getLocation(), b.cart.getLocation()) <= connectDistance
                        && Math.abs(a.cart.getLocation().getY() - b.cart.getLocation().getY()) < 1.5) {
                    a.links.add(b.cart.getUniqueId());
                    b.links.add(a.cart.getUniqueId());
                    changed = true;
                }
            }
        }
        if (changed) {
            rebuildChains();
        }
    }

    private static double horizontalDistance(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void unlink(Car a, Car b) {
        a.links.remove(b.cart.getUniqueId());
        b.links.remove(a.cart.getUniqueId());
    }

    /** Legt pro Zug fest, wer vorne fährt (Wurzel) und wem jeder Wagen folgt. */
    private void rebuildChains() {
        for (Car c : cars.values()) {
            c.parent = null;
        }
        Set<UUID> visited = new HashSet<>();
        for (Car start : cars.values()) {
            if (visited.contains(start.cart.getUniqueId()) || start.links.isEmpty()) {
                continue;
            }
            List<Car> comp = new ArrayList<>();
            ArrayDeque<Car> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start.cart.getUniqueId());
            while (!queue.isEmpty()) {
                Car c = queue.poll();
                comp.add(c);
                for (UUID id : c.links) {
                    Car o = cars.get(id);
                    if (o != null && visited.add(id)) {
                        queue.add(o);
                    }
                }
            }
            Car root = null;
            for (Car c : comp) {
                if (c.links.size() <= 1 && c.model.equals("front")) {
                    root = c;
                    break;
                }
            }
            if (root == null) {
                for (Car c : comp) {
                    if (c.links.size() <= 1 && !c.model.equals("end")) {
                        root = c;
                        break;
                    }
                }
            }
            if (root == null) {
                root = comp.get(0);
            }
            Set<UUID> seen = new HashSet<>();
            seen.add(root.cart.getUniqueId());
            ArrayDeque<Car> q2 = new ArrayDeque<>();
            q2.add(root);
            while (!q2.isEmpty()) {
                Car c = q2.poll();
                for (UUID id : c.links) {
                    Car o = cars.get(id);
                    if (o != null && seen.add(id)) {
                        o.parent = c.cart.getUniqueId();
                        q2.add(o);
                    }
                }
            }
        }
    }

    private void follow(Car car) {
        Car leader = cars.get(car.parent);
        if (leader == null || !leader.cart.isValid()) {
            return;
        }
        Location fl = car.cart.getLocation();
        Location ll = leader.cart.getLocation();
        double dx = ll.getX() - fl.getX();
        double dz = ll.getZ() - fl.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > breakDistance) {
            unlink(car, leader);
            rebuildChains();
            return;
        }
        if (dist < 0.01) {
            return;
        }
        Vector lv = leader.cart.getVelocity();
        double leaderSpeed = Math.sqrt(lv.getX() * lv.getX() + lv.getZ() * lv.getZ());
        double speed = leaderSpeed + (dist - followDistance) * pull;
        speed = Math.max(0.0, Math.min(maxSpeed, speed));
        car.cart.setVelocity(new Vector(dx / dist * speed, 0.0, dz / dist * speed));
    }

    // ---------------------------------------------------------------- Tick

    private float yawFor(Car car) {
        Location me = car.cart.getLocation();
        Vector dir = null;
        if (car.parent != null) {
            Car p = cars.get(car.parent);
            if (p != null) {
                dir = p.cart.getLocation().toVector().subtract(me.toVector());
            }
        } else {
            for (UUID id : car.links) {
                Car o = cars.get(id);
                if (o != null) {
                    dir = me.toVector().subtract(o.cart.getLocation().toVector());
                    break;
                }
            }
        }
        if (dir != null && (dir.getX() * dir.getX() + dir.getZ() * dir.getZ()) >= 1.0e-4) {
            car.lastYaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
            return car.lastYaw;
        }
        return loneYaw(car);
    }

    /** Einzelner Wagen: Fahrtrichtung, im Stand die Richtung der Schiene. */
    private float loneYaw(Car car) {
        Vector v = car.cart.getVelocity();
        double speed = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        if (speed > 0.03) {
            car.lastYaw = (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ()));
            return car.lastYaw;
        }
        Float axis = railAxisYaw(car.cart.getLocation());
        if (axis == null) {
            return car.lastYaw;
        }
        float a = axis;
        float b = axis + 180.0f;
        car.lastYaw = angleDiff(a, car.lastYaw) <= angleDiff(b, car.lastYaw) ? a : b;
        return car.lastYaw;
    }

    private static float angleDiff(float x, float y) {
        return Math.abs(((x - y) % 360.0f + 540.0f) % 360.0f - 180.0f);
    }

    private Float railAxisYaw(Location loc) {
        Block block = loc.getBlock();
        if (!(block.getBlockData() instanceof Rail)) {
            block = block.getRelative(BlockFace.DOWN);
        }
        if (!(block.getBlockData() instanceof Rail rail)) {
            return null;
        }
        return switch (rail.getShape()) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> 0.0f;
            case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> 90.0f;
            case SOUTH_EAST, NORTH_WEST -> 45.0f;
            case NORTH_EAST, SOUTH_WEST -> 135.0f;
        };
    }

    private void tick() {
        tickCounter++;
        if (couplingEnabled && tickCounter % 5 == 0) {
            tryCouple();
        }
        Iterator<Map.Entry<UUID, Car>> it = cars.entrySet().iterator();
        boolean removed = false;
        while (it.hasNext()) {
            Car car = it.next().getValue();
            if (!car.cart.isValid() || !car.display.isValid()) {
                for (UUID other : car.links) {
                    Car o = cars.get(other);
                    if (o != null) {
                        o.links.remove(car.cart.getUniqueId());
                    }
                }
                discard(car);
                it.remove();
                removed = true;
                continue;
            }
            if (car.display.getVehicle() == null) {
                car.cart.addPassenger(car.display);
            }
            if (car.seat != null && !car.seat.isValid()) {
                car.seat = null;
            }
            if (couplingEnabled && car.parent != null) {
                follow(car);
            }
            car.display.setRotation(yawFor(car) + yawOffset, car.cart.getLocation().getPitch());
        }
        if (removed) {
            rebuildChains();
        }
    }

    // -------------------------------------------------------------- Befehle

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            removeAll();
            loadSettings();
            attachAll();
            sender.sendMessage("IceMinecarts neu geladen.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("uncouple") && sender instanceof Player player) {
            int count = 0;
            for (Car car : cars.values()) {
                if (car.cart.getWorld().equals(player.getWorld())
                        && car.cart.getLocation().distance(player.getLocation()) <= 3.0) {
                    for (UUID id : new ArrayList<>(car.links)) {
                        Car o = cars.get(id);
                        if (o != null) {
                            unlink(car, o);
                            count++;
                        }
                    }
                }
            }
            rebuildChains();
            sender.sendMessage("Verbindungen gelöst: " + count);
            return true;
        }
        sender.sendMessage("Benutzung: /iceminecarts reload | uncouple");
        return true;
    }
}
