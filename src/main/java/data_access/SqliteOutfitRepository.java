package data_access;

import entity.*;
import use_case.clothing.ClothingRepository;
import use_case.outfit.OutfitRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** SQLite store shared by the outfit and clothing ports. One connection, one file. */
public final class SqliteOutfitRepository implements OutfitRepository, ClothingRepository, AutoCloseable {
    private static final String[] SCHEMA = {
            "CREATE TABLE IF NOT EXISTS clothing_item ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, image_path TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS clothing_feature ("
                    + "clothing_id TEXT NOT NULL REFERENCES clothing_item(id) ON DELETE CASCADE, "
                    + "position INTEGER NOT NULL, feature TEXT NOT NULL, "
                    + "PRIMARY KEY (clothing_id, position))",
            "CREATE TABLE IF NOT EXISTS tag ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL COLLATE NOCASE UNIQUE)",
            "CREATE TABLE IF NOT EXISTS outfit ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS outfit_clothing ("
                    + "outfit_id TEXT NOT NULL REFERENCES outfit(id) ON DELETE CASCADE, "
                    + "clothing_id TEXT NOT NULL REFERENCES clothing_item(id), "
                    + "position INTEGER NOT NULL, PRIMARY KEY (outfit_id, clothing_id))",
            "CREATE TABLE IF NOT EXISTS outfit_tag ("
                    + "outfit_id TEXT NOT NULL REFERENCES outfit(id) ON DELETE CASCADE, "
                    + "tag_id TEXT NOT NULL REFERENCES tag(id), "
                    + "position INTEGER NOT NULL, PRIMARY KEY (outfit_id, tag_id))"
    };

    private final Connection connection;

