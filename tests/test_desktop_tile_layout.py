import importlib
from pathlib import Path
import sys
import unittest


class DesktopTileLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        omarchy_dir = str(Path(__file__).resolve().parents[1] / "omarchy")
        if omarchy_dir not in sys.path:
            sys.path.insert(0, omarchy_dir)
        cls.app_mod = importlib.import_module("remote-dev-terminal")
        if not cls.app_mod.Gtk.init_check([])[0]:
            raise unittest.SkipTest("GTK display not available in headless environment (run under xvfb)")

    def make_dummy_app(self, tile_layout="grid"):
        app_mod = self.app_mod

        class DummyApp:
            build_split = app_mod.App.build_split
            set_initial_positions = app_mod.App.set_initial_positions
            build_tile_layout = app_mod.App.build_tile_layout
            cycle_tile_layout = app_mod.App.cycle_tile_layout

            def __init__(self, layout):
                self.tile_layout = layout
                self.data = {"tile_layout": layout}
                self.grid = app_mod.Gtk.Box(orientation=app_mod.Gtk.Orientation.VERTICAL)
                self.layout_button = app_mod.Gtk.Button()
                self.persisted = False
                self.rebuilt = False

            def persist(self):
                self.persisted = True

            def rebuild_view(self):
                self.rebuilt = True

        return DummyApp(tile_layout)

    def test_tile_layout_cycle(self):
        app = self.make_dummy_app("grid")
        app.cycle_tile_layout(None)
        self.assertEqual(app.tile_layout, "columns")
        self.assertEqual(app.layout_button.get_label(), "⬓  切换布局 · 左右")

        app.cycle_tile_layout(None)
        self.assertEqual(app.tile_layout, "rows")
        self.assertEqual(app.layout_button.get_label(), "⬒  切换布局 · 上下")

        app.cycle_tile_layout(None)
        self.assertEqual(app.tile_layout, "grid")
        self.assertEqual(app.layout_button.get_label(), "⊞  切换布局 · 网格")

    def test_build_tile_layout_grid_creates_draggable_paneds(self):
        app = self.make_dummy_app("grid")
        Gtk = self.app_mod.Gtk
        cards = [Gtk.Box() for _ in range(4)]
        root = app.build_tile_layout(cards)

        # 4 cards in grid should create a vertical Paned with two horizontal Paned rows
        self.assertIsInstance(root, Gtk.Paned)
        self.assertEqual(root.get_orientation(), Gtk.Orientation.VERTICAL)
        self.assertTrue(root.get_style_context().has_class("tile-paned"))

        top_row = root.get_child1()
        bottom_row = root.get_child2()
        self.assertIsInstance(top_row, Gtk.Paned)
        self.assertIsInstance(bottom_row, Gtk.Paned)
        self.assertEqual(top_row.get_orientation(), Gtk.Orientation.HORIZONTAL)
        self.assertEqual(bottom_row.get_orientation(), Gtk.Orientation.HORIZONTAL)

        # Draggable boundary adjustment simulation
        # Adjust height by changing vertical root position
        root.set_position(350)
        self.assertEqual(root.get_position(), 350)

        # Adjust width by changing horizontal row position
        top_row.set_position(450)
        self.assertEqual(top_row.get_position(), 450)

    def test_build_tile_layout_columns_and_rows(self):
        Gtk = self.app_mod.Gtk
        # Columns layout (all in 1 row, horizontal split)
        app_cols = self.make_dummy_app("columns")
        cards = [Gtk.Box() for _ in range(3)]
        root_cols = app_cols.build_tile_layout(cards)
        self.assertIsInstance(root_cols, Gtk.Paned)
        self.assertEqual(root_cols.get_orientation(), Gtk.Orientation.HORIZONTAL)

        # Rows layout (all in 1 column, vertical split)
        app_rows = self.make_dummy_app("rows")
        cards_rows = [Gtk.Box() for _ in range(3)]
        root_rows = app_rows.build_tile_layout(cards_rows)
        self.assertIsInstance(root_rows, Gtk.Paned)
        self.assertEqual(root_rows.get_orientation(), Gtk.Orientation.VERTICAL)


if __name__ == "__main__":
    unittest.main()
