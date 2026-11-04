package app.ktmnav.backend;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Manages saved bikes, active bike selection, provisional pairing rollback,
 * and persisted session keys in SharedPreferences 'ktmnav'.
 * Automatically migrates legacy 'GEN1' labels to canonical 'GEN3' per original TurnIt architecture.
 */
public final class BikeManager {

    private static final String PREFS_NAME = "ktmnav";
    private static final String KEY_ACTIVE_ADDRESS = "active_bike_address";
    private static final String KEY_ACTIVE_NAME = "active_bike_name";
    private static final String KEY_ACTIVE_GEN = "active_bike_gen";
    private static final String KEY_INACTIVE_ADDRESSES = "inactive_bike_addresses";
    private static final String KEY_BIKE_NAME_PREFIX = "bike_name_";
    private static final String KEY_BIKE_GEN_PREFIX = "bike_gen_";
    private static final String KEY_BIKE_MODEL_PREFIX = "bike_model_";
    private static final String KEY_PAIRED_BEFORE_PREFIX = "paired_before_";
    private static final String KEY_SESSION_KEYS_PREFIX = "session_keys_";

    // Provisional pairing rollback keys matching original OpenDash / TurnIt behavior
    private static final String KEY_PROVISIONAL_RESTORE_ADDRESS = "provisional_restore_address";
    private static final String KEY_PROVISIONAL_RESTORE_NAME = "provisional_restore_name";
    private static final String KEY_PROVISIONAL_RESTORE_GEN = "provisional_restore_gen";

    private final SharedPreferences prefs;