    public SqliteOutfitRepository(Path databaseFile) {
        Connection opened = null;
        try {
            Path parent = databaseFile.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            try (Statement statement = opened.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                try (ResultSet keys = statement.executeQuery("PRAGMA foreign_keys")) {
                    if (!keys.next() || keys.getInt(1) != 1) {
                        throw new IllegalStateException("SQLite foreign keys are off.");
                    }
                }
                for (String sql : SCHEMA) statement.execute(sql);
            }
            connection = opened;
            opened = null;
        } catch (Exception ex) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (SQLException ignored) { }
            }
            throw new IllegalStateException("Could not open the wardrobe database.", ex);
        }
    }

    @Override
    public synchronized List<ClothingItem> getClothing() {
        try {
            List<String[]> stored = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT id, name, type, image_path FROM clothing_item ORDER BY rowid")) {
                while (rows.next()) {
                    stored.add(new String[] {
                            rows.getString("id"), rows.getString("name"),
                            rows.getString("type"), rows.getString("image_path")
                    });
                }
            }
            List<ClothingItem> items = new ArrayList<>();
            for (String[] row : stored) {
                items.add(new ClothingItem(row[0], row[1], ClothingType.valueOf(row[2]), row[3],
                        queryStrings(
                                "SELECT feature FROM clothing_feature WHERE clothing_id = ? ORDER BY position",
                                row[0])));
            }
            return items;
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read clothing from the wardrobe database.", ex);
        }
    }

    @Override
    public synchronized List<Outfit> getOutfits() {
        try {
            List<String[]> stored = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT id, name FROM outfit ORDER BY rowid")) {
                while (rows.next()) stored.add(new String[] {rows.getString("id"), rows.getString("name")});
            }
            List<Outfit> outfits = new ArrayList<>();
            for (String[] row : stored) {
                outfits.add(new Outfit(row[0], row[1],
                        queryStrings(
                                "SELECT clothing_id FROM outfit_clothing WHERE outfit_id = ? ORDER BY position",
                                row[0]),
                        queryStrings(
                                "SELECT tag_id FROM outfit_tag WHERE outfit_id = ? ORDER BY position",
                                row[0])));
            }
            return outfits;
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read outfits from the wardrobe database.", ex);
        }
    }

    @Override
    public synchronized List<Tag> getTags() {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id, name FROM tag ORDER BY rowid")) {
            List<Tag> tags = new ArrayList<>();
            while (rows.next()) tags.add(new Tag(rows.getString("id"), rows.getString("name")));
            return tags;
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not read tags from the wardrobe database.", ex);
        }
    }

    @Override
    public synchronized void saveOutfit(Outfit outfit) {
        write(() -> {
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO outfit (id, name) VALUES (?, ?) "
                            + "ON CONFLICT(id) DO UPDATE SET name = excluded.name")) {
                upsert.setString(1, outfit.getId());
                upsert.setString(2, outfit.getName());
                upsert.executeUpdate();
            }
            replaceLinks("DELETE FROM outfit_clothing WHERE outfit_id = ?",
                    "INSERT INTO outfit_clothing (outfit_id, clothing_id, position) VALUES (?, ?, ?)",
                    outfit.getId(), outfit.getClothingIds());
            replaceLinks("DELETE FROM outfit_tag WHERE outfit_id = ?",
                    "INSERT INTO outfit_tag (outfit_id, tag_id, position) VALUES (?, ?, ?)",
                    outfit.getId(), outfit.getTagIds());
        });
    }

    @Override
    public synchronized void saveTag(Tag tag) {
        write(() -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tag (id, name) VALUES (?, ?)")) {
                insert.setString(1, tag.getId());
                insert.setString(2, tag.getName());
                insert.executeUpdate();
            } catch (SQLException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("tag.name")) {
                    throw new IllegalArgumentException("A tag with this name already exists.");
                }
                throw ex;
            }
        });
    }

    @Override
    public synchronized void deleteOutfit(String outfitId) {
        write(() -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM outfit WHERE id = ?")) {
                delete.setString(1, outfitId);
                delete.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void saveClothing(ClothingItem item) {
        write(() -> {
            int updated;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE clothing_item SET name = ?, type = ?, image_path = ? WHERE id = ?")) {
                update.setString(1, item.getName());
                update.setString(2, item.getType().name());
                update.setString(3, item.getImagePath());
                update.setString(4, item.getId());
                updated = update.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO clothing_item (id, name, type, image_path) VALUES (?, ?, ?, ?)")) {
                    insert.setString(1, item.getId());
                    insert.setString(2, item.getName());
                    insert.setString(3, item.getType().name());
                    insert.setString(4, item.getImagePath());
                    insert.executeUpdate();
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM clothing_feature WHERE clothing_id = ?")) {
                delete.setString(1, item.getId());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO clothing_feature (clothing_id, position, feature) VALUES (?, ?, ?)")) {
                int position = 0;
                for (String feature : item.getExtractedFeatures()) {
                    insert.setString(1, item.getId());
                    insert.setInt(2, position++);
                    insert.setString(3, feature);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        });
    }

    @Override
    public synchronized void deleteClothing(String clothingId) {
        write(() -> {
            try (PreparedStatement exists = connection.prepareStatement(
                    "SELECT 1 FROM clothing_item WHERE id = ?")) {
                exists.setString(1, clothingId);
                try (ResultSet rows = exists.executeQuery()) {
                    if (!rows.next()) {
                        throw new IllegalArgumentException("This clothing item no longer exists.");
                    }
                }
            }
            List<String> outfitIds = queryStrings(
                    "SELECT outfit_id FROM outfit_clothing WHERE clothing_id = ?", clothingId);
            try (PreparedStatement detach = connection.prepareStatement(
                    "DELETE FROM outfit_clothing WHERE clothing_id = ?")) {
                detach.setString(1, clothingId);
                detach.executeUpdate();
            }
            List<String> emptyOutfits = new ArrayList<>();
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM outfit_clothing WHERE outfit_id = ?")) {
                for (String outfitId : outfitIds) {
                    count.setString(1, outfitId);
                    try (ResultSet rows = count.executeQuery()) {
                        rows.next();
                        if (rows.getInt(1) == 0) emptyOutfits.add(outfitId);
                    }
                }
            }
            try (PreparedStatement deleteOutfit = connection.prepareStatement(
                    "DELETE FROM outfit WHERE id = ?")) {
                for (String outfitId : emptyOutfits) {
                    deleteOutfit.setString(1, outfitId);
                    deleteOutfit.executeUpdate();
                }
            }
            try (PreparedStatement deleteItem = connection.prepareStatement(
                    "DELETE FROM clothing_item WHERE id = ?")) {
                deleteItem.setString(1, clothingId);
                deleteItem.executeUpdate();
            }
        });
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not close the wardrobe database.", ex);
        }
    }

    private void replaceLinks(String deleteSql, String insertSql, String outfitId, List<String> ids)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
            delete.setString(1, outfitId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            int position = 0;
            for (String id : ids) {
                insert.setString(1, outfitId);
                insert.setString(2, id);
                insert.setInt(3, position++);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private List<String> queryStrings(String sql, String id) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) values.add(rows.getString(1));
            }
        }
        return values;
    }

    private void write(SqlWork work) {
        try {
            connection.setAutoCommit(false);
            work.run();
            connection.commit();
        } catch (SQLException ex) {
            rollbackQuietly();
            throw new IllegalStateException("Could not write to the wardrobe database.", ex);
        } catch (RuntimeException ex) {
            rollbackQuietly();
            throw ex;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) { }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) { }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }
}
