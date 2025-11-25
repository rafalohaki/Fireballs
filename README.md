# 🔥 Custom Fireballs

**Folia 1.21.8+ Plugin** - Strzelaj kustomowymi kulami ognia z Fire Charge!

## 🎯 Jak używać

1. Weź **Fire Charge** do ręki
2. Kliknij prawym przyciskiem myszy
3. Wystrzel kule ognia z konfigurowalną eksplozją

## ⚙️ Konfiguracja

```yaml
# Siła eksplozji (TNT = 4.0)
explosion-power: 4.0

# Czy podpalać bloki
set-fire: true

# Czy niszczyć bloki
break-blocks: false

# Cooldown (sekundy)
cooldown-seconds: 3

# Auto-zmiana nazwy Fire Charge → "Fireball"
rename-fire-charge: true
custom-name: "<gold>Fireball</gold>"
```

## 🚀 Funkcje

- ✅ **Event-based system** - zero lagu, brak ciągłego skanowania
- ✅ **Thread-safe na Folia** - wszystkie operacje na właściwych wątkach
- ✅ **Auto-rename** - Fire Charge automatycznie zmienia nazwę na "Fireball"
- ✅ **Konfigurowalne eksplozje** - siła, podpalanie, niszczenie bloków
- ✅ **Cooldown system** - zapobiega spamowaniu
- ✅ **Optimized performance** - cached config, zero I/O w runtime

## 📋 Wymagania

- **Java 21**
- **Folia 1.21.8+**
- **PacketEvents 2.10.1+**

## 📦 Instalacja

1. Pobierz `Fireballs-1.0-SNAPSHOT.jar`
2. Umieść w `plugins/`
3. Zrestartuj serwer
4. Skonfiguruj `plugins/Fireballs/config.yml`

## 🔧 Auto-rename system

Fire Charge automatycznie zmienia nazwę na "Fireball" gdy:
- 🛠️ **Skraftujesz** je
- 📦 **Podniesiesz** z ziemi  
- 🎒 **Otworzysz** skrzynię
- 🔄 **Przesuniesz** w ekwipunku

## 🛡️ Thread Safety

Plugin jest w 100% kompatybilny z Folia:
- Brak `BukkitScheduler`
- `folia-supported: true`
- `ConcurrentHashMap` dla cooldownów
- Memory leak prevention

## 📄 Licencja

[MIT](LICENSE)