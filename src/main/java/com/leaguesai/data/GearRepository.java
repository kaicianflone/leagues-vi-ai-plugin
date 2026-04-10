package com.leaguesai.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.leaguesai.data.model.GearItem;
import com.leaguesai.data.model.GearSlot;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Provides gear item lookup. Primary source: SQLite {@code items} table.
 * Fallback: classpath {@code /gear.json}.
 *
 * <p>Follows the same construction pattern as {@link DatabaseLoader} —
 * takes a {@code File dbFile} constructor argument, no Guice @Inject.
 */
@Singleton
@Slf4j
public class GearRepository {

    private static final Gson GSON = new Gson();

    private final Map<String, GearItem> itemsById;

    public GearRepository(File dbFile) {
        Map<String, GearItem> loaded = tryLoadFromDb(dbFile);
        if (loaded.isEmpty()) {
            loaded = tryLoadFromClasspath();
        }
        this.itemsById = loaded;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns the item with the given id, or {@code null} if not found. */
    public GearItem findById(String id) {
        return itemsById.get(id);
    }

    /** Returns all items whose slot matches the given {@link GearSlot}. */
    public List<GearItem> findBySlot(GearSlot slot) {
        return itemsById.values().stream()
                .filter(item -> slot.equals(item.getSlot()))
                .collect(Collectors.toList());
    }

    /** Returns all loaded items. */
    public List<GearItem> listAll() {
        return new ArrayList<>(itemsById.values());
    }

    // -------------------------------------------------------------------------
    // Loading strategies
    // -------------------------------------------------------------------------

    private Map<String, GearItem> tryLoadFromDb(File dbFile) {
        if (dbFile == null || !dbFile.exists()) {
            return Collections.emptyMap();
        }
        Map<String, GearItem> result = new LinkedHashMap<>();
        String sql = "SELECT id, wiki_item_id, name, slot, region, " +
                "attack_stab, attack_slash, attack_crush, attack_magic, attack_ranged, " +
                "defence_stab, defence_slash, defence_crush, defence_magic, defence_ranged, " +
                "melee_strength, magic_damage, ranged_strength, prayer_bonus, weight, " +
                "skill_requirements, wiki_url " +
                "FROM items";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                try {
                    GearItem item = parseDbRow(rs);
                    result.put(item.getId(), item);
                } catch (Exception rowErr) {
                    // Skip bad row, keep loading.
                }
            }
        } catch (Exception e) {
            // Table missing or DB connection failure — return empty.
            return Collections.emptyMap();
        }
        return result;
    }

    private Map<String, GearItem> tryLoadFromClasspath() {
        try (InputStream is = GearRepository.class.getResourceAsStream("/gear.json")) {
            if (is == null) {
                log.warn("GearRepository: /gear.json not found on classpath");
                return Collections.emptyMap();
            }
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                GearFileWrapper wrapper = GSON.fromJson(reader, GearFileWrapper.class);
                if (wrapper == null || wrapper.items == null) {
                    log.warn("GearRepository: /gear.json parsed to null");
                    return Collections.emptyMap();
                }
                Map<String, GearItem> result = new LinkedHashMap<>();
                for (GearItem item : wrapper.items) {
                    result.put(item.getId(), item);
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("GearRepository: failed to load /gear.json: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // -------------------------------------------------------------------------
    // DB row parser
    // -------------------------------------------------------------------------

    private GearItem parseDbRow(ResultSet rs) throws Exception {
        String skillReqJson = rs.getString("skill_requirements");
        Map<String, Integer> skillRequirements = null;
        if (skillReqJson != null && !skillReqJson.isEmpty()) {
            Type type = new TypeToken<Map<String, Integer>>() {}.getType();
            skillRequirements = GSON.fromJson(skillReqJson, type);
        }

        String slotStr = rs.getString("slot");
        GearSlot slot = slotStr != null ? GearSlot.valueOf(slotStr) : null;

        return GearItem.builder()
                .id(rs.getString("id"))
                .wikiItemId(rs.getInt("wiki_item_id"))
                .name(rs.getString("name"))
                .slot(slot)
                .region(rs.getString("region"))
                .attackStab(rs.getInt("attack_stab"))
                .attackSlash(rs.getInt("attack_slash"))
                .attackCrush(rs.getInt("attack_crush"))
                .attackMagic(rs.getInt("attack_magic"))
                .attackRanged(rs.getInt("attack_ranged"))
                .defenceStab(rs.getInt("defence_stab"))
                .defenceSlash(rs.getInt("defence_slash"))
                .defenceCrush(rs.getInt("defence_crush"))
                .defenceMagic(rs.getInt("defence_magic"))
                .defenceRanged(rs.getInt("defence_ranged"))
                .meleeStrength(rs.getInt("melee_strength"))
                .magicDamage(rs.getInt("magic_damage"))
                .rangedStrength(rs.getInt("ranged_strength"))
                .prayerBonus(rs.getInt("prayer_bonus"))
                .weight(rs.getDouble("weight"))
                .skillRequirements(skillRequirements)
                .wikiUrl(rs.getString("wiki_url"))
                .taskOverrides(Collections.emptyList())
                .build();
    }

    // -------------------------------------------------------------------------
    // Classpath JSON wrapper
    // -------------------------------------------------------------------------

    private static class GearFileWrapper {
        List<GearItem> items;
    }
}
