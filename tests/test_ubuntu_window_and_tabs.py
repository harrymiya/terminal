import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

UBUNTU = Path(__file__).resolve().parents[1] / 'ubuntu'
sys.path.insert(0, str(UBUNTU))
try:
    spec = importlib.util.spec_from_file_location('ubuntu_desktop_regression', UBUNTU / 'remote-dev-terminal.py')
    app_module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(app_module)
except (ImportError, ValueError):
    app_module = None


@unittest.skipIf(app_module is None, 'GTK unavailable')
class UbuntuWindowAndTabsTest(unittest.TestCase):
    def setUp(self):
        if not app_module.Gtk.init_check([])[0]:
            self.skipTest('GTK display unavailable')
        with patch.object(app_module, 'load_config', return_value={'hosts': [], 'agents': []}):
            self.app = app_module.App()

    def tearDown(self):
        self.app.disconnect_by_func(app_module.Gtk.main_quit)
        self.app.destroy()

    def test_backdrop_keeps_text_readable(self):
        Gtk = app_module.Gtk
        self.app.data["theme"] = "classic"
        self.app.apply_theme()
        self.app.show_all()
        def labels(widget):
            if isinstance(widget, Gtk.Label):
                yield widget
            if isinstance(widget, Gtk.Container):
                for child in widget.get_children():
                    yield from labels(child)
        by_text = {label.get_text(): label for label in labels(self.app)}
        for text in ('REMOTE DEV', '＋主机', '自动同步：已关闭（本机设置）'):
            color = by_text[text].get_style_context().get_color(Gtk.StateFlags.BACKDROP)
            self.assertGreater(max(color.red, color.green, color.blue), 0.45, text)

    def test_repeated_mode_switch_preserves_header_and_quota(self):
        Gtk = app_module.Gtk
        terminal = app_module.Vte.Terminal()
        header = Gtk.Box()
        name = Gtk.Label(label='project · Codex')
        quota = Gtk.Label(label='余量 50%')
        header.pack_start(name, True, True, 0)
        header.pack_start(quota, False, False, 0)
        indicator, update = app_module.make_quota_indicator()
        update(85)
        header.pack_start(indicator, False, False, 0)
        self.app.sessions = [{'terminal': terminal, 'label': header, 'key': 'k', 'id': 'id', 'state': {}, 'quota_indicators': (indicator,)}]
        self.app.show_all()
        for tiled in (True, False, True, False, True, False):
            self.app.tiled = tiled
            self.app.rebuild_view()
            self.assertEqual(header.get_children(), [name, quota, indicator])
            self.assertEqual(quota.get_text(), '余量 50%')
            self.assertEqual(indicator.get_children()[1].get_text(), '85' if tiled else '85%')
            if not tiled:
                self.assertEqual(self.app.tabs.get_n_pages(), 1)
                self.assertFalse(self.app.tabs.get_show_tabs())
                self.assertTrue(self.app.instances_section.get_visible())
                self.assertEqual(len(self.app.instances_list.get_children()), 1)
                while Gtk.events_pending():
                    Gtk.main_iteration()
            else:
                self.assertFalse(self.app.instances_section.get_visible())

    def test_tab_mode_shows_instances_below_hosts_with_full_name_image_and_close(self):
        Gtk, Pango = app_module.Gtk, app_module.Pango
        self.app.persist = lambda: None
        sidebar_children = self.app.sidebar.get_children()
        sync_idx = sidebar_children.index(self.app.sync_status)
        inst_idx = sidebar_children.index(self.app.instances_section)
        agents_idx = sidebar_children.index(self.app.agents_list)
        self.assertEqual(inst_idx, sync_idx + 1)
        self.assertLess(inst_idx, agents_idx)

        first, _ = self.make_session("very-long-workspace-name-for-full-display")
        second, _ = self.make_session("second-workspace")
        first["item"]["display_name"] = "very-long-workspace-name-for-full-display · Codex"
        second["item"]["display_name"] = "second-workspace · Antigravity"
        clicked = []
        for tag, s in (("first", first), ("second", second)):
            img_btn = Gtk.Button()
            img_btn.connect("clicked", lambda *_, t=tag: clicked.append(("image", t)))
            cls_btn = Gtk.Button()
            cls_btn.connect("clicked", lambda *_, t=tag: clicked.append(("close", t)))
            s["image_button"] = img_btn
            s["close_button"] = cls_btn

        self.app.show_all()
        self.assertFalse(self.app.instances_section.get_visible())

        self.app.view_button.set_active(False)
        self.assertTrue(self.app.instances_section.get_visible())
        self.assertEqual(self.app.instances_heading.get_text(), "工作实例")
        rows = self.app.instances_list.get_children()
        self.assertEqual(len(rows), 2)

        row_children = rows[0].get_children()
        self.assertEqual(len(row_children), 1)
        tab_btn = row_children[0]
        btn_children = tab_btn.get_child().get_children()
        self.assertEqual(len(btn_children), 4)
        title_lbl, edit_btn, img_btn, close_btn = btn_children
        self.assertEqual(edit_btn.get_tooltip_text(), "编辑实例名称")
        self.assertEqual(title_lbl.get_text(), "very-long-workspace-name-for-full-display · Codex")
        self.assertEqual(title_lbl.get_ellipsize(), Pango.EllipsizeMode.END)
        self.assertTrue(title_lbl.get_single_line_mode())
        self.assertFalse(title_lbl.get_line_wrap())

        # Clicking second tab switches active page and updates highlight
        second_btn = rows[1].get_children()[0]
        second_btn.clicked()
        self.assertEqual(self.app.tabs.get_current_page(), 1)
        self.assertTrue(second_btn.get_style_context().has_class("host-selected"))
        self.assertFalse(tab_btn.get_style_context().has_class("host-selected"))

        img_btn.clicked()
        close_btn.clicked()
        self.assertEqual(clicked, [("image", "first"), ("close", "first")])

    def make_session(self, name):
        Gtk, Vte = app_module.Gtk, app_module.Vte
        terminal = Vte.Terminal()
        terminal.set_size(24, 8)
        pty = Vte.Pty.new_sync(Vte.PtyFlags.DEFAULT, None)
        terminal.set_pty(pty)
        header = Gtk.Box()
        header.pack_start(Gtk.Label(label=f"{name} · Codex"), True, True, 0)
        floating = Gtk.Button.new_from_icon_name("window-new-symbolic", Gtk.IconSize.MENU)
        header.pack_start(floating, False, False, 0)
        session = {"terminal": terminal, "label": header, "item": {"directory_name": name},
                   "state": {}, "float_button": floating}
        self.app.sessions.append(session)
        return session, pty

    def test_minimize_creates_one_button_per_session_and_restores_tiles(self):
        self.app.persist = lambda: None
        first, pty = self.make_session("project")
        second, _ = self.make_session("other")
        self.app.show_all()
        self.app.view_button.set_active(False)
        self.assertEqual(self.app.tabs.get_n_pages(), 2)
        self.app.minimize_session(first)
        self.app.minimize_session(first)
        self.app.minimize_session(second)
        self.assertEqual(self.app.tabs.get_n_pages(), 0)
        self.assertEqual(len(self.app.minimized_buttons.get_children()), 2)
        button = first["minimized_button"]
        self.assertEqual(button.get_child().get_children()[1].get_text(), "project")
        dot = first["minimized_dot"]
        self.app._animate_running_headers()
        self.assertTrue(dot.get_style_context().has_class("idle"))
        first["state"]["last_output_at"] = app_module.time.monotonic()
        self.app._animate_running_headers()
        self.assertTrue(dot.get_style_context().has_class("running"))
        first["state"]["needs_action"] = True
        self.app._animate_running_headers()
        self.assertTrue(dot.get_style_context().has_class("waiting"))
        self.assertEqual(dot.get_opacity(), 1)
        self.assertIs(first["terminal"].get_pty(), pty)
        button.clicked()
        self.assertTrue(self.app.tiled)
        self.assertEqual(len(self.app.minimized_buttons.get_children()), 1)
        self.assertIs(first["terminal"].get_pty(), pty)
        self.assertIsNotNone(first["terminal"].get_parent())
        self.assertIsNone(second["terminal"].get_parent())

    def test_floating_card_resizes_overhangs_and_returns_to_the_tab(self):
        self.app.persist = lambda: None
        session, pty = self.make_session("project")
        self.app.show_all()
        self.app.view_button.set_active(False)
        self.app.toggle_floating_session(session)
        window = session["floating_window"]
        self.assertEqual(self.app.tabs.get_n_pages(), 0)
        self.assertEqual(window.header.get_size_request()[1], 25)
        self.assertIs(session["terminal"].get_toplevel(), window)
        self.assertIs(session["terminal"].get_pty(), pty)
        self.assertEqual(session["float_button"].get_image().get_icon_name()[0], "view-restore-symbolic")

        x, y, width, height = window.geometry
        window.drag = ("left", 100, 100, window.geometry)
        window.drag_to(60, 100)
        self.assertEqual(window.geometry, (x - 40, y, width + 40, height))
        window.drag = ("bottom", 100, 100, window.geometry)
        window.drag_to(100, 155)
        self.assertEqual(window.geometry, (x - 40, y, width + 40, height + 55))
        window.place(-5000, -5000, width, height)
        left, top, _, _ = window.geometry
        self.assertLess(left, 0)
        self.assertLess(top, 0)
        self.assertGreaterEqual(left + width, 50)
        self.assertGreaterEqual(top + height, 50)
        window.place(5000, 5000, width, height)
        parent_width, parent_height = self.app.get_size()
        self.assertEqual(window.geometry[:2], (parent_width - 50, parent_height - 50))
        self.app.rebuild_view()
        self.assertIs(session["terminal"].get_toplevel(), window)
        self.app.toggle_floating_session(session)
        self.assertNotIn("floating_window", session)
        self.assertEqual(self.app.tabs.get_n_pages(), 1)
        self.assertIs(session["terminal"].get_pty(), pty)
        self.assertEqual(session["float_button"].get_image().get_icon_name()[0], "window-new-symbolic")

    def test_weekly_quota_is_next_to_hourly_in_the_live_card_header(self):
        self.app.persist = lambda: None
        host = {"id": "test-host", "address": "127.0.0.1", "port": 22, "username": "dev",
                "_resolved": True, "_active_address": "127.0.0.1", "_active_port": 22}
        shared = {"id": "a" * 32, "directory_name": "project", "directory_path": "/project",
                  "agent_name": "Codex", "agent_command": "codex"}
        self.app.show_all()
        with patch.object(app_module, "ssh_argv", return_value=(["/bin/cat"], shared["id"])):
            self.app.open_terminal(None, host, shared)
        session = self.app.sessions[0]
        try:
            hourly, weekly = session["quota_indicators"]
            session["update_quota"]([(300, 75, None), (10080, 40, "3d")])
            children = session["label"].get_children()
            self.assertIs(children[children.index(hourly) + 1], weekly)
            self.assertEqual(hourly.get_children()[1].get_text(), "75")
            self.assertEqual(weekly.get_children()[1].get_text(), "40")
            self.app.view_button.set_active(False)
            self.assertEqual(weekly.get_children()[1].get_text(), "40%")
            session["float_button"].clicked()
            self.assertEqual(weekly.get_children()[1].get_text(), "40")
            self.assertIs(weekly.get_toplevel(), session["floating_window"])
        finally:
            session["close_button"].clicked()

    def test_sidebar_width_clamps_and_restores_after_hiding(self):
        Gtk = app_module.Gtk
        self.app.show_all()
        while Gtk.events_pending():
            Gtk.main_iteration()
        paned = self.app.sidebar_paned
        self.assertIsInstance(paned, Gtk.Paned)
        paned.set_position(1)
        self.assertGreaterEqual(paned.get_position(), max(200, paned.get_property('min-position')))
        paned.set_position(400)
        self.assertEqual(self.app.sidebar_width, 400)
        toggle = Gtk.ToggleButton()
        self.app.toggle_sidebar(toggle)
        self.assertFalse(self.app.sidebar_revealer.get_visible())
        toggle.set_active(True)
        self.app.toggle_sidebar(toggle)
        self.assertTrue(self.app.sidebar_revealer.get_visible())
        self.assertEqual(paned.get_position(), 400)
        if self.app.sidebar_width_timer:
            app_module.GLib.source_remove(self.app.sidebar_width_timer)
            self.app.sidebar_width_timer = None

    def test_sidebar_edit_mode_hides_and_restores_management_controls(self):
        host = {'name': 'host', 'address': 'host', 'port': 22, 'username': 'user',
                'directories': [{'name': 'A', 'path': '/a'}, {'name': 'B', 'path': '/b'}]}
        self.app.data['hosts'] = [host]
        self.app.refresh()
        self.app.show_all()
        self.assertFalse(self.app.edit_mode)
        self.assertTrue(all(not box.get_visible() for box in self.app.edit_actions))
        self.assertEqual(len(self.app.hosts_list.get_children()[0].get_children()), 1)
        self.assertEqual(len(self.app.directories_list.get_children()[0].get_children()), 1)
        self.app.edit_toggle.set_active(True)
        self.assertEqual(self.app.edit_toggle.get_label(), '完成')
        self.assertTrue(all(box.get_visible() for box in self.app.edit_actions))
        self.assertTrue(all(child.get_visible() for box in self.app.edit_actions for child in box.get_children()))
        host_actions = self.app.hosts_list.get_children()[0].get_children()[1].get_children()
        self.assertEqual([button.get_label() for button in host_actions], ['✎', '×'])
        directory_actions = self.app.directories_list.get_children()[0].get_children()[1].get_children()
        self.assertEqual([button.get_label() for button in directory_actions], ['↑', '↓', '✎', '×'])
        self.app.edit_toggle.set_active(False)
        self.assertEqual(self.app.edit_toggle.get_label(), '编辑')
        self.assertTrue(all(not box.get_visible() for box in self.app.edit_actions))
        self.assertEqual(len(self.app.directories_list.get_children()[0].get_children()), 1)
        self.assertEqual([item['name'] for item in host['directories']], ['A', 'B'])

    def test_workspace_move_buttons_save_order_and_respect_bounds(self):
        host = {'name': 'host', 'address': 'host', 'port': 22, 'username': 'user',
                'directories': [{'name': 'A', 'path': '/a'}, {'name': 'B', 'path': '/b'}, {'name': 'C', 'path': '/c'}]}
        self.app.data['hosts'] = [host]
        with patch.object(self.app, 'persist') as persist, patch.object(self.app, 'push_workspace_list') as push:
            self.app.refresh()
            self.app.edit_toggle.set_active(True)
            rows = self.app.directories_list.get_children()
            actions = [row.get_children()[1].get_children() for row in rows]
            self.assertFalse(actions[0][0].get_sensitive())
            self.assertFalse(actions[-1][1].get_sensitive())
            actions[1][0].clicked()
            self.assertEqual([d['name'] for d in host['directories']], ['B', 'A', 'C'])
            self.assertEqual(self.app.directories.get_active(), 0)
            persist.assert_called_once()
            push.assert_called_once_with(host)
            self.app.move_directory(None, 0, -1)
            self.assertEqual([d['name'] for d in host['directories']], ['B', 'A', 'C'])

    def test_stale_workspace_fetch_cannot_overwrite_local_reorder(self):
        from unittest.mock import Mock
        host = {'name': 'host', 'address': 'host', 'port': 22, 'username': 'user',
                'directories': [{'name': 'A', 'path': '/a'}, {'name': 'B', 'path': '/b'}]}
        self.app.data.update(hosts=[host], sync_url='http://example', sync_token='token', auto_sync_enabled=True)
        self.app.sync_executor.shutdown(wait=True)
        jobs, callbacks = [], []
        self.app.sync_executor = Mock()
        self.app.sync_executor.submit.side_effect = jobs.append
        self.app.refresh()
        def sync_data(_config, method, path, payload=None):
            if path.startswith('/v1/workspaces/'):
                return {'configured': True, 'workspaces': [dict(d) for d in host['directories']]}
            return {'configured': False, 'agents': []}
        with patch.object(app_module, 'sync_request', return_value={'sessions': []}), \
             patch.object(app_module, 'sync_data_request', side_effect=sync_data), \
             patch.object(app_module.GLib, 'idle_add', side_effect=lambda fn, *args: callbacks.append((fn, args))), \
             patch.object(self.app, 'persist'):
            self.app.refresh_host_shared(host)
            jobs.pop(0)()  # GET completes, but its GUI callback is pending.
            self.app.move_directory(None, 1, -1)
            fn, args = callbacks.pop(0)
            fn(*args)
            self.assertEqual([d['name'] for d in host['directories']], ['B', 'A'])
            jobs.pop(0)()  # PUT the reordered snapshot.
            fn, args = callbacks.pop(0)
            fn(*args)
            self.assertFalse(self.app.workspace_dirty)

    def test_battery_indicator_shows_readable_text_and_levels(self):
        Gtk = app_module.Gtk
        indicator, update = app_module.make_quota_indicator()
        battery, text = indicator.get_children()
        level = battery.get_children()[0]
        for pct, expected, style in ((None, '--', 'quota-unknown'), (3, '3%', 'quota-low'), (15, '15%', 'quota-warning'), (85, '85%', 'quota-ok')):
            update(pct)
            self.assertEqual(text.get_text(), expected)
            self.assertEqual(level.get_value(), pct or 0)
            self.assertTrue(level.get_style_context().has_class(style))
        self.assertIsInstance(level, Gtk.LevelBar)
        indicator.set_show_percent(False)
        self.assertEqual(text.get_text(), '85')
        update(3)
        self.assertEqual(text.get_text(), '3')
        indicator.set_show_percent(True)
        self.assertEqual(text.get_text(), '3%')
        update(None)
        self.assertEqual(text.get_text(), '--')

    def test_running_outline_is_windowless_and_pass_through(self):
        Gtk = app_module.Gtk
        terminal = app_module.Vte.Terminal()
        header = Gtk.Box()
        header.pack_start(Gtk.Label(label='project · Codex'), True, True, 0)
        self.app.sessions = [{'terminal': terminal, 'label': header, 'key': 'k', 'id': 'id', 'state': {}}]
        self.app.tiled = True
        self.app.rebuild_view()
        outline = terminal._running_outline
        self.assertFalse(outline.get_has_window())
        self.assertFalse(outline.get_can_focus())
        overlay = outline.get_parent()
        self.assertTrue(overlay.get_overlay_pass_through(outline))

    def test_tiled_card_drag_and_drop_reorders_and_wraps_next_row(self):
        self.app.persist = lambda: None
        self.app.tiled = True
        self.app.tile_layout = "grid"
        a, _ = self.make_session("a")
        b, _ = self.make_session("b")
        c, _ = self.make_session("c")
        d, _ = self.make_session("d")
        self.app.show_all()
        self.app.rebuild_view()

        def grid_rows():
            root = self.app.grid.get_children()[0]
            rows = []
            for paned in (root.get_child1(), root.get_child2()):
                row = []
                for frame in (paned.get_child1(), paned.get_child2()):
                    row.append(frame.get_children()[1])
                rows.append(row)
            return rows

        self.assertEqual(grid_rows(), [[a["terminal"], b["terminal"]], [c["terminal"], d["terminal"]]])

        context = app_module.Gdk.DragContext()
        with patch.object(app_module.Gdk, "drag_status"), \
             patch.object(app_module.Gtk, "drag_finish"), \
             patch.object(app_module.Gtk, "drag_set_icon_pixbuf") as set_icon:
            c_frame = c["terminal"]._tile_frame
            c_header = c_frame.get_children()[0]
            a_frame = a["terminal"]._tile_frame
            a_header = a_frame.get_children()[0]
            c_header.emit("drag-begin", context)
            self.assertTrue(c_frame.get_style_context().has_class("tile-dragging"))
            self.assertEqual(set_icon.call_count, 1)
            alloc = c_header.get_allocation()
            expected_w = max(1, alloc.width // 2)
            expected_h = max(1, round(alloc.height * (expected_w / alloc.width)))
            icon_pixbuf = set_icon.call_args.args[1]
            self.assertEqual((icon_pixbuf.get_width(), icon_pixbuf.get_height()), (expected_w, expected_h))
            c_header.emit("drag-end", context)
            self.assertFalse(c_frame.get_style_context().has_class("tile-dragging"))

            c_header.emit("drag-begin", context)
            self.assertTrue(c_frame.get_style_context().has_class("tile-dragging"))
            # Hovering over card a's header or terminal highlights card a without flicker.
            self.assertTrue(a_header.emit("drag-motion", context, 10, 10, 0))
            self.assertTrue(a_frame.get_style_context().has_class("tile-drop-target"))
            self.assertTrue(a["terminal"].emit("drag-motion", context, 10, 10, 0))
            a_header.emit("drag-leave", context, 0)
            self.assertTrue(a_frame.get_style_context().has_class("tile-drop-target"))
            a["terminal"].emit("drag-leave", context, 0)
            self.assertFalse(a_frame.get_style_context().has_class("tile-drop-target"))
            a["terminal"].emit("drag-drop", context, 10, 10, 0)
            self.assertFalse(c["terminal"]._tile_frame.get_style_context().has_class("tile-dragging"))

        # c moves to a's slot; a shifts right; b wraps down to the start of row 2.
        self.assertEqual([s["item"]["directory_name"] for s in self.app.sessions], ["c", "a", "b", "d"])
        self.assertEqual(grid_rows(), [[c["terminal"], a["terminal"]], [b["terminal"], d["terminal"]]])

        # Dragging d onto a (end of row 1) puts d at row 1 col 2 and wraps a to row 2 col 1.
        with patch.object(app_module.Gdk, "drag_status"), patch.object(app_module.Gtk, "drag_finish"):
            d_frame = d["terminal"]._tile_frame
            d_header = d_frame.get_children()[0]
            a_frame = a["terminal"]._tile_frame
            d_header.emit("drag-begin", context)
            d_frame.emit("drag-drop", context, 10, 10, 0)  # self-drop is a no-op
            self.assertEqual([s["item"]["directory_name"] for s in self.app.sessions], ["c", "a", "b", "d"])
            a_frame.emit("drag-drop", context, 10, 10, 0)
            d_header.emit("drag-end", context)

        self.assertEqual([s["item"]["directory_name"] for s in self.app.sessions], ["c", "d", "a", "b"])
        self.assertEqual(grid_rows(), [[c["terminal"], d["terminal"]], [a["terminal"], b["terminal"]]])

        # Floating sessions must not participate in tiled card replacement or hijack header drag.
        self.app.toggle_floating_session(d)
        self.assertIsNone(d["floating_window"].header.drag_source_get_target_list())
        self.assertIsNone(d["terminal"]._tile_frame.drag_dest_get_target_list())
        self.assertFalse(self.app.move_tiled_session(d, c))
        self.assertFalse(self.app.move_tiled_session(c, d))
        self.assertEqual([s["item"]["directory_name"] for s in self.app.sessions], ["c", "d", "a", "b"])
        self.app.toggle_floating_session(d)


if __name__ == '__main__':
    unittest.main()