    public BikeManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        migrateLegacyGenerations();
        if (hasProvisionalRestore()) {
            AppLog.i("BikeManager", "Found unconfirmed provisional pairing on startup - rolling back to previous known bond");
            rollbackProvisionalRestore();
        }
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    /**
     * Transactionally migrates legacy 'GEN1' preferences to canonical 'GEN3' without
     * losing bike name, address, paired status, or cryptographic session keys.
     */
    public synchronized void migrateLegacyGenerations() {
        SharedPreferences.Editor editor = null;
        Map<String, ?> all = prefs.getAll();

        String activeGen = prefs.getString(KEY_ACTIVE_GEN, null);
        if ("GEN1".equalsIgnoreCase(activeGen)) {
            editor = prefs.edit();
            editor.putString(KEY_ACTIVE_GEN, BikeGeneration.CANONICAL_GEN3);
        }

        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(KEY_BIKE_GEN_PREFIX)) {
                Object val = entry.getValue();
                if (val instanceof String && "GEN1".equalsIgnoreCase((String) val)) {
                    if (editor == null) {
                        editor = prefs.edit();
                    }
                    editor.putString(key, BikeGeneration.CANONICAL_GEN3);
                }
            }
        }

        String restoreGen = prefs.getString(KEY_PROVISIONAL_RESTORE_GEN, null);
        if ("GEN1".equalsIgnoreCase(restoreGen)) {
            if (editor == null) {
                editor = prefs.edit();
            }
            editor.putString(KEY_PROVISIONAL_RESTORE_GEN, BikeGeneration.CANONICAL_GEN3);
        }

        if (editor != null) {
            editor.apply();
            AppLog.i("BikeManager", "Migrated legacy 'GEN1' bike configurations to canonical 'GEN3'");
        }
    }

    public synchronized Bridge.Bike getActiveBike() {
        String address = prefs.getString(KEY_ACTIVE_ADDRESS, null);
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        String name = prefs.getString(KEY_ACTIVE_NAME, address);
        String gen = prefs.getString(KEY_ACTIVE_GEN, BikeGeneration.CANONICAL_GEN3);
        return new Bridge.Bike(address, name, BikeGeneration.normalize(gen), getBikeModel(address));
    }

    /** Rider-chosen model or style text for the Garage silhouette, or null when never set. */
    public synchronized String getBikeModel(String address) {
        if (address == null) return null;
        String model = prefs.getString(KEY_BIKE_MODEL_PREFIX + address.trim(), null);
        return model == null || model.trim().isEmpty() ? null : model.trim();
    }

    public synchronized void setBikeModel(String address, String model) {
        if (address == null) return;
        String key = KEY_BIKE_MODEL_PREFIX + address.trim();
        if (model == null || model.trim().isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, model.trim()).apply();
        }
    }

    public synchronized List<Bridge.Bike> getAllBikes() {
        List<Bridge.Bike> result = new ArrayList<>();
        Bridge.Bike active = getActiveBike();
        if (active != null) {
            result.add(active);
        }
        String inactive = prefs.getString(KEY_INACTIVE_ADDRESSES, "");
        if (!inactive.isEmpty()) {
            for (String addr : inactive.split(",")) {
                String cleanAddr = addr.trim();
                if (!cleanAddr.isEmpty() && (active == null || !cleanAddr.equalsIgnoreCase(active.address))) {
                    String name = prefs.getString(KEY_BIKE_NAME_PREFIX + cleanAddr, cleanAddr);
                    String gen = prefs.getString(KEY_BIKE_GEN_PREFIX + cleanAddr, BikeGeneration.CANONICAL_GEN3);
                    result.add(new Bridge.Bike(cleanAddr, name, BikeGeneration.normalize(gen), getBikeModel(cleanAddr)));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized void saveBike(String address, String name, String generation) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        String cleanAddr = address.trim();
        String cleanName = (name != null && !name.trim().isEmpty()) ? name.trim() : cleanAddr;
        String cleanGen = BikeGeneration.normalize(generation);

        Bridge.Bike currentActive = getActiveBike();
        List<String> inactives = getInactiveAddressList();
        SharedPreferences.Editor editor = prefs.edit();

        if (currentActive != null && !currentActive.address.equalsIgnoreCase(cleanAddr)) {
            // Push previous active to inactive
            String oldAddr = currentActive.address.trim();
            String oldName = (currentActive.name != null && !currentActive.name.trim().isEmpty())
                    ? currentActive.name.trim() : oldAddr;
            String oldGen = BikeGeneration.normalize(currentActive.generation);
            inactives.removeIf(s -> s.equalsIgnoreCase(oldAddr));
            inactives.add(0, oldAddr);
            editor.putString(KEY_BIKE_NAME_PREFIX + oldAddr, oldName);
            editor.putString(KEY_BIKE_GEN_PREFIX + oldAddr, oldGen);
        }

        // Remove from inactive list if present (case-insensitive)
        inactives.removeIf(s -> s.equalsIgnoreCase(cleanAddr));

        // Set as new active
        editor.putString(KEY_ACTIVE_ADDRESS, cleanAddr);
        editor.putString(KEY_ACTIVE_NAME, cleanName);
        editor.putString(KEY_ACTIVE_GEN, cleanGen);
        editor.putString(KEY_BIKE_NAME_PREFIX + cleanAddr, cleanName);
        editor.putString(KEY_BIKE_GEN_PREFIX + cleanAddr, cleanGen);
        editor.putString(KEY_INACTIVE_ADDRESSES, String.join(",", inactives));
        editor.apply();

        AppLog.i("BikeManager", "Saved active bike " + LogRedact.mac(cleanAddr) + " (" + cleanGen + ")");
    }

    public synchronized void selectBike(String address) {
        if (address == null) {
            return;
        }
        String cleanAddr = address.trim();
        Bridge.Bike active = getActiveBike();
        if (active != null && active.address.equalsIgnoreCase(cleanAddr)) {
            return;
        }
        String name = prefs.getString(KEY_BIKE_NAME_PREFIX + cleanAddr, cleanAddr);
        String gen = prefs.getString(KEY_BIKE_GEN_PREFIX + cleanAddr, BikeGeneration.CANONICAL_GEN3);
        saveBike(cleanAddr, name, gen);
    }

    public synchronized void renameBike(String address, String newName) {
        if (address == null || newName == null) {
            return;
        }
        String cleanAddr = address.trim();
        String cleanName = newName.trim();
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_BIKE_NAME_PREFIX + cleanAddr, cleanName);

        Bridge.Bike active = getActiveBike();
        if (active != null && active.address.equalsIgnoreCase(cleanAddr)) {
            editor.putString(KEY_ACTIVE_NAME, cleanName);
        }
        editor.apply();
        AppLog.i("BikeManager", "Renamed bike " + LogRedact.mac(cleanAddr));
    }

    public synchronized void forgetBike(String address) {
        if (address == null) {
            return;
        }
        String cleanAddr = address.trim();
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(KEY_BIKE_NAME_PREFIX + cleanAddr);
        editor.remove(KEY_BIKE_GEN_PREFIX + cleanAddr);
        editor.remove(KEY_BIKE_MODEL_PREFIX + cleanAddr);
        editor.remove(KEY_PAIRED_BEFORE_PREFIX + cleanAddr);
        editor.remove(KEY_SESSION_KEYS_PREFIX + cleanAddr);

        removeInactiveAddress(cleanAddr);

        Bridge.Bike active = getActiveBike();
        if (active != null && active.address.equalsIgnoreCase(cleanAddr)) {
            editor.remove(KEY_ACTIVE_ADDRESS);
            editor.remove(KEY_ACTIVE_NAME);
            editor.remove(KEY_ACTIVE_GEN);

            // Promote next inactive bike if available
            List<String> inactives = getInactiveAddressList();
            if (!inactives.isEmpty()) {
                String nextAddr = inactives.remove(0);
                String nextName = prefs.getString(KEY_BIKE_NAME_PREFIX + nextAddr, nextAddr);
                String nextGen = prefs.getString(KEY_BIKE_GEN_PREFIX + nextAddr, BikeGeneration.CANONICAL_GEN3);
                editor.putString(KEY_ACTIVE_ADDRESS, nextAddr);
                editor.putString(KEY_ACTIVE_NAME, nextName);
                editor.putString(KEY_ACTIVE_GEN, BikeGeneration.normalize(nextGen));
                editor.putString(KEY_INACTIVE_ADDRESSES, String.join(",", inactives));
            }
        }
        editor.apply();
        AppLog.i("BikeManager", "Forgot bike " + LogRedact.mac(cleanAddr));
    }

    public synchronized void addInactiveBike(String address, String name, String gen) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        String cleanAddr = address.trim();
        String cleanName = (name != null && !name.trim().isEmpty()) ? name.trim() : cleanAddr;
        String cleanGen = BikeGeneration.normalize(gen);

        List<String> list = getInactiveAddressList();
        list.remove(cleanAddr);
        list.add(0, cleanAddr);
        prefs.edit()
                .putString(KEY_INACTIVE_ADDRESSES, String.join(",", list))
                .putString(KEY_BIKE_NAME_PREFIX + cleanAddr, cleanName)
                .putString(KEY_BIKE_GEN_PREFIX + cleanAddr, cleanGen)
                .apply();
    }

    public synchronized void removeInactiveBike(String address) {
        if (address == null) return;
        removeInactiveAddress(address.trim());
    }

    public synchronized void replaceInactiveBike(String newActiveAddr, String oldActiveAddr, String oldActiveName, String oldActiveGen) {
        removeInactiveBike(newActiveAddr);
        if (oldActiveAddr != null && !oldActiveAddr.trim().isEmpty()) {
            addInactiveBike(oldActiveAddr, oldActiveName, oldActiveGen);
        }
    }

    public synchronized List<String> getInactiveBikeAddresses() {
        return getInactiveAddressList();
    }

    public synchronized String getBikeName(String address) {
        if (address == null) return "";
        String clean = address.trim();
        return prefs.getString(KEY_BIKE_NAME_PREFIX + clean, clean);
    }

    public synchronized BikeGeneration getBikeGeneration(String address) {
        if (address == null) return BikeGeneration.GEN3;
        String clean = address.trim();
        String gen = prefs.getString(KEY_BIKE_GEN_PREFIX + clean, BikeGeneration.CANONICAL_GEN3);
        return BikeGeneration.from(gen);
    }

    private void removeInactiveAddress(String address) {
        List<String> list = getInactiveAddressList();
        if (list.remove(address)) {
            prefs.edit().putString(KEY_INACTIVE_ADDRESSES, String.join(",", list)).apply();
        }
    }

    private List<String> getInactiveAddressList() {
        String str = prefs.getString(KEY_INACTIVE_ADDRESSES, "");
        List<String> list = new ArrayList<>();
        if (!str.isEmpty()) {
            for (String s : str.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    list.add(trimmed);
                }
            }
        }
        return list;
    }

    // --- Provisional Pairing Rollback Methods ---

    public synchronized boolean hasProvisionalRestore() {
        return prefs.contains(KEY_PROVISIONAL_RESTORE_ADDRESS)
                && prefs.getString(KEY_PROVISIONAL_RESTORE_ADDRESS, null) != null;
    }

    public synchronized String getProvisionalRestoreAddress() {
        return prefs.getString(KEY_PROVISIONAL_RESTORE_ADDRESS, null);
    }

    public synchronized String getProvisionalRestoreName() {
        return prefs.getString(KEY_PROVISIONAL_RESTORE_NAME, null);
    }

    public synchronized String getProvisionalRestoreGen() {
        return prefs.getString(KEY_PROVISIONAL_RESTORE_GEN, BikeGeneration.CANONICAL_GEN3);
    }

    public synchronized void setProvisionalRestore(String address, String name, String gen) {
        if (address == null) {
            clearProvisionalRestore();
            return;
        }
        prefs.edit()
                .putString(KEY_PROVISIONAL_RESTORE_ADDRESS, address)
                .putString(KEY_PROVISIONAL_RESTORE_NAME, name != null ? name : address)
                .putString(KEY_PROVISIONAL_RESTORE_GEN, BikeGeneration.normalize(gen))
                .apply();
    }

    public synchronized void clearProvisionalRestore() {
        prefs.edit()
                .remove(KEY_PROVISIONAL_RESTORE_ADDRESS)
                .remove(KEY_PROVISIONAL_RESTORE_NAME)
                .remove(KEY_PROVISIONAL_RESTORE_GEN)
                .apply();
    }

    public synchronized void rollbackProvisionalRestore() {
        if (!hasProvisionalRestore()) {
            return;
        }
        String addr = getProvisionalRestoreAddress();
        String name = getProvisionalRestoreName();
        String gen = getProvisionalRestoreGen();

        Bridge.Bike current = getActiveBike();
        List<String> inactives = getInactiveAddressList();

        if (addr != null && !addr.trim().isEmpty()) {
            String cleanRestoredAddr = addr.trim();
            inactives.removeIf(s -> s.equalsIgnoreCase(cleanRestoredAddr));
        }

        SharedPreferences.Editor editor = prefs.edit();

        if (current != null && (addr == null || addr.trim().isEmpty() || !current.address.trim().equalsIgnoreCase(addr.trim()))) {
            String currentAddr = current.address.trim();
            String currentName = (current.name != null && !current.name.trim().isEmpty())
                    ? current.name.trim() : currentAddr;
            String currentGen = BikeGeneration.normalize(current.generation);
            inactives.removeIf(s -> s.equalsIgnoreCase(currentAddr));
            inactives.add(0, currentAddr);
            editor.putString(KEY_BIKE_NAME_PREFIX + currentAddr, currentName);
            editor.putString(KEY_BIKE_GEN_PREFIX + currentAddr, currentGen);
        }

        editor.putString(KEY_INACTIVE_ADDRESSES, String.join(",", inactives));
        if (addr != null && !addr.trim().isEmpty()) {
            String cleanAddr = addr.trim();
            editor.putString(KEY_ACTIVE_ADDRESS, cleanAddr);
            editor.putString(KEY_ACTIVE_NAME, (name != null && !name.trim().isEmpty()) ? name.trim() : cleanAddr);
            editor.putString(KEY_ACTIVE_GEN, BikeGeneration.normalize(gen));
            editor.putString(KEY_BIKE_NAME_PREFIX + cleanAddr, (name != null && !name.trim().isEmpty()) ? name.trim() : cleanAddr);
            editor.putString(KEY_BIKE_GEN_PREFIX + cleanAddr, BikeGeneration.normalize(gen));
        } else {
            editor.remove(KEY_ACTIVE_ADDRESS);
            editor.remove(KEY_ACTIVE_NAME);
            editor.remove(KEY_ACTIVE_GEN);
        }
        editor.remove(KEY_PROVISIONAL_RESTORE_ADDRESS);
        editor.remove(KEY_PROVISIONAL_RESTORE_NAME);
        editor.remove(KEY_PROVISIONAL_RESTORE_GEN);
        editor.apply();

        AppLog.i("BikeManager", "Rolled back provisional pairing to " + LogRedact.mac(addr));
    }

    // --- Pairing Status and Session Key Persistence ---

    public boolean hasPairedBefore(String address) {
        if (address == null) return false;
        return prefs.getBoolean(KEY_PAIRED_BEFORE_PREFIX + address.trim(), false);
    }

    public void markPairedBefore(String address) {
        if (address == null) return;
        prefs.edit().putBoolean(KEY_PAIRED_BEFORE_PREFIX + address.trim(), true).apply();
    }

    public void clearPairedBefore(String address) {
        if (address == null) return;
        String clean = address.trim();
        prefs.edit()
                .remove(KEY_PAIRED_BEFORE_PREFIX + clean)
                .remove(KEY_SESSION_KEYS_PREFIX + clean)
                .apply();
    }

    public void storeSessionKeys(String address, List<byte[]> keys) {
        if (address == null || keys == null) return;
        StringBuilder sb = new StringBuilder();
        for (byte[] k : keys) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(BccuCrypto.INSTANCE.hex(k));
        }
        prefs.edit().putString(KEY_SESSION_KEYS_PREFIX + address.trim(), sb.toString()).apply();
    }

    public List<byte[]> loadSessionKeys(String address) {
        if (address == null) return null;
        String val = prefs.getString(KEY_SESSION_KEYS_PREFIX + address.trim(), null);
        if (val == null || val.isEmpty()) {
            return null;
        }
        List<byte[]> keys = new ArrayList<>();
        for (String hexStr : val.split(",")) {
            keys.add(BccuCrypto.INSTANCE.unhex(hexStr));
        }
        return keys;
    }
}
