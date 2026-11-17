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
        self.app.show_all()
        def labels(widget):
            if isinstance(widget, Gtk.Label):
                yield widget
            if isinstance(widget, Gtk.Container):
                for child in widget.get_children():
                    yield from labels(child)
        by_text = {label.get_text(): label for label in labels(self.app)}
        for text in ('REMOTE DEV', '添加主机', '刷新同步'):
            color = by_text[text].get_style_context().get_color(Gtk.StateFlags.BACKDROP)
            self.assertGreater(min(color.red, color.green, color.blue), 0.45, text)

    def test_repeated_mode_switch_preserves_header_and_quota(self):
        Gtk = app_module.Gtk
        terminal = app_module.Vte.Terminal()
        header = Gtk.Box()
        name = Gtk.Label(label='project · Codex')
        quota = Gtk.Label(label='余量 50%')
        header.pack_start(name, True, True, 0)
        header.pack_start(quota, False, False, 0)
        self.app.sessions = [{'terminal': terminal, 'label': header, 'key': 'k', 'id': 'id', 'state': {}}]
        self.app.show_all()
        for tiled in (True, False, True, False, True, False):
            self.app.tiled = tiled
            self.app.rebuild_view()
            self.assertEqual(header.get_children(), [name, quota])
            self.assertEqual(quota.get_text(), '余量 50%')
            if not tiled:
                self.assertEqual(self.app.tabs.get_n_pages(), 1)
                self.assertIsNotNone(self.app.tabs.get_tab_label(terminal))
                while Gtk.events_pending():
                    Gtk.main_iteration()
                self.assertTrue(name.get_mapped(), 'tab name must actually be displayed')
                self.assertTrue(quota.get_mapped(), 'tab quota must actually be displayed')

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
        self.assertEqual(len(self.app.hosts_list.get_children()[0].get_children()), 3)
        self.assertEqual(len(self.app.directories_list.get_children()[0].get_children()), 5)
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
            self.assertFalse(rows[0].get_children()[1].get_sensitive())
            self.assertFalse(rows[-1].get_children()[2].get_sensitive())
            rows[1].get_children()[1].clicked()
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
        self.app.data.update(hosts=[host], sync_url='http://example', sync_token='token')
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


if __name__ == '__main__':
    unittest.main()
