# Custom Fireballs Plugin

Plugin dla Folia 1.21.8+ umożliwiający tworzenie kustomowych kul ognia bez niszczenia bloków.

## Funkcje

- ✅ **System oparty na eventach** - brak ciągłego skanowania, wszystko działa na eventach
- ✅ **Thread-safe na Folia** - wszystkie operacje wykonywane na właściwych wątkach regionów
- ✅ **PersistentDataContainer** - bezpieczne tagowanie kul ognia
- ✅ **Kustomowe eksplozje** - knockback i damage z konfigurowalnymi efektami
- ✅ **Konfiguracja** - podpalanie bloków, siła eksplozji, niszczenie bloków i cooldown
- ✅ **Cooldown system** - zapobiega spamowaniu kul ognia (ConcurrentHashMap dla Folia)

## Jak używać

1. Weź `STICK` (Stick/Pałka) do ręki
2. Kliknij prawym przyciskiem myszy (działa w powietrzu i na blokach)
3. Kula ognia zostanie wystrzelona przed tobą
4. Po trafieniu w cel/blok - eksplozja z konfigurowalnymi efektami

**Uwaga:** Używamy `STICK` jako trigger item, ponieważ niezawodnie wysyła RIGHT_CLICK_AIR events z dowolnej odległości.

**Domyślnie:** Eksplozja podpala bloki, ale ich nie niszczy

**Cooldown:** Jeśli spróbujesz użyć zbyt szybko, otrzymasz czerwony komunikat: *"Poczekaj jeszcze Xs przed następnym użyciem!"*

## Techniczne szczegóły

### Architektura

Plugin wykorzystuje:
- `PlayerInteractEvent` - do wykrywania użycia Stick (działa w powietrzu)
- `ProjectileHitEvent` - do obsługi kolizji kuli
- `ExplosionPrimeEvent` - do blokowania domyślnej eksplozji
- `PersistentDataContainer` - do tagowania kul ognia

### Thread Safety w Folia

Wszystkie eventy są automatycznie wywoływane na wątkach regionów, które posiadają:
- Gracza (PlayerInteractEvent)
- Encję (ProjectileHitEvent, ExplosionPrimeEvent)

Dlatego nie trzeba ręcznie przełączać schedulerów dla typowego event-handlu.

**Cooldown tracking:** Używamy `ConcurrentHashMap<UUID, Long>` dla thread-safe zarządzania cooldownami w środowisku wielowątkowym Folia.

## Konfiguracja

Plik: `config.yml`

```yaml
# Czy kule ognia mają podpalać bloki po eksplozji
set-fire: true          # domyślnie: true (włączone)

# Siła eksplozji (podobna do TNT przy 4.0)
explosion-power: 4.0    # domyślnie: 4.0

# Czy eksplozja ma niszczyć bloki  
break-blocks: false     # domyślnie: false (wyłączone)

# Cooldown między użyciami (w sekundach)
# 0 = brak cooldownu
cooldown-seconds: 3     # domyślnie: 3 sekundy
```

**Ustawienia domyślne:**
- ✅ Podpalanie bloków: **WŁĄCZONE**
- ✅ Siła eksplozji: **4.0** (jak TNT)
- ❌ Niszczenie bloków: **WYŁĄCZONE**
- ⏱️ Cooldown: **3 sekundy**

### Parametry stałe

```java
VELOCITY_MULTIPLIER = 1.5       // Prędkość początkowa kuli
SPAWN_OFFSET = 1.5              // Odległość spawnu przed graczem (bloki)
```

## Kompilacja

```bash
mvnd clean package
```

Wynikowy JAR: `target/Fireballs-1.0-SNAPSHOT.jar`

## Wymagania

- Java 21
- Folia 1.21.8+ (Paper API)
- Maven (preferowany mvnd)

## plugin.yml

```yaml
folia-supported: true
```

Ta flaga jest **wymagana** - bez niej Folia nie załaduje pluginu.

## Struktura projektu

```
src/main/java/org/rafalohaki/fireballs/
├── Fireballs.java                          # Główna klasa pluginu
├── Keys.java                               # Helper dla PersistentDataContainer
└── listener/
    └── CustomFireballListener.java        # Event handler
```

## Zgodność z Folia

Plugin jest w pełni zgodny z modelem wątków Folia:
- ✅ Brak użycia `BukkitScheduler`
- ✅ Brak założenia "głównego wątku"
- ✅ Wszystkie operacje na właściwych wątkach regionów
- ✅ `folia-supported: true` w plugin.yml
- ✅ **Memory leak prevention** - cleanup w `onDisable()`
- ✅ **Auto-cleanup** - `PlayerQuitEvent` usuwa cooldowny
- ✅ **Thread-safe cooldowns** - `ConcurrentHashMap`
- ✅ **Brak długotrwałych referencji** - tylko UUID

📋 Zobacz [FOLIA_CHECKLIST.md](FOLIA_CHECKLIST.md) dla pełnej dokumentacji compliance

## Licencja

Open source
