package data_access;

import entity.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Dependency-free checks for the SQLite wardrobe. Throws AssertionError on any failure. */
public final class SqliteOutfitRepositoryChecks {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("wardrobe-checks");
        Path db = dir.resolve("wardrobe.db");
        try (SqliteOutfitRepository repo = new SqliteOutfitRepository(db)) {
            repo.saveClothing(new ClothingItem(
                    "c1", "Shirt", ClothingType.TOP, "shirts/white.png", List.of("white", "cotton")));
            repo.saveClothing(new ClothingItem(
                    "c2", "Jeans", ClothingType.BOTTOM, "pants/jeans.png", List.of("denim")));
            repo.saveClothing(new ClothingItem(
                    "c1", "White shirt", ClothingType.TOP, "shirts/white.png", List.of("cotton", "white")));
            repo.saveTag(new Tag("t1", "School"));
            repo.saveTag(new Tag("t2", "Summer"));
            boolean duplicate = false;
            try {
                repo.saveTag(new Tag("t3", "school"));
            } catch (IllegalArgumentException ex) {
                duplicate = true;
            }
            check(duplicate && repo.getTags().size() == 2, "Duplicate tag rejected");
            repo.saveOutfit(new Outfit("o1", "Class day", List.of("c2", "c1"), List.of("t2", "t1")));
            repo.saveOutfit(new Outfit("o2", "Shirt only", List.of("c1"), List.of()));
            repo.saveOutfit(new Outfit("o3", "Jeans only", List.of("c2"), List.of()));
            boolean rejected = false;
            try {
                repo.saveOutfit(new Outfit("o4", "Broken", List.of("missing"), List.of()));
            } catch (RuntimeException ex) {
                rejected = true;
            }
            check(rejected && repo.getOutfits().size() == 3, "Failed outfit write leaves previous outfits");
        }
        try (SqliteOutfitRepository repo = new SqliteOutfitRepository(db)) {
            List<ClothingItem> clothing = repo.getClothing();
            check(clothing.size() == 2, "Clothing count survived reopen");
            ClothingItem shirt = clothing.get(0);
            check(shirt.getId().equals("c1") && shirt.getName().equals("White shirt"),
                    "Clothing update survived reopen");
            check(shirt.getExtractedFeatures().equals(List.of("cotton", "white")),
                    "Feature order survived reopen");
            check(shirt.getType() == ClothingType.TOP, "Type survived reopen");
            check(shirt.getImagePath().equals("shirts/white.png"), "Image path survived reopen");
            ClothingItem jeans = clothing.get(1);
            check(jeans.getId().equals("c2") && jeans.getExtractedFeatures().equals(List.of("denim")),
                    "Clothing insertion order survived reopen");
            List<Outfit> outfits = repo.getOutfits();
            check(outfits.get(0).getId().equals("o1") && outfits.get(2).getId().equals("o3"),
                    "Outfit order survived reopen");
            check(outfits.get(0).getClothingIds().equals(List.of("c2", "c1")),
                    "Outfit clothing order survived reopen");
            check(outfits.get(0).getTagIds().equals(List.of("t2", "t1")),
                    "Outfit tag order survived reopen");

            repo.deleteClothing("c1");
            check(repo.getClothing().size() == 1 && repo.getClothing().get(0).getId().equals("c2"),
                    "Deleted clothing is gone");
            List<Outfit> remaining = repo.getOutfits();
            check(remaining.size() == 2 && remaining.stream().noneMatch(o -> o.getId().equals("o2")),
                    "Outfit left empty was removed");
            check(remaining.get(0).getId().equals("o1")
                            && remaining.get(0).getClothingIds().equals(List.of("c2"))
                            && remaining.get(0).getTagIds().equals(List.of("t2", "t1")),
                    "Mixed outfit dropped the deleted item");
            Outfit intact = remaining.get(1);
            check(intact.getId().equals("o3") && intact.getName().equals("Jeans only")
                            && intact.getClothingIds().equals(List.of("c2")) && intact.getTagIds().isEmpty(),
                    "Other outfit kept its remaining item");
            boolean missing = false;
            try {
                repo.deleteClothing("c1");
            } catch (IllegalArgumentException ex) {
                missing = true;
            }
            check(missing && repo.getClothing().size() == 1, "Missing clothing delete rejected");
        }
        System.out.println("PASS: 16 sqlite wardrobe checks.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
