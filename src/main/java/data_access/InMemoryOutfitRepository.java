package data_access;

import entity.*;
import use_case.clothing.ClothingRepository;
import use_case.outfit.OutfitRepository;
import java.util.*;

/** Demo adapter only. All data is lost when the process exits. */
public final class InMemoryOutfitRepository implements OutfitRepository, ClothingRepository {
    private final Map<String, ClothingItem> clothing = new LinkedHashMap<>();
    private final Map<String, Outfit> outfits = new LinkedHashMap<>();
    private final Map<String, Tag> tags = new LinkedHashMap<>();

    public InMemoryOutfitRepository(List<ClothingItem> clothing) {
        for (ClothingItem item : clothing) this.clothing.put(item.getId(), item);
    }
    public List<ClothingItem> getClothing() { return new ArrayList<>(clothing.values()); }
    public List<Outfit> getOutfits() { return new ArrayList<>(outfits.values()); }
    public List<Tag> getTags() { return new ArrayList<>(tags.values()); }
    public void saveOutfit(Outfit outfit) { outfits.put(outfit.getId(), outfit); }
    public void saveTag(Tag tag) { tags.put(tag.getId(), tag); }
    public void deleteOutfit(String id) { outfits.remove(id); }

    public void saveClothing(ClothingItem item) { clothing.put(item.getId(), item); }

    public void deleteClothing(String id) {
        if (!clothing.containsKey(id)) {
            throw new IllegalArgumentException("This clothing item no longer exists.");
        }
        Map<String, Outfit> updated = new LinkedHashMap<>();
        for (Outfit outfit : outfits.values()) {
            if (!outfit.getClothingIds().contains(id)) {
                updated.put(outfit.getId(), outfit);
                continue;
            }
            List<String> remaining = new ArrayList<>();
            for (String clothingId : outfit.getClothingIds()) {
                if (!clothingId.equals(id)) remaining.add(clothingId);
            }
            if (!remaining.isEmpty()) {
                updated.put(outfit.getId(), new Outfit(
                        outfit.getId(), outfit.getName(), remaining, outfit.getTagIds()));
            }
        }
        clothing.remove(id);
        outfits.clear();
        outfits.putAll(updated);
    }
}
