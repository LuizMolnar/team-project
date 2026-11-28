package app;

import data_access.SqliteOutfitRepository;
import interface_adapter.outfit.*;
import use_case.outfit.*;
import view.OutfitView;
import javax.swing.*;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SqliteOutfitRepository repository = new SqliteOutfitRepository(Path.of("data", "wardrobe.db"));
            OutfitViewModel model = new OutfitViewModel();
            OutfitPresenter presenter = new OutfitPresenter(model);
            OutfitInputBoundary interactor = new OutfitInteractor(repository, presenter);
            OutfitController controller = new OutfitController(interactor, model);
            JFrame frame = new JFrame("Wear House");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new OutfitView(controller, model));
            frame.setSize(1000, 700);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            controller.load(null);
        });
    }
}
