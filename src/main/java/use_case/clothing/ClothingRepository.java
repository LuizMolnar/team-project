package use_case.clothing;

import entity.ClothingItem;
import java.util.List;

/** Clothing use cases depend on this port. Failed writes must not partially change data. */
public interface ClothingRepository {
    List<ClothingItem> getClothing();
    void saveClothing(ClothingItem item);
    void deleteClothing(String clothingId);
}
