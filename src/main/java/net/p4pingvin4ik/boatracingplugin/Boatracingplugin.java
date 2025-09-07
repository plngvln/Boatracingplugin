package net.p4pingvin4ik.boatracingplugin;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleUpdateEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Boatracingplugin extends JavaPlugin implements Listener {

    // --- Параметры, загружаемые из конфигурационного файла ---

    private double maxIceSpeed;         // Максимальная скорость на льду.
    private double maxOffIceSpeed;      // Максимальная скорость вне льда (для возможности выехать).
    private double accelerationAmount;  // Величина, на которую скорость увеличивается каждый тик.
    private double decelerationFactor;  // Множитель для пассивного замедления (скольжения) на льду.
    private double momentumLossFactor;  // Множитель для потери инерции вне льда.
    private double brakeFactor;         // Множитель для активного торможения.
    private boolean debugMode;          // Включает вывод отладочной информации в action bar.

    /**
     * Хранит текущую "кастомную" скорость для каждой лодки, управляемой плагином.
     * Ключ - UUID лодки, Значение - текущая скорость в виде double.
     * Это позволяет плагину иметь собственное состояние скорости, независимое от ванильной физики.
     */
    private final Map<UUID, Double> boatSpeeds = new HashMap<>();

    /**
     * Набор ледяных блоков для быстрой проверки.
     */
    private final EnumSet<Material> iceBlocks = EnumSet.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE);

    /**
     * Вызывается при включении плагина.
     * Инициализирует конфигурацию, регистрирует события и выводит статус в консоль.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig(); // Создает config.yml из ресурсов, если он не существует.
        loadConfigValues();  // Загружает значения из config.yml в переменные.
        getServer().getPluginManager().registerEvents(this, this); // Регистрирует этот класс как обработчик событий.
        getLogger().info("BoatRacingPlugin включен");
        getLogger().info(String.format("Макс. скорость (лед/земля): %.2f / %.2f | Ускорение: %.4f",
                maxIceSpeed, maxOffIceSpeed, accelerationAmount));
        if (debugMode) {
            getLogger().warning("Режим отладки активирован");
        }
    }

    /**
     * Вызывается при выключении плагина.
     */
    @Override
    public void onDisable() {
        getLogger().info("BoatRacingPlugin выключен");
    }

    /**
     * Загружает и кэширует значения из файла config.yml.
     */
    private void loadConfigValues() {
        maxIceSpeed = getConfig().getDouble("max-ice-speed", 1.5);
        maxOffIceSpeed = getConfig().getDouble("max-off-ice-speed", 0.45);
        accelerationAmount = getConfig().getDouble("acceleration-amount", 0.025);
        decelerationFactor = getConfig().getDouble("deceleration-factor", 0.98);
        momentumLossFactor = getConfig().getDouble("momentum-loss-factor", 0.92);
        brakeFactor = getConfig().getDouble("brake-factor", 0.85);
        debugMode = getConfig().getBoolean("debug", false);
    }

    /**
     * Основной обработчик физики лодок. Вызывается каждый игровой тик для каждого транспортного средства.
     * @param event Событие обновления транспорта.
     */
    @EventHandler
    public void onVehicleUpdate(VehicleUpdateEvent event) {
        // Начальная проверка, чтобы отсеять все, что не является лодкой.
        if (!(event.getVehicle() instanceof Boat)) {
            return;
        }

        Boat boat = (Boat) event.getVehicle();
        UUID boatId = boat.getUniqueId();

        // Проверяем, есть ли в лодке игрок. Если нет, плагин не должен управлять лодкой.
        List<Entity> passengers = boat.getPassengers();
        if (passengers.isEmpty() || !(passengers.get(0) instanceof Player)) {
            boatSpeeds.remove(boatId); // Убираем лодку из-под контроля, если игрок вышел.
            return;
        }
        Player player = (Player) passengers.get(0);

        // Получаем текущую скорость из нашего хранилища. Если лодки там нет, скорость равна 0.
        double currentSpeed = boatSpeeds.getOrDefault(boatId, 0.0);

        // Определяем блок под лодкой и наличие льда.
        Block blockUnder = boat.getLocation().add(0, -0.2, 0).getBlock();
        boolean onIce = iceBlocks.contains(blockUnder.getType());

        // Получаем ввод игрока.
        float impulse = player.getForwardsMovement();
        String status = ""; // Статус для отладочного сообщения.

        if (onIce) {
            // Логика для движения на льду.
            if (impulse > 0) { // Игрок нажимает "вперед".
                currentSpeed = Math.min(maxIceSpeed, currentSpeed + accelerationAmount);
                status = "§bНа льду: §aУскорение";
            } else if (impulse < 0) { // Игрок нажимает "назад" (тормоз).
                currentSpeed *= brakeFactor;
                status = "§bНа льду: §cТорможение";
            } else { // Игрок ничего не нажимает (пассивное скольжение).
                currentSpeed *= decelerationFactor;
                status = "§bНа льду: §eСкольжение";
            }
        } else {
            // Логика для движения вне льда (земля, вода и т.д.)
            if (impulse > 0) {
                // Если текущая скорость выше лимита для земли (т.е. мы только что съехали со льда),
                // мы не ускоряемся, а продолжаем плавно терять скорость.
                if (currentSpeed > maxOffIceSpeed) {
                    currentSpeed *= momentumLossFactor;
                    status = "§6Вне льда: §7Инерция (скольжение)";
                } else {
                    // Если же скорость уже низкая, мы позволяем игроку разогнаться до лимита, чтобы выехать.
                    currentSpeed = Math.min(maxOffIceSpeed, currentSpeed + accelerationAmount);
                    status = "§6Вне льда: §aВыезд";
                }
            } else if (impulse < 0) { // Тормоз работает везде.
                currentSpeed *= brakeFactor;
                status = "§6Вне льда: §cТорможение";
            } else { // Пассивное замедление вне льда.
                currentSpeed *= momentumLossFactor;
                status = "§6Вне льда: §7Инерция";
            }
        }

        // Если скорость упала до незначительного значения, полностью останавливаем лодку
        // и убираем ее из нашего контроля, чтобы избежать лишних вычислений.
        if (currentSpeed < 0.01) {
            boatSpeeds.remove(boatId);
        } else {
            // Обновляем скорость в хранилище.
            boatSpeeds.put(boatId, currentSpeed);

            // Преобразуем числовое значение скорости в физический вектор движения.
            Vector direction = boat.getLocation().getDirection().setY(0).normalize();
            if (direction.lengthSquared() > 0) { // Проверка на случай, если вектор направления нулевой.
                Vector newVelocity = direction.multiply(currentSpeed);
                boat.setVelocity(newVelocity); // Применяем рассчитанную скорость к лодке.
            }
        }

        // Если включен режим отладки, выводим информацию в action bar игрока.
        if (debugMode) {
            double speedToShow = boatSpeeds.getOrDefault(boatId, 0.0);
            double maxSpeedToShow = onIce ? maxIceSpeed : maxOffIceSpeed;
            String message = String.format("%s §f| Скорость: %.2f / %.2f", status, speedToShow, maxSpeedToShow);
            player.sendActionBar(message);
        }
    }
}