package com.transport.pricing;

import com.transport.mobility.contracts.v1.Offer.Mode;
import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.pricing.PricingRules.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class QuoteStore {
    private final JdbcTemplate db;
    private final JsonMapper json;

    QuoteStore(JdbcTemplate db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    List<Rule> rules() {
        return db.query("SELECT id, version, settings FROM regle ORDER BY id", (row, index) ->
                new Rule(row.getObject("id", UUID.class), row.getInt("version"), json.readValue(row.getString("settings"), RuleInput.class)));
    }

    void saveRule(UUID id, RuleInput settings) {
        db.update("""
                INSERT INTO regle(id, settings) VALUES (?, ?::jsonb)
                ON CONFLICT(id) DO UPDATE SET settings = excluded.settings, version = regle.version + 1
                """, id, json.writeValueAsString(settings));
    }

    List<Promotion> promotions() {
        return db.query("""
                SELECT p.code, p.version, p.settings || jsonb_build_object('modes',
                    coalesce((SELECT jsonb_agg(m.mode ORDER BY m.mode) FROM promotion_mode m WHERE m.code=p.code), '[]'::jsonb)) AS settings
                FROM promotion p ORDER BY p.code
                """, (row, index) -> new Promotion(row.getString("code"), row.getInt("version"),
                json.readValue(row.getString("settings"), PromotionInput.class)));
    }

    void savePromotion(String code, PromotionInput settings) {
        db.update("""
                INSERT INTO promotion(code, settings) VALUES (?, ?::jsonb)
                ON CONFLICT(code) DO UPDATE SET settings = excluded.settings, version = promotion.version + 1
                """, code, json.writeValueAsString(settings));
        db.update("DELETE FROM promotion_mode WHERE code = ?", code);
        for (Mode mode : settings.modes()) {
            db.update("INSERT INTO promotion_mode(code, mode) VALUES (?, ?)", code, mode.name());
        }
    }

    void deactivate(String table, String key, Object value) {
        db.update("UPDATE " + table + " SET settings=jsonb_set(settings, '{active}', 'false'), version=version+1 WHERE " + key + "=?", value);
    }

    void insert(String owner, Quote quote) {
        db.update("""
                INSERT INTO devis(id,user_id,status,created_at,expires_at,total_minor,itinerary_hash,snapshot)
                VALUES (?,?,'ISSUED',?,?,?,?,?::jsonb)
                """, quote.id(), owner, Timestamp.from(quote.createdAt()), Timestamp.from(quote.expiresAt()),
                quote.price().total().amountMinor(), quote.itineraryHash(), json.writeValueAsString(quote.price()));
        for (Line line : quote.price().lines()) {
            db.update("INSERT INTO ligne_devis(id,devis_id,allocation_id,snapshot) VALUES (?,?,?,?::jsonb)",
                    line.lineId(), quote.id(), line.allocationId(), json.writeValueAsString(line));
            for (Adjustment adjustment : line.adjustments()) {
                db.update("INSERT INTO devis_ajustement(ligne_id,snapshot) VALUES (?,?::jsonb)",
                        line.lineId(), json.writeValueAsString(adjustment));
            }
        }
    }

    Optional<Quote> find(UUID id, String owner, boolean lock) {
        return db.query("SELECT * FROM devis WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> new Quote(rs.getObject("id", UUID.class), rs.getObject("reservation_id", UUID.class),
                        Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(), rs.getString("itinerary_hash"), rs.getInt("version"),
                        json.readValue(rs.getString("snapshot"), Price.class)), id, owner).stream().findFirst();
    }

    void claim(UUID id, UUID reservation) {
        db.update("UPDATE devis SET reservation_id=?, status='CLAIMED', next_reconcile_at=now()+interval '30 seconds', version=version+1 WHERE id=?", reservation, id);
    }

    String itineraryHash(Basket basket) {
        return hash(json.writeValueAsString(basket.itinerary()));
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
